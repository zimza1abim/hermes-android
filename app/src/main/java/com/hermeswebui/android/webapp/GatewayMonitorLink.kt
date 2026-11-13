package com.hermeswebui.android.webapp

import android.os.Handler
import android.webkit.CookieManager
import android.content.Context
import java.security.MessageDigest
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
    context: Context,
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
    private val preferences = context.getSharedPreferences("hermes_monitor_cursors", Context.MODE_PRIVATE)
    private var cursorKey: String? = null
    private val saveCursors = Runnable { persistCursors() }
    private var sessionAuthority = false
    private var pings = 0
    private val watermarks = linkedMapOf<String, Long>()
    private var replayCursors = emptyMap<String, Pair<Long, String>>()
    private val sessionEpochs = mutableMapOf<String, String>()
    private var replayEpoch: String? = null
    private var nextReplayId = -2
    private val replayRequests = mutableMapOf<Int, Pair<String, Boolean>>()
    private val pollActive = object : Runnable {
        override fun run() {
            socket?.send("""{"jsonrpc":"2.0","id":-1,"method":"session.active_list","params":{}}""")
            handler.postDelayed(this, ACTIVE_POLL_MS)
        }
    }
    private val deadlines = mutableMapOf<String, Runnable>()
    private val answerDeadlines = mutableMapOf<RpcId, Runnable>()
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
        val key = serverUrl?.let(::storageKey)
        if (key != cursorKey) {
            persistCursors()
            cursorKey = key
            watermarks.clear()
            sessionEpochs.clear()
            replayEpoch = null
            loadCursors()
        }
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
                    keepCookies(response)
                    if (response.code == 401 || response.code == 403) {
                        response.close()
                        return post(WebappInput.TicketMinted(gen, null, signedOut = true))
                    }
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
        replayCursors = watermarks.mapNotNull { (sid, seq) ->
            (sessionEpochs[sid] ?: replayEpoch)?.let { sid to (seq to it) }
        }.toMap()
        val request = Request.Builder()
            .url(target.socketUrl)
            .header("Origin", target.origin)
            .header("Sec-WebSocket-Protocol", "$PROTOCOL, $TICKET_PROTOCOL_PREFIX$ticket")
            .apply { cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                keepCookies(response)
                handler.post {
                    if (gen != this@GatewayMonitorLink.gen) return@post
                    handler.removeCallbacks(ping)
                    handler.postDelayed(ping, PING_INTERVAL_S * 1_000)
                    armDeadline("ready", gen)
                    deliver(WebappInput.MonitorOpened(gen))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val response = runCatching { JSONObject(text) }.getOrNull()
                if (response?.opt("id") == -1 && response.optJSONObject("result") != null) {
                    val events = GatewayProtocol.parseActiveSessions(response.optJSONObject("result") ?: return)
                    handler.post {
                        if (gen == this@GatewayMonitorLink.gen) events.forEach {
                            deliver(WebappInput.MonitorReceived(gen, MonitorFrame.Inbound(it)))
                        }
                    }
                    return
                }
                val replayId = response?.opt("id") as? Int
                if (replayId != null && replayId < -1) {
                    handler.post {
                        if (gen != this@GatewayMonitorLink.gen) return@post
                        val (expected, baseline) = replayRequests.remove(replayId) ?: return@post
                        val result = response.optJSONObject("result") ?: return@post
                        val epoch = result.optString("replay_epoch").takeIf { it.isNotBlank() }
                            ?: result.optString("epoch").takeIf { it.isNotBlank() } ?: return@post
                        val events = result.optJSONArray("events") ?: return@post
                        if (baseline || result.optBoolean("snapshot_required") || result.optBoolean("truncated")) {
                            val latest = (result.opt("latest_seq") as? Number)?.toLong() ?: 0L
                            if (sessionEpochs[expected] != epoch) watermarks.remove(expected)
                            sessionEpochs[expected] = epoch
                            if (expected !in watermarks || latest > (watermarks[expected] ?: 0L)) watermarks[expected] = latest
                            scheduleCursorSave()
                            return@post
                        }
                        if (replayCursors[expected]?.second != epoch) return@post
                        for (i in 0 until events.length()) {
                            val params = events.optJSONObject(i) ?: continue
                            if (params.optString("session_id") != expected) continue
                            val raw = JSONObject().put("method", "event").put("params", params).toString()
                            GatewayProtocol.parseInbound(raw)?.let {
                                deliver(WebappInput.MonitorReceived(gen, MonitorFrame.Inbound(it)))
                            }
                            recordWatermark(params)
                        }
                    }
                    return
                }
                val frame = GatewayProtocol.parseMonitorFrame(text)
                if (frame == null) {
                    val params = response?.optJSONObject("params")
                    if (response?.optString("method") == "event" && params != null) {
                        handler.post { if (gen == this@GatewayMonitorLink.gen) recordWatermark(params) }
                    }
                    return
                }
                handler.post {
                    if (gen != this@GatewayMonitorLink.gen) return@post
                    val params = response?.optJSONObject("params")
                    if (frame == MonitorFrame.Ready) {
                        sessionAuthority = params?.optJSONObject("payload")?.optBoolean("session_authority") == true
                        val epoch = params?.optJSONObject("payload")?.optString("replay_epoch")?.takeIf { it.isNotBlank() }
                        if (epoch != null && replayEpoch != epoch) {
                            val oldEpoch = replayEpoch
                            val stale = sessionEpochs.filterValues { it == oldEpoch }.keys.toList()
                            stale.forEach { watermarks.remove(it); sessionEpochs.remove(it) }
                            replayCursors = replayCursors.filterValues { it.second != oldEpoch }
                            replayEpoch = epoch
                            scheduleCursorSave()
                        }
                    } else if (params != null && response.optString("method") == "event") recordWatermark(params)
                    when (frame) {
                        MonitorFrame.Ready -> {
                            clearDeadline("ready")
                            handler.removeCallbacks(pollActive)
                            handler.post(pollActive)
                        }
                        is MonitorFrame.Resumed -> clearDeadline("resume:${frame.requestId}")
                        is MonitorFrame.AnswerAck -> answerDeadlines.remove(frame.rpcId)?.let(handler::removeCallbacks)
                        else -> Unit
                    }
                    deliver(WebappInput.MonitorReceived(gen, frame))
                    if (frame is MonitorFrame.Resumed && frame.snapshot != null) requestReplay(frame.snapshot.runtimeId)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(WebappInput.MonitorClosed(gen))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(WebappInput.MonitorClosed(gen))
        })
    }

    fun sendAnswer(gen: Int, id: RpcId, frame: String): Boolean {
        if (gen != this.gen) return false
        val body = runCatching { JSONObject(frame).put("hermes_android_ack", true).toString() }.getOrNull() ?: return false
        if (socket?.send(body) != true) return false
        answerDeadlines.remove(id)?.let(handler::removeCallbacks)
        val deadline = Runnable {
            answerDeadlines.remove(id)
            deliver(WebappInput.SendResult(id, false, uncertain = true))
        }
        answerDeadlines[id] = deadline
        handler.postDelayed(deadline, RESPONSE_TIMEOUT_MS)
        return true
    }

    private fun recordWatermark(params: JSONObject) {
        val sid = params.optString("session_id").takeIf { it.isNotBlank() } ?: return
        val seq = (params.opt("seq") as? Number)?.toLong() ?: return
        val epoch = params.optString("replay_epoch").takeIf { it.isNotBlank() } ?: replayEpoch ?: return
        if (sessionEpochs[sid] != epoch) {
            sessionEpochs[sid] = epoch
            watermarks.remove(sid)
        }
        if (seq <= (watermarks[sid] ?: 0L)) return
        watermarks.remove(sid)
        watermarks[sid] = seq
        while (watermarks.size > 256) {
            val oldest = watermarks.keys.first()
            watermarks.remove(oldest)
            sessionEpochs.remove(oldest)
        }
        scheduleCursorSave()
    }

    /** A visible app has shown recent events itself; do not replay those on the next background run. */
    fun clearReplayCursors(serverUrl: String?) {
        handler.removeCallbacks(saveCursors)
        watermarks.clear()
        sessionEpochs.clear()
        replayCursors = emptyMap()
        replayRequests.clear()
        replayEpoch = null
        val editor = preferences.edit()
        cursorKey?.let(editor::remove)
        serverUrl?.let { editor.remove(storageKey(it)) }
        editor.apply()
    }

    private fun scheduleCursorSave() {
        handler.removeCallbacks(saveCursors)
        handler.postDelayed(saveCursors, 1_000L)
    }

    private fun persistCursors() {
        val key = cursorKey ?: return
        val marks = JSONObject()
        watermarks.forEach { (sid, seq) -> marks.put(sid, JSONObject().put("seq", seq).put("epoch", sessionEpochs[sid])) }
        preferences.edit().putString(key, JSONObject().put("epoch", replayEpoch).put("marks", marks).toString()).apply()
    }

    private fun loadCursors() {
        val data = cursorKey?.let { preferences.getString(it, null) } ?: return
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return
        replayEpoch = root.optString("epoch").takeIf { it.isNotBlank() && it != "null" }
        val marks = root.optJSONObject("marks") ?: return
        val keys = marks.keys()
        while (keys.hasNext() && watermarks.size < 256) {
            val sid = keys.next()
            val entry = marks.optJSONObject(sid) ?: continue
            val seq = (entry.opt("seq") as? Number)?.toLong() ?: continue
            val epoch = entry.optString("epoch").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (sid.length <= 256 && seq >= 0) {
                watermarks[sid] = seq
                sessionEpochs[sid] = epoch
            }
        }
    }

    private fun storageKey(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun requestReplay(sessionId: String) {
        val baseline = sessionId !in replayCursors
        val (cursor, epoch) = replayCursors[sessionId] ?: (0L to (replayEpoch ?: return))
        val id = nextReplayId--
        val params = JSONObject().put("session_id", sessionId).put("last_seen", cursor).put("replay_epoch", epoch)
        val request = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "session.events.since").put("params", params)
        if (socket?.send(request.toString()) == true) replayRequests[id] = sessionId to baseline
    }

    /** False when attempt [gen] has no open socket or the frame could not be queued. */
    fun send(gen: Int, frame: String): Boolean {
        if (gen != this.gen) return false
        val adapted = if (sessionAuthority) GatewayProtocol.adaptForSessionAuthority(frame) else frame
        val request = runCatching { JSONObject(adapted) }.getOrNull()
        if (request?.optString("method") == "session.resume") request.put("hermes_android_quiet_watch", true)
        if (socket?.send(request?.toString() ?: adapted) != true) return false
        if (request?.optString("method") == "session.resume") {
            val id = request.opt("id") as? Int
            if (id != null) armDeadline("resume:$id", gen)
        }
        return true
    }

    private fun armDeadline(key: String, gen: Int) {
        clearDeadline(key)
        val deadline = Runnable {
            deadlines.remove(key)
            if (gen == this.gen) {
                socket?.cancel()
                deliver(WebappInput.MonitorClosed(gen))
            }
        }
        deadlines[key] = deadline
        handler.postDelayed(deadline, RESPONSE_TIMEOUT_MS)
    }

    private fun clearDeadline(key: String) {
        deadlines.remove(key)?.let(handler::removeCallbacks)
    }

    fun retryAfter(gen: Int, delayMs: Long) {
        if (gen != this.gen) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }

    fun stop() {
        handler.removeCallbacks(saveCursors)
        persistCursors()
        close(gen)
    }

    /** Ends attempt [gen]: cancels its mint, closes its socket and stops its timers. Idempotent. */
    fun close(gen: Int) {
        if (gen != this.gen) return
        // Fence callbacks already queued by this socket before clearing its state.
        this.gen = -1
        mintCall?.cancel()
        mintCall = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        handler.removeCallbacks(retry)
        handler.removeCallbacks(ping)
        handler.removeCallbacks(pollActive)
        deadlines.values.forEach(handler::removeCallbacks)
        deadlines.clear()
        replayRequests.clear()
        val failed = answerDeadlines.keys.toList()
        answerDeadlines.values.forEach(handler::removeCallbacks)
        answerDeadlines.clear()
        failed.forEach { deliver(WebappInput.SendResult(it, false, uncertain = true)) }
    }

    /** Runs [input] on the handler thread; by then a newer attempt may have made it stale, which the reducer drops. */
    // Nous sign-in rotates its single-use refresh token on any request that refreshes, so a
    // refreshed cookie dropped here makes the page replay a spent token and lose the session.
    private fun keepCookies(response: Response) {
        val fresh = response.headers("Set-Cookie")
        if (fresh.isEmpty()) return
        val url = response.request.url.toString()
        handler.post {
            runCatching {
                val jar = CookieManager.getInstance()
                fresh.forEach { jar.setCookie(url, it) }
                jar.flush()
                endpoint?.let { cookie = jar.getCookie(it.origin) }
            }
        }
    }

    private fun post(input: WebappInput) {
        handler.post { deliver(input) }
    }

    companion object {
        private const val PROTOCOL = "hermes-gateway-v1"
        private const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."
        private const val NORMAL_CLOSURE = 1000
        private const val CONNECT_TIMEOUT_S = 10L
        private const val MINT_TIMEOUT_S = 15L
        private const val ACTIVE_POLL_MS = 10_000L
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val PING_INTERVAL_S = 15L
    }
}
