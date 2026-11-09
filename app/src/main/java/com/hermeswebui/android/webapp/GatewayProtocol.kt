package com.hermeswebui.android.webapp

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A JSON-RPC id kept as its canonical JSON token (`7` or `"abc"`), so an answer echoes exactly the
 * id the gateway sent. Only integer and string ids are accepted.
 */
@JvmInline
value class RpcId private constructor(val json: String) {
    companion object {
        internal fun of(raw: Any?): RpcId? = when (raw) {
            is String -> RpcId(JSONObject.quote(raw))
            is Int, is Long -> RpcId(raw.toString())
            else -> null
        }

        /** Parses a token produced by [json] back into an id, or null when it is not one. */
        fun fromToken(token: String): RpcId? =
            of(runCatching { JSONTokener(token).nextValue() }.getOrNull())?.takeIf { it.json == token }
    }
}

enum class TurnOutcome { COMPLETE, ERROR, INTERRUPTED }

enum class ApprovalChoice(val wire: String) { ONCE("once"), SESSION("session"), ALWAYS("always"), DENY("deny") }

enum class Decision { ALLOW, DENY }

/** The gateway events the app reacts to. Every other frame parses to null. */
sealed interface GatewayEvent {
    val sessionId: String

    /** [seq] is the server's per-session event number, the same on every socket that sees the frame. */
    data class TurnEnded(
        override val sessionId: String,
        val outcome: TurnOutcome,
        val text: String?,
        val error: String?,
        val seq: Long? = null,
    ) : GatewayEvent

    data class BackgroundCompleted(override val sessionId: String, val text: String?, val seq: Long? = null) : GatewayEvent

    /** The chat's title from `session.title`; never blank. */
    data class SessionTitled(override val sessionId: String, val title: String) : GatewayEvent

    /** A `session.info` snapshot. Each field is null when the payload leaves it out. */
    data class SessionInfo(
        override val sessionId: String,
        val storedId: String?,
        val running: Boolean?,
        val title: String?,
    ) : GatewayEvent

    /** The server withdrew one open request (`request.cancel`): timed out, interrupted, resolved elsewhere. */
    data class RequestCancelled(override val sessionId: String, val rpcId: RpcId) : GatewayEvent

    /** Matches the `request_id` of each approval it withdraws; empty withdraws every approval in the session. */
    data class ApprovalCancelled(override val sessionId: String, val requestIds: Set<String>) : GatewayEvent

    /** A server-to-client JSON-RPC request; its answer must echo [rpcId]. */
    sealed interface PromptRequest : GatewayEvent {
        val rpcId: RpcId
    }

    data class ApprovalRequested(
        override val sessionId: String,
        override val rpcId: RpcId,
        val requestId: String?,
        val command: String?,
        val description: String?,
        val toolName: String?,
        val choices: List<ApprovalChoice>,
    ) : PromptRequest

    data class ClarifyRequested(
        override val sessionId: String,
        override val rpcId: RpcId,
        val questions: List<ClarifyQuestion>,
    ) : PromptRequest
}

