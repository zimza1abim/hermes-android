package com.hermeswebui.android.webapp

import android.app.NotificationManager
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The one list of user-facing Webapp alert kinds. Notification channels, settings rows, the
 * disabled-kind storage keys and notification id ranges are all derived from it.
 */
enum class GatewayAlertKind(
    /** Stable storage value; never the enum name, so a rename cannot drop a saved choice. */
    val storageKey: String,
    val channelId: String,
    val label: String,
    val summary: String,
    val importance: Int,
    /** Prompts are shown while still open; other kinds are one-shot. */
    val isPrompt: Boolean,
) {
    TURN_COMPLETE(
        "turn_complete", "hermes_webapp_turn_complete", "Task finished",
        "A reply finished while Hermes was in the background", NotificationManager.IMPORTANCE_DEFAULT, false,
    ),
    TURN_FAILED(
        "turn_failed", "hermes_webapp_turn_failed", "Task failed or stopped",
        "A reply ended with an error or was interrupted", NotificationManager.IMPORTANCE_DEFAULT, false,
    ),
    APPROVAL(
        "approval", "hermes_webapp_approval", "Approval requests",
        "Hermes asks before running a command; Allow needs an unlocked phone", NotificationManager.IMPORTANCE_HIGH, true,
    ),
    CLARIFY(
        "clarify", "hermes_webapp_clarify", "Questions from Hermes",
        "Hermes needs an answer; tap to open the app", NotificationManager.IMPORTANCE_HIGH, true,
    ),
    BACKGROUND_COMPLETE(
        "background_complete", "hermes_webapp_background", "Background tasks",
        "A background task finished", NotificationManager.IMPORTANCE_DEFAULT, false,
    );

    /** Unique within [ALERT_TAG]: the kind picks the top byte, the key hash the low 24 bits. */
    fun notificationId(key: String): Int = ((ordinal + 1) shl 24) + (key.hashCode() and 0x00FF_FFFF)

    companion object {
        const val ALERT_TAG = "hermes.webapp"
    }
}

/** The single settings value for the Webapp background feature. Absent storage reads as these defaults. */
data class WebappBackgroundSettings(
    val keepAlive: Boolean = true,
    val showPreview: Boolean = true,
    /** The disabled kinds, so a kind added later starts enabled without a migration. */
    val disabled: Set<GatewayAlertKind> = emptySet(),
) {
    fun isEnabled(kind: GatewayAlertKind): Boolean = kind !in disabled

    fun disabledStorageKeys(): Set<String> = disabled.mapTo(mutableSetOf()) { it.storageKey }

    companion object {
        /** Unknown keys (a newer build's kind, or a removed one) are ignored. */
        fun disabledFromStorage(keys: Set<String>?): Set<GatewayAlertKind> =
            GatewayAlertKind.entries.filterTo(mutableSetOf()) { keys.orEmpty().contains(it.storageKey) }
    }
}

/** Random per page document, minted by the injected tap. */
@JvmInline
value class DocId(val value: String)

/** Identifies an open prompt within one document: the tap's socket number plus the request id. */
data class PromptKey(val sock: Int, val rpcId: RpcId) {
    fun encode(): String = "$sock|${rpcId.json}"

    companion object {
        fun decode(text: String): PromptKey? {
            val sock = text.substringBefore('|', "").toIntOrNull()?.takeIf { it > 0 } ?: return null
            val rpcId = RpcId.fromToken(text.substringAfter('|')) ?: return null
            return PromptKey(sock, rpcId)
        }
    }
}

data class OpenPrompt(
    val request: GatewayEvent.PromptRequest,
    /** A tray answer is in flight; the prompt is hidden until it succeeds or fails. */
    val sending: Boolean = false,
) {
    val kind: GatewayAlertKind
        get() = when (request) {
            is GatewayEvent.ApprovalRequested -> GatewayAlertKind.APPROVAL
            is GatewayEvent.ClarifyRequested -> GatewayAlertKind.CLARIFY
        }
}

