package br.com.mimiexplica.sala

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Ponto de encontro" (servidor gratuito do PeerJS). Fala o mesmo protocolo da página do aluno,
 * por isso o app e o navegador se encontram na mesma sala.
 */
class Signaling(private val myId: String, private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onIdTaken()
        fun onMessage(type: String, src: String, payload: JSONObject?)
        fun onDisconnected()
    }

    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var closed = false
    var isOpen = false; private set

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            ws?.send("{\"type\":\"HEARTBEAT\"}")
            main.postDelayed(this, 5000)
        }
    }

    fun connect() {
        if (closed) return
        val token = java.lang.Long.toString((Math.random() * 1e15).toLong(), 36)
        val url = "wss://0.peerjs.com:443/peerjs?key=peerjs&id=$myId&token=$token&version=1.5.4"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { handle(o) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { lost(webSocket) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { lost(webSocket) } }
        })
        main.removeCallbacks(heartbeat)
        main.postDelayed(heartbeat, 5000)
    }

    private fun lost(w: WebSocket) {
        if (w !== ws) return
        isOpen = false
        listener.onDisconnected()
        if (!closed) main.postDelayed({ connect() }, 3000)
    }

    private fun handle(o: JSONObject) {
        when (val type = o.optString("type")) {
            "OPEN" -> { isOpen = true; listener.onOpen() }
            "ID-TAKEN" -> {
                isOpen = false
                ws?.close(1000, null); ws = null
                listener.onIdTaken()
                if (!closed) main.postDelayed({ connect() }, 4000)
            }
            "HEARTBEAT" -> {}
            else -> listener.onMessage(type, o.optString("src"), o.optJSONObject("payload"))
        }
    }

    fun send(type: String, dst: String, payload: JSONObject) {
        val m = JSONObject().put("type", type).put("dst", dst).put("payload", payload)
        ws?.send(m.toString())
    }

    fun close() {
        closed = true
        main.removeCallbacks(heartbeat)
        ws?.close(1000, null); ws = null
    }
}