/** One question of a clarify request. Its answer is keyed by [qid]; without one it cannot be answered. */
data class ClarifyQuestion(
    val qid: String?,
    val question: String?,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

/** The attach answer to a `session.resume` the monitor sent: the runtime id events will carry. */
data class ResumeSnapshot(
    val runtimeId: String,
    val storedId: String,
    val running: Boolean,
    val title: String?,
    /** The session's unanswered server requests that the app models. */
    val openRequests: List<GatewayEvent.PromptRequest>,
)

/** One frame the native monitor socket received, reduced to what the app acts on. */
sealed interface MonitorFrame {
    data object Ready : MonitorFrame
    data object QuietWatchFallback : MonitorFrame
    data class Inbound(val event: GatewayEvent) : MonitorFrame
    /** The answer to `client.capabilities`; [declinesNotShown] says a 4404 decline does not settle a request. */
    data class Capabilities(val requestId: Int, val declinesNotShown: Boolean) : MonitorFrame
    /** The answer to one of the monitor's `session.resume` calls; null when the server refused it. */
    data class Resumed(val requestId: Int, val snapshot: ResumeSnapshot?) : MonitorFrame
    /** A window-owned server request (preview, terminal, window read, tour) that no app window shows. */
    data class AnswerAck(val rpcId: RpcId, val accepted: Boolean) : MonitorFrame
    data class WindowRequest(val rpcId: RpcId) : MonitorFrame
}

/**
 * The only code that knows the gateway's JSON-RPC shapes (hermes-agent `tui_gateway/contracts`).
 * Wire types stay in this file; callers see [GatewayEvent] and [RpcId].
 */
object GatewayProtocol {
    /**
     * Substrings the injected tap uses as a cheap superset filter before forwarding an inbound
     * frame. The script is built from this list, and a test asserts every parseable fixture
     * contains one, so the parser and the filter cannot drift apart.
     */
    val WATCHED_MARKERS: List<String> = listOf(
        "message.complete", "approval", "clarify", "background.complete", "session.title", "session.info", "request.cancel", "hermes.android.answer_ack",
    )

    /** Server requests only the window showing the session answers; every other client declines them. */
    private val WINDOW_OWNED = setOf("preview.read", "preview.act", "terminal.read", "window.read", "tour")

    /** Error code of a "no window here shows this session" decline (hermes-agent `server_requests.NOT_SHOWN_CODE`). */
    private const val NOT_SHOWN_CODE = 4404

    fun parseInbound(raw: String): GatewayEvent? = parseObject(raw)?.let(::parseFrame)

    private fun parseFrame(frame: JSONObject): GatewayEvent? {
        val params = frame.optJSONObject("params") ?: return null
        return when (string(frame, "method")) {
            "event" -> parseEvent(params)
            "approval" -> RpcId.of(frame.opt("id"))?.let { parseApproval(it, params) }
            "clarify" -> RpcId.of(frame.opt("id"))?.let { parseClarify(it, params) }
            else -> null
        }
    }

    /** Parses a frame from the monitor socket. Responses are matched by the integer ids the monitor sends. */
    fun parseMonitorFrame(raw: String): MonitorFrame? {
        val frame = parseObject(raw) ?: return null
        val method = string(frame, "method")
        if (method == null) {
            val id = frame.opt("id") as? Int ?: return null
            val result = frame.optJSONObject("result")
            return when {
                result?.has("server_requests") == true ->
                    MonitorFrame.Capabilities(id, result.opt("declines_not_shown") == true)
                frame.has("error") || result != null -> MonitorFrame.Resumed(id, result?.let(::parseResume))
                else -> null
            }
        }
        if (method == "hermes.android.quiet_watch_unavailable") return MonitorFrame.QuietWatchFallback
        if (method == "hermes.android.answer_ack") {
            val params = frame.optJSONObject("params") ?: return null
            val id = RpcId.of(params.opt("id")) ?: return null
            val accepted = params.opt("accepted") as? Boolean ?: return null
            return MonitorFrame.AnswerAck(id, accepted)
        }
        if (method in WINDOW_OWNED) return RpcId.of(frame.opt("id"))?.let(MonitorFrame::WindowRequest)
        if (method == "event" && string(frame.optJSONObject("params") ?: JSONObject(), "type") == "gateway.ready") {
            return MonitorFrame.Ready
        }
        return parseFrame(frame)?.let(MonitorFrame::Inbound)
    }

    private fun parseResume(result: JSONObject): ResumeSnapshot? {
        val runtimeId = string(result, "session_id") ?: return null
        val info = result.optJSONObject("info") ?: JSONObject()
        val storedId = string(result, "session_key") ?: string(result, "stored_session_id")
            ?: string(info, "stored_session_id") ?: return null
        val open = result.optJSONArray("open_requests")
        val requests = if (open == null) emptyList() else (0 until open.length()).mapNotNull { index ->
            open.optJSONObject(index)?.let(::parseFrame) as? GatewayEvent.PromptRequest
        }
        return ResumeSnapshot(runtimeId, storedId, result.opt("running") == true, string(info, "title")?.trim(), requests)
    }

    /** Converts a live-list snapshot into facts for the monitor; malformed rows are ignored. */
    fun parseActiveSessions(result: JSONObject): List<GatewayEvent.SessionInfo> {
        val rows = result.optJSONArray("sessions") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = string(row, "id") ?: return@mapNotNull null
            val stored = string(row, "session_key") ?: return@mapNotNull null
            val status = string(row, "status") ?: return@mapNotNull null
            GatewayEvent.SessionInfo(id, stored, status != "idle", string(row, "title"))
        }
    }

    fun encodeCapabilities(requestId: Int): String =
        """{"jsonrpc":"2.0","id":$requestId,"method":"client.capabilities","params":{"server_requests":true}}"""

    /** Attaches the monitor to a stored session as a viewer without loading its transcript. */
    fun encodeResume(requestId: Int, storedId: String): String {
        val params = JSONObject().put("session_id", storedId).put("lazy", true).put("omit_messages", true).put("inline_images", false)
        return """{"jsonrpc":"2.0","id":$requestId,"method":"session.resume","params":$params}"""
    }

    /** Canonical session authority rejects legacy-only resume parameters. */
    fun adaptForSessionAuthority(frame: String): String {
        val request = parseObject(frame) ?: return frame
        if (string(request, "method") != "session.resume") return frame
        request.optJSONObject("params")?.apply { remove("lazy"); remove("inline_images") }
        return request.toString()
    }

    fun encodeNotShown(rpcId: RpcId): String =
        """{"jsonrpc":"2.0","id":${rpcId.json},"error":{"code":$NOT_SHOWN_CODE,"message":"No app window shows this session"}}"""

    fun encodePing(id: String): String =
        """{"jsonrpc":"2.0","id":${JSONObject.quote(id)},"method":"gateway.ping","params":{}}"""

    /** The id of a page-sent JSON-RPC response (`result` or `error`, no `method`), else null. */
    fun parseResponseId(raw: String): RpcId? {
        val frame = parseObject(raw) ?: return null
        if (frame.has("method") || !(frame.has("result") || frame.has("error"))) return null
        return RpcId.of(frame.opt("id"))
    }

    fun encodeApprovalAnswer(rpcId: RpcId, choice: ApprovalChoice): String =
        """{"jsonrpc":"2.0","id":${rpcId.json},"result":${JSONObject().put("choice", choice.wire)}}"""

    /** A clarify result answering one question; the gateway reads `{answers: {qid: text}}`. */
    fun encodeClarifyAnswer(rpcId: RpcId, qid: String, text: String): String =
        """{"jsonrpc":"2.0","id":${rpcId.json},"result":${JSONObject().put("answers", JSONObject().put(qid, text))}}"""

    /** Allow picks `once` when offered, else the first non-deny choice. Null when nothing fits. */
    fun choiceFor(decision: Decision, offered: List<ApprovalChoice>): ApprovalChoice? = when (decision) {
        Decision.DENY -> ApprovalChoice.DENY.takeIf { it in offered }
        Decision.ALLOW -> ApprovalChoice.ONCE.takeIf { it in offered }
            ?: offered.firstOrNull { it != ApprovalChoice.DENY }
    }

    private fun parseEvent(params: JSONObject): GatewayEvent? {
        val payload = params.optJSONObject("payload") ?: JSONObject()
        val sessionId = string(params, "session_id") ?: string(payload, "session_id") ?: return null
        val seq = (params.opt("seq") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
        return when (string(params, "type")) {
            "message.complete" -> {
                val outcome = when (string(payload, "status") ?: "complete") {
                    "complete" -> TurnOutcome.COMPLETE
                    "error" -> TurnOutcome.ERROR
                    "interrupted" -> TurnOutcome.INTERRUPTED
                    else -> return null
                }
                GatewayEvent.TurnEnded(
                    sessionId,
                    outcome,
                    text = string(payload, "text"),
                    error = string(payload, "error") ?: string(payload, "failure_reason"),
                    seq = seq,
                )
            }
            "background.complete" -> GatewayEvent.BackgroundCompleted(sessionId, string(payload, "text"), seq)
            "session.title" -> string(payload, "title")?.let { GatewayEvent.SessionTitled(sessionId, it.trim()) }
            "session.info" -> GatewayEvent.SessionInfo(
                sessionId,
                storedId = string(payload, "stored_session_id"),
                running = payload.opt("running") as? Boolean,
                title = string(payload, "title")?.trim(),
            ).takeIf { it.storedId != null || it.running != null || it.title != null }
            "request.cancel" -> RpcId.of(payload.opt("id"))?.let { GatewayEvent.RequestCancelled(sessionId, it) }
            "approval.cancelled" -> GatewayEvent.ApprovalCancelled(
                sessionId,
                strings(payload.optJSONArray("request_ids")).toSet(),
            )
            else -> null
        }
    }

    private fun parseApproval(rpcId: RpcId, params: JSONObject): GatewayEvent? {
        val sessionId = string(params, "session_id") ?: return null
        val offered = strings(params.optJSONArray("choices"))
            .mapNotNull { wire -> ApprovalChoice.entries.firstOrNull { it.wire == wire } }
        return GatewayEvent.ApprovalRequested(
            sessionId,
            rpcId,
            requestId = string(params, "request_id"),
            command = string(params, "command"),
            description = string(params, "description"),
            toolName = string(params, "tool_name"),
            choices = offered.ifEmpty { listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY) },
        )
    }

    private fun parseClarify(rpcId: RpcId, params: JSONObject): GatewayEvent? {
        val sessionId = string(params, "session_id") ?: return null
        val raw = params.optJSONArray("questions")
        val questions = if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { index ->
            raw.optJSONObject(index)?.let { question ->
                ClarifyQuestion(
                    qid = string(question, "qid"),
                    question = string(question, "question"),
                    choices = strings(question.optJSONArray("choices")).filter { it.isNotBlank() },
                    multiSelect = question.opt("multi_select") == true,
                )
            }
        }
        return GatewayEvent.ClarifyRequested(sessionId, rpcId, questions)
    }

    private fun parseObject(raw: String): JSONObject? =
        runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()

    /** A non-blank string field; JSON null, numbers and blanks read as absent. */
    private fun string(obj: JSONObject, name: String): String? =
        (obj.opt(name) as? String)?.takeIf { it.isNotBlank() }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }
}