data class GatewayDocument(
    val id: DocId,
    val prompts: Map<PromptKey, OpenPrompt> = emptyMap(),
    /** sessionId to title, newest last, bounded to [MAX_TITLES]. */
    val titles: Map<String, String> = emptyMap(),
) {
    companion object {
        const val MAX_TITLES = 32
    }
}

data class WebappState(
    val settings: WebappBackgroundSettings,
    val visible: Boolean = true,
    /** Null while no gateway document is live in the WebView. */
    val doc: GatewayDocument? = null,
)

data class AnswerTarget(val doc: DocId, val prompt: PromptKey)

/** What the shell renders. The body is already removed when previews are off. */
data class Alert(
    val kind: GatewayAlertKind,
    /** Replaces per key: the session id for one-shot kinds, the encoded [PromptKey] for prompts. */
    val key: String,
    val title: String,
    val body: String?,
    /** Set only for approvals, which carry Allow and Deny actions. */
    val answer: AnswerTarget?,
)

/** Inputs, already validated by the shell. Raw bridge text never reaches the reducer. */
sealed interface WebappInput {
    /** A gateway document announced itself; a new id starts from a clean slate. */
    data class Hello(val doc: DocId) : WebappInput
    data class Inbound(val doc: DocId, val sock: Int, val event: GatewayEvent) : WebappInput
    /** The page answered a request itself. */
    data class Responded(val doc: DocId, val sock: Int, val rpcId: RpcId) : WebappInput
    data class SocketClosed(val doc: DocId, val sock: Int) : WebappInput
    data class SendResult(val doc: DocId, val prompt: PromptKey, val ok: Boolean) : WebappInput
    data class TrayAnswer(val target: AnswerTarget, val decision: Decision) : WebappInput
    data class Visibility(val visible: Boolean) : WebappInput
    data class SettingsChanged(val settings: WebappBackgroundSettings) : WebappInput
    data object DocumentGone : WebappInput
}

sealed interface WebappEffect {
    data class Post(val alert: Alert) : WebappEffect
    data class Cancel(val kind: GatewayAlertKind, val key: String) : WebappEffect
    data class Send(val doc: DocId, val sock: Int, val prompt: PromptKey, val frame: String) : WebappEffect
    data class KeepAlive(val on: Boolean) : WebappEffect
}

/**
 * Every Webapp background decision, pure and total. Invariants (each pinned by a test):
 *  - Keep-alive is on exactly while the setting is on, the app is hidden and a gateway document is
 *    live. [WebappEffect.KeepAlive] is emitted only when that value changes.
 *  - Shown prompt notifications are exactly the open prompts that are not sending and whose kind
 *    is enabled, while hidden. Prompt posts and cancels come only from diffing that set before and
 *    after each input, so no path can leave a stale Allow/Deny in the tray.
 *  - One-shot alerts are posted only while hidden and enabled.
 *  - Only [WebappInput.Hello] changes the document; any other input naming another document is dropped.
 *  - A send is emitted only for an open approval that is not already sending.
 */
object WebappAlertReducer {
    private const val MAX_DETAIL = 1_000

    fun reduce(state: WebappState, input: WebappInput): Pair<WebappState, List<WebappEffect>> {
        val (next, actions) = apply(state, input)
        return next to (actions + diffPrompts(state, next) + keepAliveChange(state, next))
    }

    fun keepAlive(state: WebappState): Boolean = state.settings.keepAlive && !state.visible && state.doc != null

    fun promptAlerts(state: WebappState): Map<Pair<GatewayAlertKind, String>, Alert> {
        val doc = state.doc ?: return emptyMap()
        if (state.visible) return emptyMap()
        return doc.prompts
            .filter { (_, prompt) -> !prompt.sending && state.settings.isEnabled(prompt.kind) }
            .map { (key, prompt) -> promptAlert(state, doc, key, prompt) }
            .associateBy { it.kind to it.key }
    }

