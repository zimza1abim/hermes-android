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
    /** sessionId to chat title, newest last, bounded to [MAX_TITLES]. */
    val titles: Map<String, String> = emptyMap(),
    /** One-shot alerts posted with a preview, newest last, so a title that arrives later can retitle them. */
    val oneShots: Map<Pair<GatewayAlertKind, String>, Alert> = emptyMap(),
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
    /** The session an alert tap asked to open, held until the app is visible. */
    val focus: String? = null,
)

data class AnswerTarget(val doc: DocId, val prompt: PromptKey)

/** The tray actions an alert offers; each answers [target]. */
sealed interface AlertActions {
    val target: AnswerTarget

    /** Allow and Deny on an approval. */
    data class Decide(override val target: AnswerTarget) : AlertActions

    /** One button per choice of a one-question clarify. */
    data class Choose(override val target: AnswerTarget, val choices: List<String>) : AlertActions

    /** A free-text reply to a one-question clarify. */
    data class Reply(override val target: AnswerTarget) : AlertActions
}

/** An answer from the tray; it must match the actions the prompt offers. */
sealed interface TrayReply {
    data class Decide(val decision: Decision) : TrayReply
    data class Choose(val index: Int) : TrayReply
    data class Reply(val text: String) : TrayReply
}

/** Why an alert was posted; the shell shows it as the notification's short subtext. */
enum class AlertReason { COMPLETE, ERROR, INTERRUPTED, APPROVAL, QUESTION, BACKGROUND }

/** What the shell renders. The title and body are already removed when previews are off. */
data class Alert(
    val kind: GatewayAlertKind,
    /** Replaces per key: the session id for one-shot kinds, the encoded [PromptKey] for prompts. */
    val key: String,
    /** A tap asks the page to open this session. */
    val sessionId: String,
    val reason: AlertReason,
    /** The chat title, else the detail's first line; null shows the app name. */
    val title: String?,
    val body: String?,
    /** Null when the alert is tap-only. */
    val actions: AlertActions?,
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
    data class TrayAnswer(val target: AnswerTarget, val reply: TrayReply) : WebappInput
    data class Visibility(val visible: Boolean) : WebappInput
    data class SettingsChanged(val settings: WebappBackgroundSettings) : WebappInput
    /** An alert tap brought the app forward and names the session to open. */
    data class FocusRequested(val sessionId: String) : WebappInput
    data object DocumentGone : WebappInput
}

sealed interface WebappEffect {
    /** [update] replaces an alert already in the tray: silently, and only if it is still shown. */
    data class Post(val alert: Alert, val update: Boolean = false) : WebappEffect
    data class Cancel(val kind: GatewayAlertKind, val key: String) : WebappEffect
    data class Send(val doc: DocId, val sock: Int, val prompt: PromptKey, val frame: String) : WebappEffect
    data class KeepAlive(val on: Boolean) : WebappEffect
    /** Ask the live document to open a session; emitted only while visible. */
    data class FocusSession(val sessionId: String) : WebappEffect
}

/**
 * Every Webapp background decision, pure and total. Invariants (each pinned by a test):
 *  - Keep-alive is on exactly while the setting is on, the app is hidden and a gateway document is
 *    live. [WebappEffect.KeepAlive] is emitted only when that value changes.
 *  - Shown prompt notifications are exactly the open prompts that are not sending and whose kind
 *    is enabled, while hidden. Prompt posts and cancels come only from diffing that set before and
 *    after each input, so no path can leave a stale Allow/Deny in the tray.
 *  - One-shot alerts are posted only while hidden and enabled; a chat title that arrives later
 *    only updates them in place.
 *  - Only [WebappInput.Hello] changes the document; any other input naming another document is dropped.
 *  - A send is emitted only for an open prompt that is not already sending, and only for a reply
 *    that matches what the prompt offers: a decision for an approval, a choice or text for a
 *    one-question, single-select clarify (choices for 1-[MAX_CHOICE_ACTIONS] choices, else text).
 *    A multi-question clarify is tap-only. Previews off hide clarify actions with the question.
 *  - A focus request is delivered at most once, only while visible and a document is live; a new
 *    document drops it.
 */
