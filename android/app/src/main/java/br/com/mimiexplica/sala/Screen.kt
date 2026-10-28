package br.com.mimiexplica.sala

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Serviço com notificação: o Android exige isso para compartilhar ou gravar a tela. */
class AulaService : Service() {
    companion object { var onReady: (() -> Unit)? = null }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("aula", "Aula", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "aula")
            .setContentTitle("Sala Mimi Explica")
            .setContentText("Aula em andamento")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true).build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        Handler(Looper.getMainLooper()).post { onReady?.invoke(); onReady = null }
        return START_NOT_STICKY
    }
}

/**
 * Captura da tela inteira. Os quadros vão para quem precisar: a tela compartilhada com o aluno
 * e/ou a gravação. Uma captura só serve para as duas coisas (o Android só permite uma por vez).
 */
class ScreenCapture(private val data: Intent, private val onStopped: () -> Unit) : VideoCapturer, VideoSink {
    private lateinit var helper: SurfaceTextureHelper
    private lateinit var ctx: Context
    private var observer: CapturerObserver? = null
    var projection: MediaProjection? = null; private set
    private var vd: VirtualDisplay? = null
    @Volatile var sendToCall = false
    val sinks = CopyOnWriteArrayList<VideoSink>()
    var width = 0; private set
    var height = 0; private set

    override fun initialize(h: SurfaceTextureHelper, c: Context, obs: CapturerObserver) { helper = h; ctx = c; observer = obs }

    override fun startCapture(w: Int, h: Int, fps: Int) {
        width = w; height = h
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(Activity.RESULT_OK, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Handler(Looper.getMainLooper()).post { onStopped() } }
        }, Handler(Looper.getMainLooper()))
        helper.setTextureSize(w, h)
        vd = p.createVirtualDisplay("sala", w, h, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, Surface(helper.surfaceTexture), null, null)
        helper.startListening(this)
        observer?.onCapturerStarted(true)
    }

    override fun onFrame(frame: VideoFrame) {
        if (sendToCall) observer?.onFrameCaptured(frame)
        for (s in sinks) s.onFrame(frame)
    }

    override fun stopCapture() {
        try { helper.stopListening() } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vd = null; projection = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(w: Int, h: Int, fps: Int) {}
    override fun dispose() {}
    override fun isScreencast(): Boolean = true
}

/**
 * Gravação da aula no tablet do professor: a tela (o que você vê), a sua voz e a voz do aluno.
 * Vídeo H.264 + áudio AAC num arquivo MP4.
 */
