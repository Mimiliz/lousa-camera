package br.com.mimiexplica.sala

import android.Manifest
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Papel { PROFESSOR, ALUNO }

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val SITE = "https://mimiliz.github.io/lousa-camera/"
    private val ACCENT = 0xFF37C99A.toInt()
    private val CHROME = 0xFF14161B.toInt()
    private val RAISED = 0xFF272B34.toInt()
    private val MUTED = 0xFF8E96A5.toInt()
    private val FG = 0xFFECEEF2.toInt()

    // aula
    private var papel = Papel.ALUNO
    private var sala = ""
    private var rtc: Rtc? = null
    private var sig: Signaling? = null
    private val calls = HashMap<String, Call>()
    private var camCall: Call? = null
    private var screenCall: Call? = null
    private var otherPeer: String? = null
    private var otherVia = ""
    private var remoteCam: VideoTrack? = null
    private var remoteScreen: VideoTrack? = null
    private var remoteAudio: org.webrtc.AudioTrack? = null
    private var teacherMuted = false   // aluno: o professor desligou meu microfone
    private var studentMuted = false   // professor: eu desliguei o microfone do aluno
    private var studentMicOn: Boolean? = null
    private var inCall = false
    private var leaving = false

    // tela e gravação
    private var capture: ScreenCapture? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var sharing = false
    private var recorder: ClassRecorder? = null
    private var recStart = 0L
    private var pendingCapture: (() -> Unit)? = null

    // interface da aula
    private lateinit var bigR: SurfaceViewRenderer
    private lateinit var smallR: SurfaceViewRenderer
    private lateinit var meR: SurfaceViewRenderer
    private lateinit var waitTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var recTv: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var miniCol: View
    private var bigSrc: VideoTrack? = null
    private var smallSrc: VideoTrack? = null
    private val buttons = HashMap<String, Pair<ImageView, TextView>>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun pill(color: Int, radius: Float = 999f) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius) }

    private var afterPerms: (() -> Unit)? = null
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == false && r[Manifest.permission.RECORD_AUDIO] == false) toast("Sem câmera e microfone a aula não funciona.")
        afterPerms?.invoke(); afterPerms = null
    }
    private val askScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val then = pendingCapture; pendingCapture = null
        if (res.resultCode == RESULT_OK && res.data != null && then != null) {
            val data = res.data!!
            AulaService.onReady = { createCapture(data); then() }
            ContextCompat.startForegroundService(this, Intent(this, AulaService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sala", MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inCall) confirmLeave() else finish()
            }
        })
        if (!handleLink(intent)) showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!inCall) handleLink(intent)
    }

    /** Link do convite: mimisala://entrar?sala=XXXXXX */
    private fun handleLink(i: Intent?): Boolean {
        val u = i?.data ?: return false
        val code = u.getQueryParameter("sala")?.uppercase()?.filter { it.isLetterOrDigit() } ?: return false
        if (code.length < 4) return false
        prefs.edit().putString("codigoAluno", code).apply()
        start(Papel.ALUNO, code)
        return true
    }

    // ================= tela inicial =================

    private fun showHome() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0D0F13.toInt()); setPadding(dpi(24f), dpi(40f), dpi(24f), dpi(24f))
        }
        col.addView(TextView(this).apply { text = "Sala Mimi Explica"; setTextColor(FG); textSize = 28f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply {
            text = "Aula ao vivo: vocês se veem, conversam e podem mostrar a tela um para o outro."
            setTextColor(MUTED); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dpi(8f), 0, dpi(28f))
        }, LinearLayout.LayoutParams(dpi(460f).coerceAtMost(resources.displayMetrics.widthPixels - dpi(48f)), -2))

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = pill(0xFF1D2027.toInt(), 18f)
            setPadding(dpi(20f), dpi(18f), dpi(20f), dpi(20f))
        }
        val w = dpi(460f).coerceAtMost(resources.displayMetrics.widthPixels - dpi(48f))
        val prof = card()
        prof.addView(TextView(this).apply { text = "Sou o professor"; setTextColor(FG); textSize = 18f; typeface = Typeface.DEFAULT_BOLD })
        prof.addView(TextView(this).apply {
            text = "Abre a sua sala. Só você pode gravar a aula."; setTextColor(MUTED); textSize = 14f; setPadding(0, dpi(4f), 0, dpi(14f))
        })
        prof.addView(bigButton("Abrir minha sala", ACCENT, 0xFF06281D.toInt()) { start(Papel.PROFESSOR, roomCode()) })
        col.addView(prof, LinearLayout.LayoutParams(w, -2).apply { bottomMargin = dpi(16f) })

        val aluno = card()
        aluno.addView(TextView(this).apply { text = "Sou aluno"; setTextColor(FG); textSize = 18f; typeface = Typeface.DEFAULT_BOLD })
        aluno.addView(TextView(this).apply {
            text = "Digite o código que o professor passou."; setTextColor(MUTED); textSize = 14f; setPadding(0, dpi(4f), 0, dpi(12f))
        })
        val ed = EditText(this).apply {
            hint = "CÓDIGO"; setText(prefs.getString("codigoAluno", "")); setTextColor(FG); setHintTextColor(MUTED)
            textSize = 22f; gravity = Gravity.CENTER; letterSpacing = 0.2f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(8), InputFilter.AllCaps())
            background = pill(RAISED, 12f); setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
        }
        aluno.addView(ed, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(12f) })
        aluno.addView(bigButton("Entrar na aula", RAISED, FG) {
            val code = ed.text.toString().uppercase().filter { it.isLetterOrDigit() }
            if (code.length < 4) { toast("Digite o código completo"); return@bigButton }
            prefs.edit().putString("codigoAluno", code).apply()
            start(Papel.ALUNO, code)
        })
        col.addView(aluno, LinearLayout.LayoutParams(w, -2))
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(0xFF0D0F13.toInt()); isFillViewport = true; addView(col) })
    }

    private fun bigButton(t: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(fg); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        background = pill(bg, 14f); minHeight = dpi(52f); setOnClickListener { onClick() }
    }

    private fun roomCode(): String = prefs.getString("sala", null) ?: newCode().also { prefs.edit().putString("sala", it).apply() }
    private fun newCode(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { abc[(Math.random() * abc.length).toInt()] }.joinToString("")
    }
    private fun rnd() = java.lang.Long.toString((Math.random() * 1e15).toLong(), 36)

    // ================= começar a aula =================

    private fun start(p: Papel, code: String) {
        papel = p; sala = code
        val need = arrayListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) enterCall() else { afterPerms = { enterCall() }; askPerms.launch(missing.toTypedArray()) }
    }

    private fun enterCall() {
        // aluno: o Android bloqueia print e gravação de tela deste app
        if (papel == Papel.ALUNO) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        inCall = true; leaving = false; teacherMuted = false; studentMuted = false; studentMicOn = null
        val r = Rtc(this); rtc = r
        buildCallUi(r)
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
        r.startCamera(prefs.getString("camera", null))
        meR.setMirror(r.isFront(r.camName))
        r.camTrack.addSink(meR)

        val myId = if (papel == Papel.PROFESSOR) "mimisala-$sala" else "mimialuno-" + rnd()
        sig = Signaling(myId, object : Signaling.Listener {
            override fun onOpen() {
                if (papel == Papel.PROFESSOR) status(if (otherPeer == null) "Sala $sala aberta. Convide o aluno." else statusAluno())
                else dial()
            }
            override fun onIdTaken() { status("Abrindo a sala…") }
            override fun onMessage(type: String, src: String, payload: JSONObject?) { onSignal(type, src, payload) }
            override fun onDisconnected() { status("Sem internet. Tentando de novo…") }
        })
        status(if (papel == Papel.PROFESSOR) "Abrindo a sala $sala…" else "Conectando…")
        sig!!.connect()
        if (Build.VERSION.SDK_INT >= 31) setPictureInPictureParams(pipParams())
        if (papel == Papel.PROFESSOR && prefs.getBoolean("primeiraVez", true)) {
            prefs.edit().putBoolean("primeiraVez", false).apply(); ui.postDelayed({ invite() }, 600)
        }
    }

    private fun statusAluno() = if (otherVia == "app") "Aluno na aula · pelo app (print e gravação bloqueados)"
        else "Aluno na aula · pelo navegador (sem bloqueio de print)"

    private fun status(t: String) { if (::statusTv.isInitialized) statusTv.text = t }

    // ================= sinalização =================

    private fun onSignal(type: String, src: String, payload: JSONObject?) {
        val r = rtc ?: return
        val s = sig ?: return
        when (type) {
            "OFFER" -> {
                payload ?: return
                val connId = payload.optString("connectionId")
                val meta = payload.optJSONObject("metadata")
                val kind = meta?.optString("kind", "cam") ?: "cam"
                val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
                if (kind == "screen") {
                    val c = Call(r, s, src, connId, "screen",
                        onRemoteVideo = { t -> remoteScreen = t; layout() },
                        onEnded = { c -> calls.remove(c.connectionId); remoteScreen = null; layout() })
                    calls[connId] = c
                    c.answer(sdp)
                } else if (papel == Papel.PROFESSOR) {
                    camCall?.let { if (it.peer != src) endOther() else it.end() }
                    otherPeer = src; otherVia = meta?.optString("via", "navegador") ?: "navegador"
                    val c = Call(r, s, src, connId, "cam",
                        onRemoteVideo = { t -> remoteCam = t; layout(); status(statusAluno()); sendCtl(JSONObject().put("mute", studentMuted)); paintStudentMic() },
                        onRemoteAudio = { a -> remoteAudio = a; a.setEnabled(!studentMuted) },
                        onEnded = { c -> calls.remove(c.connectionId); if (camCall === c) otherLeft() })
                    c.addTracks(listOf(r.camTrack, r.micTrack), "cam")
                    calls[connId] = c; camCall = c
                    c.answer(sdp)
                    if (sharing) ui.postDelayed({ startScreenCall() }, 1500)
                }
            }
            "ANSWER" -> {
                val sdp = payload?.optJSONObject("sdp")?.optString("sdp") ?: return
                calls[payload.optString("connectionId")]?.onAnswer(sdp)
            }
            "CANDIDATE" -> {
                payload?.optJSONObject("ctl")?.let { onCtl(src, it); return }
                val cand = payload?.optJSONObject("candidate") ?: return
                calls[payload.optString("connectionId")]?.onCandidate(cand)
            }
            "LEAVE" -> if (src == otherPeer) endOther()
            "EXPIRE" -> if (papel == Papel.ALUNO) { status("O professor ainda não abriu a sala…"); ui.postDelayed({ dial() }, 3000) }
        }
    }

    private var lastDial = 0L

    /** Recados entre professor e aluno (passam pelo mesmo servidor de encontro). */
    private fun sendCtl(o: JSONObject) {
        val p = otherPeer ?: return
        sig?.send("CANDIDATE", p, JSONObject().put("type", "media").put("connectionId", "ctl").put("ctl", o))
    }
    private fun sendMicState() { sendCtl(JSONObject().put("micOn", rtc?.micTrack?.enabled() == true)) }

    private fun onCtl(src: String, c: JSONObject) {
        if (src != otherPeer) return
        if (papel == Papel.ALUNO && c.has("mute")) {
            teacherMuted = c.optBoolean("mute")
            rtc?.micTrack?.setEnabled(!teacherMuted)
            paintMic(); sendMicState()
            toast(if (teacherMuted) "O professor desligou o seu microfone" else "O professor liberou o seu microfone")
        }
        if (papel == Papel.PROFESSOR && c.has("micOn")) { studentMicOn = c.optBoolean("micOn"); paintStudentMic() }
    }

    private fun paintMic() {
        val on = rtc?.micTrack?.enabled() == true
        if (teacherMuted) setOn("mic", false, "Microfone", "Mutado pelo professor", Icons.MIC, Icons.MIC_OFF)
        else setOn("mic", on, "Microfone", "Mudo", Icons.MIC, Icons.MIC_OFF)
    }

    private fun paintStudentMic() {
        setOn("mutar", !studentMuted, "Mutar aluno", "Liberar aluno")
        if (otherPeer != null && remoteCam != null)
            status(statusAluno() + when {
                studentMuted -> " · microfone do aluno desligado por você"
                studentMicOn == false -> " · aluno com microfone desligado"
                else -> ""
            })
    }

    private fun toggleStudentMute() {
        if (otherPeer == null || remoteCam == null) { toast("Nenhum aluno na sala agora"); return }
        studentMuted = !studentMuted
        remoteAudio?.setEnabled(!studentMuted)   // silêncio do seu lado e na gravação
        sendCtl(JSONObject().put("mute", studentMuted)); paintStudentMic()
        toast(if (studentMuted) "Microfone do aluno desligado" else "Microfone do aluno liberado")
    }

    private fun chooseMic() {
        val r = rtc ?: return
        val list = r.mics()
        if (list.isEmpty()) { toast("Nenhum microfone encontrado"); return }
        AlertDialog.Builder(this).setTitle("Escolher microfone")
            .setItems(list.map { r.micLabel(it) }.toTypedArray()) { _, i -> r.setMic(list[i]); toast("Microfone: " + r.micLabel(list[i])) }
            .show()
    }

    /** Aluno: liga para a sala do professor. */
    private fun dial() {
        if (!inCall || papel != Papel.ALUNO) return
        val r = rtc ?: return
        val s = sig ?: return
        if (!s.isOpen) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDial < 2500) return
        lastDial = nowMs
        val old = camCall; camCall = null; old?.end()
        val teacher = "mimisala-$sala"
        otherPeer = teacher
        val c = Call(r, s, teacher, "mc_" + rnd(), "cam",
            onRemoteVideo = { t -> remoteCam = t; layout(); status("Na aula com o professor"); sendMicState() },
            onEnded = { c ->
                calls.remove(c.connectionId)
                if (camCall === c) {
                    camCall = null; remoteCam = null; remoteScreen = null; layout()
                    if (inCall && !leaving) { status("A conexão caiu. Reconectando…"); ui.postDelayed({ dial() }, 3000) }
                }
            })
        c.addTracks(listOf(r.camTrack, r.micTrack), "cam")
        calls[c.connectionId] = c; camCall = c
        c.offer(JSONObject().put("kind", "cam").put("via", "app"))
        ui.postDelayed({
            if (camCall === c && remoteCam == null && inCall) { status("O professor ainda não abriu a sala…"); dial() }
        }, 10_000)
        if (sharing) ui.postDelayed({ startScreenCall() }, 1500)
    }

    private fun otherLeft() {
        camCall = null; remoteCam = null; remoteScreen = null; remoteAudio = null; studentMicOn = null
        screenCall?.end(); screenCall = null
        otherPeer = null
        layout()
        if (papel == Papel.PROFESSOR) status("O aluno saiu. Sala $sala continua aberta.")
    }

    private fun endOther() {
        for (c in calls.values.toList()) c.end()
        calls.clear(); otherLeft()
    }

    // ================= interface da aula =================

    private fun buildCallUi(r: Rtc) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bigR = SurfaceViewRenderer(this).apply {
            init(r.egl.eglBaseContext, null); setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
        root.addView(bigR, FrameLayout.LayoutParams(-1, -1))
        waitTv = TextView(this).apply { setTextColor(MUTED); textSize = 16f; gravity = Gravity.CENTER; setPadding(dpi(30f), 0, dpi(30f), 0) }
        root.addView(waitTv, FrameLayout.LayoutParams(-1, -1))

        val mini = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun mk() = SurfaceViewRenderer(this).apply {
            init(r.egl.eglBaseContext, null); setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); setEnableHardwareScaler(true)
        }
        smallR = mk().apply { visibility = View.GONE }
        meR = mk()
        val mw = dpi(118f); val mh = dpi(158f)
        mini.addView(smallR, LinearLayout.LayoutParams(mw, mh).apply { bottomMargin = dpi(8f) })
        mini.addView(meR, LinearLayout.LayoutParams(mw, mh))
        miniCol = mini
        root.addView(mini, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dpi(14f); bottomMargin = dpi(96f) })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f)); setBackgroundColor(0xB314161B.toInt())
        }
        statusTv = TextView(this).apply { setTextColor(FG); textSize = 14f }
        top.addView(statusTv, LinearLayout.LayoutParams(0, -2, 1f))
        recTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; background = pill(0xFFFF4A4F.toInt())
            setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(4f)); visibility = View.GONE
        }
        top.addView(recTv)
        topBar = top
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(10f)) }
        fun btn(key: String, icon: String, label: String, onClick: () -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f)); setOnClickListener { onClick() }
                contentDescription = label
            }
            val iv = ImageView(this).apply { setImageDrawable(IconDrawable(icon)); background = pill(RAISED); setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f)) }
            val tv = TextView(this).apply { text = label; setTextColor(FG); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dpi(4f), 0, 0) }
            box.addView(iv, LinearLayout.LayoutParams(dpi(50f), dpi(50f)))
            box.addView(tv)
            buttons[key] = iv to tv
            row.addView(box)
        }
        btn("mic", Icons.MIC, "Microfone") { toggleMic() }
        btn("cam", Icons.CAMERA, "Câmera") { toggleCam() }
        btn("trocar", Icons.TROCAR, "Trocar câmera") { nextCamera() }
        btn("micsel", Icons.MIC_TROCAR, "Trocar microfone") { chooseMic() }
        btn("tela", Icons.TELA, "Compartilhar tela") { toggleShare() }
        if (papel == Papel.PROFESSOR) {
            btn("mutar", Icons.ALUNO_MUDO, "Mutar aluno") { toggleStudentMute() }
            btn("gravar", Icons.GRAVAR, "Gravar aula") { toggleRecord() }
            btn("convidar", Icons.CONVIDAR, "Convidar") { invite() }
        }
        btn("sair", Icons.SAIR, "Sair") { confirmLeave() }
        (buttons["sair"]!!.first.background as GradientDrawable).setColor(0xFFFF4A4F.toInt())
        (buttons["gravar"]?.first?.drawable as? IconDrawable)?.color = 0xFFFF4A4F.toInt()
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xB314161B.toInt()); isHorizontalScrollBarEnabled = false; isFillViewport = true
            addView(row, FrameLayout.LayoutParams(-1, -2))
        }
        bottomBar = scroll
        root.addView(scroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        layout()
    }

    private fun setOn(key: String, on: Boolean, labelOn: String, labelOff: String, iconOn: String? = null, iconOff: String? = null) {
        val (iv, tv) = buttons[key] ?: return
        tv.text = if (on) labelOn else labelOff
        if (iconOn != null && iconOff != null) iv.setImageDrawable(IconDrawable(if (on) iconOn else iconOff))
        (iv.background as GradientDrawable).setColor(if (on) RAISED else 0xFF5A2A2E.toInt())
    }

    private fun setActive(key: String, active: Boolean, label: String) {
        val (iv, tv) = buttons[key] ?: return
        tv.text = label
        (iv.background as GradientDrawable).setColor(if (active) ACCENT else RAISED)
    }

    /** O que aparece grande: tela compartilhada do outro lado; senão, a câmera dele. */
    private fun layout() {
        if (!::bigR.isInitialized) return
        val big = remoteScreen ?: remoteCam
        val small = if (remoteScreen != null) remoteCam else null
        if (bigSrc !== big) {
            try { bigSrc?.removeSink(bigR) } catch (_: Exception) {}
            bigR.clearImage(); big?.addSink(bigR); bigSrc = big
        }
        if (smallSrc !== small) {
            try { smallSrc?.removeSink(smallR) } catch (_: Exception) {}
            smallR.clearImage(); small?.addSink(smallR); smallSrc = small
        }
        smallR.visibility = if (small != null && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        bigR.setScalingType(if (remoteScreen != null) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        waitTv.visibility = if (big == null) View.VISIBLE else View.GONE
        waitTv.text = if (papel == Papel.PROFESSOR) "Esperando o aluno entrar…\nCódigo da sala: $sala" else "Esperando o professor…"
    }

    private fun toggleMic() {
        if (teacherMuted) { toast("O professor desligou o seu microfone. Ele precisa liberar para você falar."); return }
        val t = rtc?.micTrack ?: return
        t.setEnabled(!t.enabled())
        paintMic(); sendMicState()
    }

    private fun toggleCam() {
        val r = rtc ?: return
        val on = !r.camTrack.enabled()
        r.camTrack.setEnabled(on)
        meR.visibility = if (on) View.VISIBLE else View.INVISIBLE
        setOn("cam", on, "Câmera", "Câmera desligada", Icons.CAMERA, Icons.CAM_OFF)
    }

    private fun nextCamera() {
        val r = rtc ?: return
        val list = r.cameras()
        if (list.size < 2) { toast("Só há uma câmera disponível"); return }
        val names = list.map { r.cameraLabel(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Escolher câmera")
            .setItems(names) { _, i ->
                r.switchTo(list[i]) { meR.setMirror(r.isFront(r.camName)); prefs.edit().putString("camera", list[i]).apply() }
            }.show()
    }

    // ================= compartilhar tela e gravar =================

    /** Garante a captura da tela (pede permissão ao Android uma vez por aula). */
    private fun withCapture(then: () -> Unit) {
        if (capture != null) { then(); return }
        pendingCapture = then
        askScreen.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun createCapture(data: android.content.Intent) {
        val r = rtc ?: return
        val b = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds
            else Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val k = 1920f / max(b.width(), b.height()).coerceAtLeast(1)
        val w = ((b.width() * k).roundToInt() / 2) * 2
        val h = ((b.height() * k).roundToInt() / 2) * 2
        val helper = SurfaceTextureHelper.create("tela", r.egl.eglBaseContext)
        val cap = ScreenCapture(data) { onCaptureStopped() }
        cap.initialize(helper, this, r.screenSource.capturerObserver)
        cap.startCapture(w, h, 30)
        r.screenSource.adaptOutputFormat(if (w > h) 1280 else 720, if (w > h) 720 else 1280, 15)
        capture = cap; captureHelper = helper
    }

    private fun onCaptureStopped() {
        // o Android encerrou a captura (por exemplo, pela notificação)
        if (sharing) stopShare()
        if (recorder != null) stopRecord()
        releaseCapture()
    }

    private fun releaseCaptureIfIdle() { if (!sharing && recorder == null) releaseCapture() }

    private fun releaseCapture() {
        val c = capture ?: return
        capture = null
        try { c.stopCapture() } catch (_: Exception) {}
        captureHelper?.dispose(); captureHelper = null
        stopService(Intent(this, AulaService::class.java))
    }

    private fun toggleShare() { if (sharing) stopShare() else withCapture { startShare() } }

    private fun startShare() {
        sharing = true
        capture?.sendToCall = true
        setActive("tela", true, "Parar de mostrar")
        startScreenCall()
        toast("Sua tela está sendo mostrada. Abra o app que quiser; a sala fica numa janelinha.")
        if (!isInPictureInPictureMode) enterPip()
    }

    private fun startScreenCall() {
        val r = rtc ?: return
        val s = sig ?: return
        val other = otherPeer ?: return
        if (!sharing || camCall == null) return
        screenCall?.end()
        val c = Call(r, s, other, "mc_" + rnd(), "screen", onRemoteVideo = {}, onEnded = { c -> calls.remove(c.connectionId); if (screenCall === c) screenCall = null })
        c.addTracks(listOf(r.screenTrack), "screen")
        calls[c.connectionId] = c; screenCall = c
        c.offer(JSONObject().put("kind", "screen"))
    }

    private fun stopShare() {
        sharing = false
        capture?.sendToCall = false
        screenCall?.end(); screenCall = null
        setActive("tela", false, "Compartilhar tela")
        releaseCaptureIfIdle()
    }

    private fun toggleRecord() { if (recorder != null) stopRecord() else withCapture { startRecord() } }

    private fun startRecord() {
        val r = rtc ?: return
        val cap = capture ?: return
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.apply { mkdirs() }
        val f = File(dir, "aula-" + SimpleDateFormat("yyyy-MM-dd-HH'h'mm", Locale.US).format(Date()) + ".mp4")
        val rec = try { ClassRecorder(r.egl.eglBaseContext, cap.width, cap.height, cap.projection, f).also { it.start() } }
            catch (e: Exception) { toast("Não consegui iniciar a gravação: ${e.message}"); releaseCaptureIfIdle(); return }
        recorder = rec
        cap.sinks.add(rec)
        r.micSink = { s -> rec.onMic(s) }
        recStart = SystemClock.elapsedRealtime()
        setActive("gravar", true, "Parar gravação")
        recTv.visibility = View.VISIBLE
        tickRec()
    }

    private fun tickRec() {
        if (recorder == null) { recTv.visibility = View.GONE; return }
        val s = (SystemClock.elapsedRealtime() - recStart) / 1000
        recTv.text = String.format("● GRAVANDO %02d:%02d", s / 60, s % 60)
        ui.postDelayed({ tickRec() }, 500)
    }

    private fun stopRecord() {
        val rec = recorder ?: return
        recorder = null
        capture?.sinks?.remove(rec)
        rtc?.micSink = null
        setActive("gravar", false, "Gravar aula")
        recTv.visibility = View.GONE
        toast("Salvando a gravação…")
        rec.stop { f ->
            releaseCaptureIfIdle()
            if (f == null) { toast("A gravação não pôde ser salva."); return@stop }
            thread {
                val uri = saveToGallery(f)
                ui.post { recordingSaved(uri) }
            }
        }
    }

    private fun saveToGallery(f: File): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SalaMimi")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            f.delete()
        }
        uri
    } catch (e: Exception) { null }

    private fun recordingSaved(uri: Uri?) {
        if (uri == null) { toast("A gravação não pôde ser salva."); return }
        AlertDialog.Builder(this)
            .setMessage("Aula gravada. O vídeo está na Galeria, em Filmes › SalaMimi. Assista antes de enviar ao aluno.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Compartilhar") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Enviar a aula"))
            }.show()
    }

    // ================= convite =================

    private fun invite() {
        val link = SITE + "?sala=" + sala
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dpi(20f), dpi(10f), dpi(20f), dpi(6f)) }
        box.addView(ImageView(this).apply { setImageBitmap(Qr.make(link, dpi(230f))) }, LinearLayout.LayoutParams(dpi(230f), dpi(230f)))
        box.addView(TextView(this).apply {
            text = "Código: $sala\n$link"; setTextColor(FG); textSize = 15f; gravity = Gravity.CENTER
            setTextIsSelectable(true); setPadding(0, dpi(12f), 0, dpi(4f))
        })
        box.addView(TextView(this).apply {
            text = "No Android o aluno pode entrar pelo app Sala Mimi Explica, com print e gravação bloqueados. Pelo navegador (iPhone, computador) ele entra, mas sem esse bloqueio."
            setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Convidar o aluno").setView(box)
            .setPositiveButton("Compartilhar link") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Entre na minha aula ao vivo: $link (código $sala)")
                }, "Enviar o convite"))
            }
            .setNeutralButton("Trocar código") { _, _ ->
                AlertDialog.Builder(this).setMessage("Criar um código novo? O link antigo deixa de funcionar e a sala reabre.")
                    .setPositiveButton("Trocar") { _, _ ->
                        prefs.edit().putString("sala", newCode()).apply()
                        leave(); start(Papel.PROFESSOR, roomCode())
                    }.setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Fechar", null).show()
    }

    // ================= janela flutuante =================

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(inCall)
        return b.build()
    }

    private fun enterPip() { try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {} }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (inCall && Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (!::topBar.isInitialized) return
        val v = if (isInPip) View.GONE else View.VISIBLE
        topBar.visibility = v; bottomBar.visibility = v; miniCol.visibility = v
        layout()
    }

    // ================= sair =================

    private fun confirmLeave() {
        AlertDialog.Builder(this).setMessage(if (recorder != null) "Sair da aula? A gravação será encerrada e salva." else "Sair da aula?")
            .setPositiveButton("Sair") { _, _ -> leave(); showHome() }
            .setNegativeButton("Continuar", null).show()
    }

    private fun leave() {
        leaving = true; inCall = false
        if (Build.VERSION.SDK_INT >= 31) setPictureInPictureParams(pipParams())
        if (recorder != null) stopRecord()
        if (sharing) stopShare()
        for (c in calls.values.toList()) c.end()
        calls.clear(); camCall = null; screenCall = null; otherPeer = null
        remoteCam = null; remoteScreen = null
        try { bigSrc?.removeSink(bigR) } catch (_: Exception) {}
        try { smallSrc?.removeSink(smallR) } catch (_: Exception) {}
        bigSrc = null; smallSrc = null
        sig?.close(); sig = null
        rtc?.let { r ->
            try { r.camTrack.removeSink(meR) } catch (_: Exception) {}
            r.stopCamera()
        }
        if (::bigR.isInitialized) { bigR.release(); smallR.release(); meR.release() }
        if (capture == null) { rtc?.release(); rtc = null }
        else ui.postDelayed({ releaseCapture(); rtc?.release(); rtc = null }, 3500)
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_NORMAL
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroy() {
        if (inCall) leave()
        super.onDestroy()
    }
}
