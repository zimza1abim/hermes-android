package com.hermeswebui.android.webapp

import android.os.Handler
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONTokener

/** The configured server's gateway endpoints, under its base path. */
data class MonitorEndpoint(val origin: String, val ticketUrl: String, val socketUrl: String) {
    companion object {
        fun from(serverUrl: String): MonitorEndpoint? {
            val uri = runCatching { URI(serverUrl.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host ?: return null
            if (scheme != "http" && scheme != "https") return null
            val authority = if (uri.port == -1) host else "$host:${uri.port}"
            val base = uri.rawPath.orEmpty().trimEnd('/')
            val socketScheme = if (scheme == "https") "wss" else "ws"
            return MonitorEndpoint(
                origin = "$scheme://$authority",
                ticketUrl = "$scheme://$authority$base/api/webapp/socket-ticket?socket=gateway",
                socketUrl = "$socketScheme://$authority$base/api/ws?native_dial=browser",
            )
        }
    }
}

/**
 * The monitor's sockets and timers. It decides nothing: it runs the reducer's monitor effects and
 * reports each callback, tagged with its attempt, through [deliver] on [handler]'s thread.
 */
internal class GatewayMonitorLink(
    private val handler: Handler,
    private val deliver: (WebappInput) -> Unit,
) {
    private val base = OkHttpClient.Builder().connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val http = base.newBuilder().callTimeout(MINT_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val sockets = base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS).build()

    private var gen = -1
    private var endpoint: MonitorEndpoint? = null
    private var cookie: String? = null
    private var mintCall: Call? = null
    private var socket: WebSocket? = null
    private var pings = 0
    private val retry = Runnable { deliver(WebappInput.RetryDue(gen)) }
    private val ping = object : Runnable {
        override fun run() {
            socket?.send(GatewayProtocol.encodePing("android-ping-${++pings}"))
            handler.postDelayed(this, PING_INTERVAL_S * 1_000)
        }
    }

    /** Mints a one-use ticket with the page's cookie; [cookie] is read now, on the main thread. */
    fun mint(gen: Int, serverUrl: String?, cookie: String?) {
        close(this.gen)
        this.gen = gen
        val target = serverUrl?.let(MonitorEndpoint::from)
        endpoint = target
        this.cookie = cookie
        if (target == null) {
            post(WebappInput.TicketMinted(gen, null))
            return
        }
        val request = Request.Builder()
            .url(target.ticketUrl)
            .post(ByteArray(0).toRequestBody())
            .header("Origin", target.origin)
            .header("Accept", "application/json")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        mintCall = http.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = post(WebappInput.TicketMinted(gen, null))

                override fun onResponse(call: Call, response: Response) {
                    val ticket = response.use { reply ->
                        if (!reply.isSuccessful) null else runCatching {
                            (JSONTokener(reply.body?.string().orEmpty()).nextValue() as? JSONObject)
                                ?.optString("ticket")?.trim()?.takeIf { it.isNotEmpty() }
                        }.getOrNull()
                    }
                    post(WebappInput.TicketMinted(gen, ticket))
                }
            })
        }
    }

    fun dial(gen: Int, ticket: String) {
        val target = endpoint ?: return post(WebappInput.MonitorClosed(gen))
        if (gen != this.gen) return
        val request = Request.Builder()
            .url(target.socketUrl)
            .header("Origin", target.origin)
            .header("Sec-WebSocket-Protocol", "$PROTOCOL, $TICKET_PROTOCOL_PREFIX$ticket")
            .apply { cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (gen != this@GatewayMonitorLink.gen) return@post
                    handler.removeCallbacks(ping)
                    handler.postDelayed(ping, PING_INTERVAL_S * 1_000)
                    deliver(WebappInput.MonitorOpened(gen))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                GatewayProtocol.parseMonitorFrame(text)?.let { post(WebappInput.MonitorReceived(gen, it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(WebappInput.MonitorClosed(gen))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(WebappInput.MonitorClosed(gen))
        })
    }

    /** False when attempt [gen] has no open socket or the frame could not be queued. */
    fun send(gen: Int, frame: String): Boolean = gen == this.gen && socket?.send(frame) == true

    fun retryAfter(gen: Int, delayMs: Long) {
        if (gen != this.gen) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }

    fun stop() = close(gen)

    /** Ends attempt [gen]: cancels its mint, closes its socket and stops its timers. Idempotent. */
    fun close(gen: Int) {
        if (gen != this.gen) return
        mintCall?.cancel()
        mintCall = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        handler.removeCallbacks(retry)
        handler.removeCallbacks(ping)
    }

    /** Runs [input] on the handler thread; by then a newer attempt may have made it stale, which the reducer drops. */
    private fun post(input: WebappInput) {
        handler.post { deliver(input) }
    }

    companion object {
        private const val PROTOCOL = "hermes-gateway-v1"
        private const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."
        private const val NORMAL_CLOSURE = 1000
        private const val CONNECT_TIMEOUT_S = 10L
        private const val MINT_TIMEOUT_S = 15L
        private const val PING_INTERVAL_S = 15L
    }
}