object WebappAlertReducer {
    private const val MAX_DETAIL = 1_000
    const val MAX_CHOICE_ACTIONS = 3
    private const val MAX_REPLY = 4_000
    const val MAX_TITLE = 60

    /** The chat title, else the first non-blank line of [detail] capped at [MAX_TITLE]; null with previews off. */
    fun alertTitle(chatTitle: String?, detail: String?, preview: Boolean): String? {
        if (!preview) return null
        chatTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val line = detail?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length <= MAX_TITLE) line else line.take(MAX_TITLE - 1).trimEnd() + "…"
    }

    fun reduce(state: WebappState, input: WebappInput): Pair<WebappState, List<WebappEffect>> {
        val (applied, actions) = apply(state, input)
        val (next, focus) = deliverFocus(applied)
        return next to (actions + diffPrompts(state, next) + keepAliveChange(state, next) + focus)
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
            is WebappInput.FocusRequested -> state.copy(focus = input.sessionId) to emptyList()
            WebappInput.DocumentGone -> state.copy(doc = null, focus = null) to emptyList()
            is WebappInput.Hello ->
                (if (state.doc?.id == input.doc) state else state.copy(doc = GatewayDocument(input.doc), focus = null)) to emptyList()
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
            val titles = (doc.titles - event.sessionId + (event.sessionId to event.title)).newest()
            val retitled = doc.oneShots.filterValues { it.sessionId == event.sessionId && it.title != event.title }
                .mapValues { (_, alert) -> alert.copy(title = event.title) }
                .takeIf { state.settings.showPreview }.orEmpty()
            state.copy(doc = doc.copy(titles = titles, oneShots = doc.oneShots + retitled)) to
                retitled.values.map { WebappEffect.Post(it, update = true) }
        }
        is GatewayEvent.TurnEnded, is GatewayEvent.BackgroundCompleted -> {
            val alert = oneShotAlert(state, doc, event)
            val oneShots = if (alert == null || !state.settings.showPreview) doc.oneShots
            else (doc.oneShots - (alert.kind to alert.key) + ((alert.kind to alert.key) to alert)).newest()
            state.copy(doc = doc.copy(oneShots = oneShots)) to listOfNotNull(alert?.let(WebappEffect::Post))
        }
    }

    private fun <K, V> Map<K, V>.newest(): Map<K, V> =
        entries.toList().takeLast(GatewayDocument.MAX_TITLES).associate { it.key to it.value }

    private fun trayAnswer(state: WebappState, input: WebappInput.TrayAnswer): Pair<WebappState, List<WebappEffect>> {
        val doc = state.docFor(input.target.doc) ?: return state to emptyList()
        val key = input.target.prompt
        val prompt = doc.prompts[key]?.takeUnless { it.sending } ?: return state to emptyList()
        val frame = answerFrame(prompt.request, input.reply) ?: return state to emptyList()
        val send = WebappEffect.Send(doc.id, key.sock, key, frame)
        return state.withPrompts(doc, doc.prompts + (key to prompt.copy(sending = true))) to listOf(send)
    }

    private fun answerFrame(request: GatewayEvent.PromptRequest, reply: TrayReply): String? = when (request) {
        is GatewayEvent.ApprovalRequested -> (reply as? TrayReply.Decide)
            ?.let { GatewayProtocol.choiceFor(it.decision, request.choices) }
            ?.let { GatewayProtocol.encodeApprovalAnswer(request.rpcId, it) }
        is GatewayEvent.ClarifyRequested -> answerable(request)?.let { question ->
            val text = when {
                reply is TrayReply.Choose && question.offersChoices -> question.choices.getOrNull(reply.index)
                reply is TrayReply.Reply && !question.offersChoices -> reply.text.trim().take(MAX_REPLY).ifEmpty { null }
                else -> null
            }
            text?.let { GatewayProtocol.encodeClarifyAnswer(request.rpcId, question.qid, it) }
        }
    }

    /** The one question of a clarify that tray actions can answer. */
    private class Answerable(val qid: String, val choices: List<String>) {
        val offersChoices: Boolean get() = choices.size in 1..MAX_CHOICE_ACTIONS
    }

    /** Exactly one question, with an id, single-select; anything else is tap-only. */
    private fun answerable(request: GatewayEvent.ClarifyRequested): Answerable? {
        val question = request.questions.singleOrNull()?.takeUnless { it.multiSelect } ?: return null
        return question.qid?.let { Answerable(it, question.choices) }
    }

    private fun clarifyActions(state: WebappState, request: GatewayEvent.ClarifyRequested, target: AnswerTarget): AlertActions? {
        if (!state.settings.showPreview) return null
        val question = answerable(request) ?: return null
        return if (question.offersChoices) AlertActions.Choose(target, question.choices) else AlertActions.Reply(target)
    }

    private fun clarifyDetail(request: GatewayEvent.ClarifyRequested): String? {
        val question = request.questions.singleOrNull()
            ?: return request.questions.mapNotNull { it.question }.joinToString("\n").ifEmpty { null }
        val listed = question.choices.takeIf { it.size > MAX_CHOICE_ACTIONS }.orEmpty()
        return (listOfNotNull(question.question) + listed.map { "• $it" }).joinToString("\n").ifEmpty { null }
    }

    private fun oneShotAlert(state: WebappState, doc: GatewayDocument, event: GatewayEvent): Alert? {
        val (kind, reason, detail) = when (event) {
            is GatewayEvent.TurnEnded -> when (event.outcome) {
                TurnOutcome.COMPLETE -> Triple(GatewayAlertKind.TURN_COMPLETE, AlertReason.COMPLETE, event.text)
                TurnOutcome.ERROR -> Triple(GatewayAlertKind.TURN_FAILED, AlertReason.ERROR, event.error ?: event.text)
                TurnOutcome.INTERRUPTED -> Triple(GatewayAlertKind.TURN_FAILED, AlertReason.INTERRUPTED, event.error ?: event.text)
            }
            is GatewayEvent.BackgroundCompleted ->
                Triple(GatewayAlertKind.BACKGROUND_COMPLETE, AlertReason.BACKGROUND, event.text)
            else -> return null
        }
        if (state.visible || !state.settings.isEnabled(kind)) return null
        return alert(state, doc, kind, event.sessionId, event.sessionId, reason, detail, actions = null)
    }

    private fun promptAlert(state: WebappState, doc: GatewayDocument, key: PromptKey, prompt: OpenPrompt): Alert {
        val encoded = key.encode()
        return when (val request = prompt.request) {
            is GatewayEvent.ApprovalRequested -> alert(
                state, doc, GatewayAlertKind.APPROVAL, encoded, request.sessionId, AlertReason.APPROVAL,
                request.command ?: request.description ?: request.toolName,
                AlertActions.Decide(AnswerTarget(doc.id, key)),
            )
            is GatewayEvent.ClarifyRequested -> alert(
                state, doc, GatewayAlertKind.CLARIFY, encoded, request.sessionId, AlertReason.QUESTION,
                clarifyDetail(request), clarifyActions(state, request, AnswerTarget(doc.id, key)),
            )
        }
    }

    private fun alert(
        state: WebappState,
        doc: GatewayDocument,
        kind: GatewayAlertKind,
        key: String,
        sessionId: String,
        reason: AlertReason,
        detail: String?,
        actions: AlertActions?,
    ): Alert {
        val preview = state.settings.showPreview
        return Alert(
            kind = kind,
            key = key,
            sessionId = sessionId,
            reason = reason,
            title = alertTitle(doc.titles[sessionId], detail, preview),
            body = detail?.takeIf { preview }?.trim()?.take(MAX_DETAIL)?.ifEmpty { null },
            actions = actions,
        )
    }

    private fun diffPrompts(before: WebappState, after: WebappState): List<WebappEffect> {
        val old = promptAlerts(before)
        val new = promptAlerts(after)
        val cancels = (old.keys - new.keys).map { (kind, key) -> WebappEffect.Cancel(kind, key) }
        val posts = new.filter { (slot, alert) -> old[slot] != alert }
            .map { (slot, alert) -> WebappEffect.Post(alert, update = slot in old) }
        return cancels + posts
    }

    private fun deliverFocus(state: WebappState): Pair<WebappState, List<WebappEffect>> {
        val sessionId = state.focus ?: return state to emptyList()
        if (!state.visible) return state to emptyList()
        val cleared = state.copy(focus = null)
        return cleared to listOfNotNull(WebappEffect.FocusSession(sessionId).takeIf { state.doc != null })
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