    private fun apply(state: WebappState, input: WebappInput): Pair<WebappState, List<WebappEffect>> =
        when (input) {
            is WebappInput.Visibility -> state.copy(visible = input.visible) to emptyList()
            is WebappInput.SettingsChanged -> state.copy(settings = input.settings) to emptyList()
            WebappInput.DocumentGone -> state.copy(doc = null) to emptyList()
            is WebappInput.Hello ->
                (if (state.doc?.id == input.doc) state else state.copy(doc = GatewayDocument(input.doc))) to emptyList()
            is WebappInput.Inbound -> state.docFor(input.doc)
                ?.let { inbound(state, it, input.sock, input.event) }
                ?: (state to emptyList())
            is WebappInput.Responded -> state.docFor(input.doc)
                ?.let { state.withPrompts(it, it.prompts - PromptKey(input.sock, input.rpcId)) }
                .orUnchanged(state)
            is WebappInput.SocketClosed -> state.docFor(input.doc)
                ?.let { doc -> state.withPrompts(doc, doc.prompts.filterKeys { it.sock != input.sock }) }
                .orUnchanged(state)
            is WebappInput.SendResult -> state.docFor(input.doc)?.let { doc ->
                val prompt = doc.prompts[input.prompt] ?: return@let null
                val prompts = if (input.ok) doc.prompts - input.prompt
                else doc.prompts + (input.prompt to prompt.copy(sending = false))
                state.withPrompts(doc, prompts)
            }.orUnchanged(state)
            is WebappInput.TrayAnswer -> trayAnswer(state, input)
        }

    private fun inbound(
        state: WebappState,
        doc: GatewayDocument,
        sock: Int,
        event: GatewayEvent,
    ): Pair<WebappState, List<WebappEffect>> = when (event) {
        is GatewayEvent.PromptRequest ->
            state.withPrompts(doc, doc.prompts + (PromptKey(sock, event.rpcId) to OpenPrompt(event))) to emptyList()
        is GatewayEvent.ApprovalCancelled -> {
            val remaining = doc.prompts.filterValues { prompt ->
                val request = prompt.request
                !(request is GatewayEvent.ApprovalRequested && request.sessionId == event.sessionId &&
                    (event.requestIds.isEmpty() || request.requestId in event.requestIds))
            }
            state.withPrompts(doc, remaining) to emptyList()
        }
        is GatewayEvent.SessionTitled -> {
            val titles = (doc.titles - event.sessionId + (event.sessionId to event.title))
                .entries.toList().takeLast(GatewayDocument.MAX_TITLES).associate { it.key to it.value }
            state.copy(doc = doc.copy(titles = titles)) to emptyList()
        }
        is GatewayEvent.TurnEnded, is GatewayEvent.BackgroundCompleted ->
            state to listOfNotNull(oneShotAlert(state, doc, event)?.let(WebappEffect::Post))
    }

    private fun trayAnswer(state: WebappState, input: WebappInput.TrayAnswer): Pair<WebappState, List<WebappEffect>> {
        val doc = state.docFor(input.target.doc) ?: return state to emptyList()
        val key = input.target.prompt
        val prompt = doc.prompts[key]?.takeUnless { it.sending } ?: return state to emptyList()
        val request = prompt.request as? GatewayEvent.ApprovalRequested ?: return state to emptyList()
        val choice = GatewayProtocol.choiceFor(input.decision, request.choices) ?: return state to emptyList()
        val send = WebappEffect.Send(doc.id, key.sock, key, GatewayProtocol.encodeApprovalAnswer(request.rpcId, choice))
        return state.withPrompts(doc, doc.prompts + (key to prompt.copy(sending = true))) to listOf(send)
    }

    private fun oneShotAlert(state: WebappState, doc: GatewayDocument, event: GatewayEvent): Alert? {
        val (kind, headline, detail) = when (event) {
            is GatewayEvent.TurnEnded -> when (event.outcome) {
                TurnOutcome.COMPLETE -> Triple(GatewayAlertKind.TURN_COMPLETE, "Task finished", event.text)
                TurnOutcome.ERROR -> Triple(GatewayAlertKind.TURN_FAILED, "Task failed", event.error ?: event.text)
                TurnOutcome.INTERRUPTED -> Triple(GatewayAlertKind.TURN_FAILED, "Task stopped", event.error ?: event.text)
            }
            is GatewayEvent.BackgroundCompleted ->
                Triple(GatewayAlertKind.BACKGROUND_COMPLETE, "Background task finished", event.text)
            else -> return null
        }
        if (state.visible || !state.settings.isEnabled(kind)) return null
        return alert(state, doc, kind, event.sessionId, event.sessionId, headline, detail, answer = null)
    }

