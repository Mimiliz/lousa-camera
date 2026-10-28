package br.com.mimiexplica.sala

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** Motor de vídeo e áudio (WebRTC): câmera, microfone e a tela compartilhada. */
class Rtc(private val ctx: Context) {
    val egl: EglBase = EglBase.create()
    /** Recebe o som do microfone, para a gravação da aula. */
    @Volatile var micSink: ((JavaAudioDeviceModule.AudioSamples) -> Unit)? = null

    val factory: PeerConnectionFactory
    private val adm: JavaAudioDeviceModule
    val config: PeerConnection.RTCConfiguration

    private val enumerator = Camera2Enumerator(ctx)
    private var camCapturer: CameraVideoCapturer? = null
    private var camHelper: SurfaceTextureHelper? = null
    private val camSource: VideoSource
    val camTrack: VideoTrack
    val micTrack: AudioTrack
    var camName: String? = null; private set

    val screenSource: VideoSource
    val screenTrack: VideoTrack

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        adm = JavaAudioDeviceModule.builder(ctx)
            // som tocado como "mídia": assim a gravação do professor consegue capturar a voz do aluno
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { s -> micSink?.invoke(s) }
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(listOf("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"))
                .setUsername("peerjs").setPassword("peerjsp").createIceServer()
        )
        config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        camSource = factory.createVideoSource(false)
        camTrack = factory.createVideoTrack("cam0", camSource)
        micTrack = factory.createAudioTrack("mic0", factory.createAudioSource(MediaConstraints()))
        screenSource = factory.createVideoSource(true)
        screenTrack = factory.createVideoTrack("screen0", screenSource)
    }

    /** Câmeras do aparelho: frontal, traseira e USB (quando o Android reconhece). */
    fun cameras(): List<String> = enumerator.deviceNames.toList()

    fun cameraLabel(name: String): String = when {
        enumerator.isFrontFacing(name) -> "Câmera frontal"
        enumerator.isBackFacing(name) -> "Câmera traseira"
        else -> "Câmera USB ($name)"
    }

    fun isFront(name: String?) = name != null && enumerator.isFrontFacing(name)

    /** Microfones ligados ao aparelho (do próprio aparelho, fone, USB, Bluetooth). */
    fun mics(): List<AudioDeviceInfo> {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        }.distinctBy { it.type.toString() + it.productName }
    }

    fun micLabel(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone do aparelho"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (${d.productName})"
        else -> "Microfone USB (${d.productName})"
    }

    fun setMic(d: AudioDeviceInfo) {
        adm.setPreferredInputDevice(d)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                am.availableCommunicationDevices.firstOrNull { it.type == d.type }?.let { am.setCommunicationDevice(it) }
            } else am.clearCommunicationDevice()
        }
    }

    fun startCamera(preferred: String?) {
        val names = cameras()
        if (names.isEmpty()) return
        val name = preferred?.takeIf { it in names }
            ?: names.firstOrNull { !enumerator.isFrontFacing(it) && !enumerator.isBackFacing(it) }
            ?: names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.first()
        stopCamera()
        val cap = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
        cap.initialize(helper, ctx, camSource.capturerObserver)
        cap.startCapture(1280, 720, 30)
        camCapturer = cap; camHelper = helper; camName = name
    }

    fun switchTo(name: String, done: () -> Unit) {
        val cap = camCapturer ?: run { startCamera(name); done(); return }
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { camName = name; Handler(Looper.getMainLooper()).post(done) }
            override fun onCameraSwitchError(error: String?) { Handler(Looper.getMainLooper()).post { startCamera(name); done() } }
        }, name)
    }

    fun stopCamera() {
        try { camCapturer?.stopCapture() } catch (_: Exception) {}
        camCapturer?.dispose(); camCapturer = null
        camHelper?.dispose(); camHelper = null
    }

    fun release() {
        stopCamera()
        camTrack.dispose(); micTrack.dispose(); screenTrack.dispose()
        camSource.dispose(); screenSource.dispose()
        factory.dispose()
        egl.release()
    }
}

/** Uma ligação (câmera ou tela) com o outro lado, no formato que o PeerJS entende. */
class Call(
    rtc: Rtc,
    private val sig: Signaling,
    val peer: String,
    val connectionId: String,
    val kind: String,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onRemoteAudio: (AudioTrack) -> Unit = {},
    private val onEnded: (Call) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var closed = false
    var connected = false; private set

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected = true
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> end()
                    PeerConnection.IceConnectionState.DISCONNECTED -> main.postDelayed({
                        if (!closed && pc.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) end()
                    }, 4000)
                    else -> {}
                }
            }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {
            val cand = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            main.post { sig.send("CANDIDATE", peer, JSONObject().put("candidate", cand).put("type", "media").put("connectionId", connectionId)) }
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val t = receiver?.track()
            if (t is VideoTrack) main.post { if (!closed) onRemoteVideo(t) }
            if (t is AudioTrack) main.post { if (!closed) onRemoteAudio(t) }
        }
    }

    val pc: PeerConnection = rtc.factory.createPeerConnection(rtc.config, observer)
        ?: throw IllegalStateException("Não consegui criar a conexão")

    fun addTracks(tracks: List<MediaStreamTrack>, streamId: String) { for (t in tracks) pc.addTrack(t, listOf(streamId)) }

    private open inner class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { main.post { end() } }
        override fun onSetFailure(e: String?) { main.post { end() } }
    }

    /** Nós ligamos: manda a oferta. */
    fun offer(metadata: JSONObject) {
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                d ?: return
                pc.setLocalDescription(Sdp(), d)
                val sdp = JSONObject().put("type", "offer").put("sdp", d.description)
                main.post {
                    sig.send("OFFER", peer, JSONObject().put("sdp", sdp).put("type", "media")
                        .put("connectionId", connectionId).put("metadata", metadata).put("browser", "android"))
                }
            }
        }, MediaConstraints())
    }

    /** O outro lado ligou: aceita e responde. */
    fun answer(offerSdp: String) {
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                main.post { remoteSet = true; flush() }
                pc.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(d: SessionDescription?) {
                        d ?: return
                        pc.setLocalDescription(Sdp(), d)
                        val sdp = JSONObject().put("type", "answer").put("sdp", d.description)
                        main.post {
                            sig.send("ANSWER", peer, JSONObject().put("sdp", sdp).put("type", "media")
                                .put("connectionId", connectionId).put("browser", "android"))
                        }
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    fun onAnswer(answerSdp: String) {
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() { main.post { remoteSet = true; flush() } }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    fun onCandidate(c: JSONObject) {
        val ic = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.optString("candidate"))
        if (remoteSet) pc.addIceCandidate(ic) else pending.add(ic)
    }

    private fun flush() { for (c in pending) pc.addIceCandidate(c); pending.clear() }

    fun end() {
        if (closed) return
        closed = true
        try { pc.close() } catch (_: Exception) {}
        onEnded(this)
    }
}