class ClassRecorder(
    private val shared: EglBase.Context,
    private val w: Int,
    private val h: Int,
    private val projection: MediaProjection?,
    val file: File
) : VideoSink {
    private val vThread = HandlerThread("grava-video").apply { start() }
    private val vh = Handler(vThread.looper)
    private lateinit var egl: EglBase
    private val frameDrawer = VideoFrameDrawer()
    private lateinit var glDrawer: GlRectDrawer
    private val video: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val audio: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var vTrack = -1
    private var aTrack = -1
    @Volatile private var muxing = false
    @Volatile private var running = false
    @Volatile var paused = false
        set(v) { if (v && !field) pauseStart = System.nanoTime() else if (!v && field) pausedNs += System.nanoTime() - pauseStart; field = v }
    private var pauseStart = 0L
    private var pausedNs = 0L
    private val t0 = System.nanoTime()
    private var lastFrame = 0L
    private val lock = Object()

    // microfone: amostras a 48 kHz mono esperando para serem misturadas
    private val mic = ShortArray(48000 * 2)
    private var micW = 0
    private var micR = 0

    private fun nowUs() = (System.nanoTime() - t0 - pausedNs) / 1000

    fun start() {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w * h > 1_500_000) 8_000_000 else 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = video.createInputSurface()
        video.start()
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audio.start()
        running = true
        vh.post {
            egl = EglBase.create(shared, EglBase.CONFIG_RECORDABLE)
            egl.createSurface(input)
            egl.makeCurrent()
            glDrawer = GlRectDrawer()
        }
        thread(name = "grava-saida-video") { drain(video, true) }
        thread(name = "grava-audio") { audioLoop() }
    }

    /** Quadro da tela: desenha no codificador (no máximo 30 por segundo). */
    override fun onFrame(frame: VideoFrame) {
        if (!running || paused) return
        val now = System.nanoTime()
        if (now - lastFrame < 32_000_000L) return
        lastFrame = now
        frame.retain()
        vh.post {
            try {
                if (running && ::glDrawer.isInitialized) {
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    frameDrawer.drawFrame(frame, glDrawer, null, 0, 0, w, h)
                    egl.swapBuffers(nowUs() * 1000)
                }
            } catch (_: Exception) {
            } finally { frame.release() }
        }
    }

    /** Voz do professor (vem do microfone da chamada). */
    fun onMic(s: JavaAudioDeviceModule.AudioSamples) {
        if (!running || paused) return
        val bb = ByteBuffer.wrap(s.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ch = s.channelCount.coerceAtLeast(1)
        val frames = bb.remaining() / ch
        val ratio = s.sampleRate / 48000.0
        val outN = (frames / ratio).toInt()
        synchronized(lock) {
            for (i in 0 until outN) {
                val src = (i * ratio).toInt().coerceAtMost(frames - 1)
                var sum = 0
                for (c in 0 until ch) sum += bb.get(src * ch + c)
                mic[micW] = (sum / ch).toShort()
                micW = (micW + 1) % mic.size
                if (micW == micR) micR = (micR + 1) % mic.size // fila cheia: descarta o mais antigo
            }
        }
    }

    private fun takeMic(out: ShortArray, n: Int) {
        synchronized(lock) {
            var avail = (micW - micR + mic.size) % mic.size
            if (avail > 48000 / 5) { micR = (micW - 4800 + mic.size) % mic.size; avail = 4800 } // atraso grande: alcança
            for (i in 0 until n) {
                if (avail > 0) { out[i] = mic[micR]; micR = (micR + 1) % mic.size; avail-- } else out[i] = 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        // voz do aluno: o som que o próprio aparelho está tocando
        val rec: AudioRecord? = try {
            projection?.let { p ->
                val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(48000)
                    .setAudioPlaybackCaptureConfig(cfg).build().also { it.startRecording() }
            }
        } catch (e: Exception) { null }
        val n = 480 // 10 ms
        val other = ShortArray(n)
        val mine = ShortArray(n)
        val bytes = ByteArray(n * 2)
        var samples = 0L
        var next = System.nanoTime()
        val info = MediaCodec.BufferInfo()
        while (running) {
            if (rec != null) {
                var got = 0
                while (got < n && running) { val r = rec.read(other, got, n - got); if (r <= 0) break; got += r }
                for (i in got until n) other[i] = 0
            } else {
                java.util.Arrays.fill(other, 0.toShort())
                next += 10_000_000L
                val sleep = (next - System.nanoTime()) / 1_000_000L
                if (sleep > 0) Thread.sleep(sleep)
            }
            if (paused) continue
            takeMic(mine, n)
            for (i in 0 until n) {
                val v = (other[i] + mine[i]).coerceIn(-32768, 32767)
                bytes[2 * i] = (v and 0xFF).toByte(); bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            val idx = audio.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = audio.getInputBuffer(idx)!!
                buf.clear(); buf.put(bytes)
                audio.queueInputBuffer(idx, 0, bytes.size, samples * 1_000_000L / 48000, 0)
                samples += n
            }
            drainOnce(audio, false, info)
        }
        try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        val idx = audio.dequeueInputBuffer(20_000)
        if (idx >= 0) audio.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(audio, false)
    }

    private fun addTrack(isVideo: Boolean, f: MediaFormat) {
        synchronized(muxer) {
            if (isVideo) vTrack = muxer.addTrack(f) else aTrack = muxer.addTrack(f)
            if (vTrack >= 0 && aTrack >= 0 && !muxing) { muxer.start(); muxing = true }
        }
    }

    /** Devolve true quando chegou o fim. */
    private fun drainOnce(codec: MediaCodec, isVideo: Boolean, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 0)
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(isVideo, codec.outputFormat)
                i >= 0 -> {
                    val out = codec.getOutputBuffer(i)
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!config && info.size > 0 && muxing && out != null) {
                        synchronized(muxer) { muxer.writeSampleData(if (isVideo) vTrack else aTrack, out, info) }
                    }
                    codec.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
                else -> return false
            }
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (drainOnce(codec, isVideo, info)) break
            if (!running && stopAt > 0 && System.currentTimeMillis() - stopAt > 2500) break
            Thread.sleep(5)
        }
        if (isVideo) videoDone = true
    }
    @Volatile private var videoDone = false
    @Volatile private var stopAt = 0L

    fun stop(done: (File?) -> Unit) {
        stopAt = System.currentTimeMillis()
        running = false
        vh.post {
            try { video.signalEndOfInputStream() } catch (_: Exception) {}
            thread {
                val t = System.currentTimeMillis()
                while (!videoDone && System.currentTimeMillis() - t < 3000) Thread.sleep(20)
                Thread.sleep(300)
                var ok = muxing
                try { video.stop(); video.release() } catch (_: Exception) {}
                try { audio.stop(); audio.release() } catch (_: Exception) {}
                try { if (muxing) muxer.stop(); muxer.release() } catch (_: Exception) { ok = false }
                vh.post {
                    try { glDrawer.release(); frameDrawer.release(); egl.release() } catch (_: Exception) {}
                    vThread.quitSafely()
                }
                Handler(Looper.getMainLooper()).post { done(if (ok && file.length() > 0) file else null) }
            }
        }
    }
}