    private fun promptAlert(state: WebappState, doc: GatewayDocument, key: PromptKey, prompt: OpenPrompt): Alert {
        val encoded = key.encode()
        return when (val request = prompt.request) {
            is GatewayEvent.ApprovalRequested -> alert(
                state, doc, GatewayAlertKind.APPROVAL, encoded, request.sessionId, "Approval needed",
                request.command ?: request.description ?: request.toolName,
                AnswerTarget(doc.id, key),
            )
            is GatewayEvent.ClarifyRequested -> alert(
                state, doc, GatewayAlertKind.CLARIFY, encoded, request.sessionId, "Hermes has a question",
                request.question, answer = null,
            )
        }
    }

    private fun alert(
        state: WebappState,
        doc: GatewayDocument,
        kind: GatewayAlertKind,
        key: String,
        sessionId: String,
        headline: String,
        detail: String?,
        answer: AnswerTarget?,
    ): Alert {
        val preview = state.settings.showPreview
        val sessionTitle = doc.titles[sessionId]?.takeIf { preview }
        return Alert(
            kind = kind,
            key = key,
            title = if (sessionTitle != null) "$headline · $sessionTitle" else headline,
            body = detail?.takeIf { preview }?.trim()?.take(MAX_DETAIL)?.ifEmpty { null },
            answer = answer,
        )
    }

    private fun diffPrompts(before: WebappState, after: WebappState): List<WebappEffect> {
        val old = promptAlerts(before)
        val new = promptAlerts(after)
        val cancels = (old.keys - new.keys).map { (kind, key) -> WebappEffect.Cancel(kind, key) }
        val posts = new.filter { (slot, alert) -> old[slot] != alert }.values.map(WebappEffect::Post)
        return cancels + posts
    }

    private fun keepAliveChange(before: WebappState, after: WebappState): List<WebappEffect> {
        val on = keepAlive(after)
        return if (keepAlive(before) == on) emptyList() else listOf(WebappEffect.KeepAlive(on))
    }

    private fun WebappState.docFor(id: DocId): GatewayDocument? = doc?.takeIf { it.id == id }

    private fun WebappState.withPrompts(doc: GatewayDocument, prompts: Map<PromptKey, OpenPrompt>) =
        copy(doc = doc.copy(prompts = prompts))

    private fun WebappState?.orUnchanged(state: WebappState): Pair<WebappState, List<WebappEffect>> =
        (this ?: state) to emptyList()
}

/** Parses the tap's bridge envelope into a [WebappInput]. Anything malformed is null. */
object WebappBridgeEnvelope {
    private const val MAX_DOC_ID = 64

    fun parse(data: String?): WebappInput? {
        val message = runCatching { JSONTokener(data ?: return null).nextValue() as? JSONObject }.getOrNull()
            ?: return null
        val doc = (message.opt("doc") as? String)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_DOC_ID }
            ?.let(::DocId)
            ?: return null
        val sock = (message.opt("sock") as? Int)?.takeIf { it > 0 }
        val frame = message.opt("data") as? String
        val key = (message.opt("key") as? String)?.let(PromptKey::decode)
        return when (message.opt("t")) {
            "hello" -> WebappInput.Hello(doc)
            "in" -> if (sock == null || frame == null) null
                else GatewayProtocol.parseInbound(frame)?.let { WebappInput.Inbound(doc, sock, it) }
            "out" -> if (sock == null || frame == null) null
                else GatewayProtocol.parseResponseId(frame)?.let { WebappInput.Responded(doc, sock, it) }
            "close" -> sock?.let { WebappInput.SocketClosed(doc, it) }
            "sent" -> key?.let { WebappInput.SendResult(doc, it, ok = true) }
            "sendFailed" -> key?.let { WebappInput.SendResult(doc, it, ok = false) }
            else -> null
        }
    }
}
