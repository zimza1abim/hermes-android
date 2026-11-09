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

    data class TurnEnded(
        override val sessionId: String,
        val outcome: TurnOutcome,
        val text: String?,
        val error: String?,
    ) : GatewayEvent

    data class BackgroundCompleted(override val sessionId: String, val text: String?) : GatewayEvent

    /** The chat's title, from `session.title` or the title in `session.info`; never blank. */
    data class SessionTitled(override val sessionId: String, val title: String) : GatewayEvent

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
    val WATCHED_MARKERS: List<String> =
        listOf("message.complete", "approval", "clarify", "background.complete", "session.title", "session.info")

    fun parseInbound(raw: String): GatewayEvent? {
        val frame = parseObject(raw) ?: return null
        val params = frame.optJSONObject("params") ?: return null
        return when (string(frame, "method")) {
            "event" -> parseEvent(params)
            "approval" -> RpcId.of(frame.opt("id"))?.let { parseApproval(it, params) }
            "clarify" -> RpcId.of(frame.opt("id"))?.let { parseClarify(it, params) }
            else -> null
        }
    }

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
                )
            }
            "background.complete" -> GatewayEvent.BackgroundCompleted(sessionId, string(payload, "text"))
            "session.title", "session.info" -> string(payload, "title")?.let { GatewayEvent.SessionTitled(sessionId, it.trim()) }
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
