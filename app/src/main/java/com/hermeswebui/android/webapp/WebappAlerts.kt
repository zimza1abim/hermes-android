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

/** Where an open prompt was delivered, and so where its answer can go. */
sealed interface PromptRoute {
    /** A socket of the live page document, numbered by the tap. */
    data class Page(val sock: Int) : PromptRoute

    data object Monitor : PromptRoute
}

data class OpenPrompt(
    val request: GatewayEvent.PromptRequest,
    val routes: Set<PromptRoute>,
    /** A tray answer is in flight; the prompt is hidden until it succeeds or fails. */
    val sending: Boolean = false,
    val answerFailed: Boolean = false,
    val answerUncertain: Boolean = false,
) {
    val kind: GatewayAlertKind
        get() = when (request) {
            is GatewayEvent.ApprovalRequested -> GatewayAlertKind.APPROVAL
            is GatewayEvent.ClarifyRequested -> GatewayAlertKind.CLARIFY
        }
}

/** What the app knows about one chat, keyed by its runtime id. */
data class SessionFacts(val storedId: String? = null, val title: String? = null, val running: Boolean = false)

/** One gateway event across sockets: the server stamps the same per-session seq on every copy of a frame. */
data class EventMark(val sessionId: String, val seq: Long)

data class WebappState(
    val settings: WebappBackgroundSettings,
    val visible: Boolean = true,
    /** Null while no gateway document is live in the WebView. */
    val doc: DocId? = null,
    /** A Webapp document has activated monitoring for this background run. */
    val webappActive: Boolean = false,
    /** Open server requests by the server's id, which is the same on the page's sockets and the monitor's. */
    val prompts: Map<RpcId, OpenPrompt> = emptyMap(),
    /** Requests answered or withdrawn, newest last, so a late copy from a slower socket cannot reopen one. */
    val settled: List<RpcId> = emptyList(),
    /** One-shot events already handled, newest last, so the second socket's copy does not alert again. */
    val seen: List<EventMark> = emptyList(),
    /** Runtime session id to its facts, newest last, bounded to [MAX_SESSIONS]. */
    val sessions: Map<String, SessionFacts> = emptyMap(),
    /** The chat the page last reported through `session.info`. */
    val pageSession: String? = null,
    /** One-shot alerts posted with a preview, newest last, so a title that arrives later can retitle them. */
    val oneShots: Map<Pair<GatewayAlertKind, String>, Alert> = emptyMap(),
    val monitor: MonitorState = MonitorState(),
    /** The session an alert tap asked to open, held until the app is visible. */
    val focus: String? = null,
) {
    companion object {
        const val MAX_SESSIONS = 256
        const val MAX_RECENT = 64
        const val MAX_WATCH = 256
    }
}

/** An open prompt a tray action answers. */
data class AnswerTarget(val rpcId: RpcId)

/** The socket an answer goes out on. */
sealed interface AnswerRoute {
    data class Page(val doc: DocId, val sock: Int) : AnswerRoute
    data class Monitor(val gen: Int) : AnswerRoute
}

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
    /** Replaces per key: the session id for one-shot kinds, the request id token for prompts. */
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

/** Inputs, already validated by the shell. Raw bridge and socket text never reaches the reducer. */
sealed interface WebappInput {
    /** A gateway document announced itself; a new id drops the old document's sockets. */
    data class Hello(val doc: DocId) : WebappInput
    data class Inbound(val doc: DocId, val sock: Int, val event: GatewayEvent) : WebappInput
    /** The page answered a request itself. */
    data class Responded(val doc: DocId, val sock: Int, val rpcId: RpcId) : WebappInput
    data class SocketClosed(val doc: DocId, val sock: Int) : WebappInput
    /** Whether a tray answer reached its socket. */
    data class SendResult(val rpcId: RpcId, val ok: Boolean, val uncertain: Boolean = false) : WebappInput
    data class PageAck(val doc: DocId, val rpcId: RpcId, val accepted: Boolean) : WebappInput
    data class TrayAnswer(val target: AnswerTarget, val reply: TrayReply) : WebappInput
    data class Visibility(val visible: Boolean) : WebappInput
    data class SettingsChanged(val settings: WebappBackgroundSettings) : WebappInput
    /** An alert tap brought the app forward and names the session to open. */
    data class FocusRequested(val sessionId: String) : WebappInput
    data object DocumentGone : WebappInput
    data object ServerChanged : WebappInput
    /** A different network became the default while keep-alive runs. */
    data object NetworkChanged : WebappInput

    /** A callback from the monitor's attempt [gen]. */
    sealed interface MonitorInput : WebappInput {
        val gen: Int
    }

    /** Null when the ticket could not be minted. */
    data class TicketMinted(override val gen: Int, val ticket: String?) : MonitorInput {
        override fun toString() = "TicketMinted(gen=$gen, ticket=${if (ticket == null) "null" else "…"})"
    }
    data class MonitorOpened(override val gen: Int) : MonitorInput
    data class MonitorReceived(override val gen: Int, val frame: MonitorFrame) : MonitorInput
    data class MonitorClosed(override val gen: Int) : MonitorInput
    data class RetryDue(override val gen: Int) : MonitorInput
}

sealed interface WebappEffect {
    /** [update] replaces an alert already in the tray: silently, and only if it is still shown. */
    data class Post(val alert: Alert, val update: Boolean = false) : WebappEffect
    data class Cancel(val kind: GatewayAlertKind, val key: String) : WebappEffect
    /** Sends a tray answer; the shell reports [WebappInput.SendResult]. */
    data class Answer(val rpcId: RpcId, val frame: String, val via: AnswerRoute) : WebappEffect
    data class KeepAlive(val on: Boolean) : WebappEffect
    /** Ask the live document to open a session; emitted only while visible. */
    data class FocusSession(val sessionId: String) : WebappEffect
    data object QuietWatchFallback : WebappEffect
    data class MonitorMint(val gen: Int) : WebappEffect
    data class MonitorDial(val gen: Int, val ticket: String) : WebappEffect {
        override fun toString() = "MonitorDial(gen=$gen)"
    }
    data class MonitorSend(val gen: Int, val frame: String) : WebappEffect
    /** Closes attempt [gen] and cancels anything it still has in flight. */
    data class MonitorClose(val gen: Int) : WebappEffect
    data class MonitorRetryAfter(val gen: Int, val delayMs: Long) : WebappEffect
}

/**
 * Every Webapp background decision, pure and total. Invariants (each pinned by a test):
 *  - Keep-alive is on exactly while the setting is on, the app is hidden and a gateway document is
 *    live. [WebappEffect.KeepAlive] is emitted only when that value changes. The native monitor is
 *    wanted exactly while keep-alive is on; it attaches to [watchSet].
 *  - A prompt is one server request, keyed by the server's id, however many sockets delivered it.
 *    It stays open while some route still reaches it; an answer, a page response, a withdrawal or the
 *    monitor's attach snapshot settles it, and a settled id never reopens.
 *  - Shown prompt notifications are exactly the open prompts that are not sending and whose kind is
 *    enabled, while hidden. Prompt posts and cancels come only from diffing that set before and after
 *    each input, so no path can leave a stale Allow/Deny in the tray.
 *  - One-shot alerts are posted only while hidden and enabled, once per event: by the event's seq
 *    when it has one, else only from the monitor for a session the monitor is attached to.
 *  - A send is emitted only for an open prompt that is not already sending, and only for a reply
 *    that matches what the prompt offers: a decision for an approval, a choice or text for a
 *    one-question, single-select clarify (choices for 1-[MAX_CHOICE_ACTIONS] choices, else text).
 *    It goes on the monitor when the monitor is live and delivered the prompt, else on a page socket
 *    that delivered it. A multi-question clarify is tap-only. Previews off hide clarify actions.
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
        val (monitor, monitorEffects) = GatewayMonitorMachine.reconcile(applied.monitor, keepAlive(applied), watchSet(applied),
            allowRotation = applied.prompts.values.none { it.sending })
        val stopped = monitor.phase == MonitorPhase.Idle && applied.monitor.phase != MonitorPhase.Idle
        val reconciled = applied.copy(monitor = monitor).let { if (stopped) it.dropRoutes { route -> route == PromptRoute.Monitor } else it }
        val (next, focus) = deliverFocus(reconciled)
        return next to (actions + diffPrompts(state, next) + keepAliveChange(state, next) + monitorEffects + focus)
    }

    fun keepAlive(state: WebappState): Boolean = state.settings.keepAlive && !state.visible && state.webappActive

    /**
     * Stored ids the monitor attaches to: the page's current chat, chats with a running turn and chats
     * with an open prompt, most urgent first. A chat whose stored id the app never learned is left to the page.
     */
    fun watchSet(state: WebappState): List<String> {
        val prompted = state.prompts.values.mapTo(mutableSetOf()) { it.request.sessionId }
        fun urgency(id: String, facts: SessionFacts) = when {
            id in prompted -> 2
            facts.running -> 1
            else -> 0
        }
        return state.sessions.entries
            .filter { (id, facts) -> facts.storedId != null && (id == state.pageSession || facts.running || id in prompted) }
            .sortedByDescending { (id, facts) -> urgency(id, facts) }
            .mapNotNull { it.value.storedId }
            .distinct()
            .take(WebappState.MAX_WATCH)
    }

    fun promptAlerts(state: WebappState): Map<Pair<GatewayAlertKind, String>, Alert> {
        if (state.visible) return emptyMap()
        return state.prompts.values
            .filter { prompt -> state.settings.isEnabled(prompt.kind) }
            .map { promptAlert(state, it) }
            .associateBy { it.kind to it.key }
    }

    private fun apply(state: WebappState, input: WebappInput): Pair<WebappState, List<WebappEffect>> =
        when (input) {
            is WebappInput.Visibility -> state.copy(visible = input.visible).alone()
            is WebappInput.SettingsChanged -> state.copy(settings = input.settings).alone()
            is WebappInput.FocusRequested -> state.copy(focus = input.sessionId).alone()
            WebappInput.DocumentGone -> state.copy(doc = null, focus = null,
                webappActive = !state.visible && state.webappActive).dropRoutes { it is PromptRoute.Page }.alone()
            WebappInput.ServerChanged -> state.copy(doc = null, focus = null, webappActive = false,
                sessions = emptyMap(), pageSession = null, seen = emptyList(), settled = emptyList(),
                oneShots = emptyMap(), prompts = emptyMap()).alone()
            is WebappInput.Hello ->
                (if (state.doc == input.doc) state
                else state.copy(doc = input.doc, focus = null, webappActive = true).dropRoutes { it is PromptRoute.Page }).alone()
            is WebappInput.Inbound ->
                if (state.doc != input.doc) state.alone() else inbound(state, PromptRoute.Page(input.sock), input.event)
            is WebappInput.Responded ->
                (if (state.doc != input.doc) state else state.settle(setOf(input.rpcId))).alone()
            is WebappInput.SocketClosed ->
                (if (state.doc != input.doc) state else state.dropRoutes { it == PromptRoute.Page(input.sock) }).alone()
            is WebappInput.PageAck -> if (state.doc == input.doc) apply(state,
                WebappInput.SendResult(input.rpcId, input.accepted, uncertain = !input.accepted)) else state.alone()
            is WebappInput.SendResult -> {
                val prompt = state.prompts[input.rpcId]
                when {
                    prompt == null -> state
                    input.ok -> state.settle(setOf(input.rpcId))
                    else -> state.copy(prompts = state.prompts + (input.rpcId to prompt.copy(sending = false, answerFailed = true, answerUncertain = input.uncertain)))
                }.alone()
            }
            is WebappInput.TrayAnswer -> trayAnswer(state, input)
            WebappInput.NetworkChanged -> GatewayMonitorMachine.networkChanged(state.monitor).let { (monitor, effects) ->
                state.copy(monitor = monitor) to effects
            }
            is WebappInput.MonitorInput -> monitorInput(state, input)
        }

    private fun monitorInput(state: WebappState, input: WebappInput.MonitorInput): Pair<WebappState, List<WebappEffect>> {
        val frame = (input as? WebappInput.MonitorReceived)?.frame
        val current = input.gen == state.monitor.gen && state.monitor.live != null
        return when {
            frame is MonitorFrame.Inbound -> if (current) inbound(state, PromptRoute.Monitor, frame.event) else state.alone()
            frame is MonitorFrame.Resumed -> if (current) resumed(state, frame.requestId, frame.snapshot) else state.alone()
            frame is MonitorFrame.AnswerAck -> if (current) apply(state, WebappInput.SendResult(frame.rpcId, frame.accepted, uncertain = !frame.accepted)) else state.alone()
            else -> GatewayMonitorMachine.step(state.monitor, input).let { (monitor, effects) -> state.copy(monitor = monitor) to effects }
        }
    }

    private fun inbound(state: WebappState, route: PromptRoute, event: GatewayEvent): Pair<WebappState, List<WebappEffect>> =
        when (event) {
            is GatewayEvent.PromptRequest -> state.open(event, route).alone()
            is GatewayEvent.ApprovalCancelled -> state.settle(
                state.prompts.filterValues { prompt ->
                    val request = prompt.request
                    request is GatewayEvent.ApprovalRequested && request.sessionId == event.sessionId &&
                        (event.requestIds.isEmpty() || request.requestId in event.requestIds)
                }.keys
            ).alone()
            is GatewayEvent.RequestCancelled -> state.settle(setOf(event.rpcId)).alone()
            is GatewayEvent.SessionTitled -> learn(state, event.sessionId, event.title) { it }
            is GatewayEvent.SessionInfo -> learn(state, event.sessionId, event.title) { facts ->
                facts.copy(storedId = event.storedId ?: facts.storedId, running = event.running ?: facts.running)
            }.let { (next, effects) -> (if (route is PromptRoute.Page) next.copy(pageSession = event.sessionId) else next) to effects }
            is GatewayEvent.TurnEnded -> learn(state, event.sessionId, null) { it.copy(running = false) }
                .let { (next, _) -> oneShot(next, route, event, event.seq) }
            is GatewayEvent.BackgroundCompleted -> oneShot(state, route, event, event.seq)
        }

    /** Applies the monitor's attach snapshot: the session's facts and every request still open in it. */
    private fun resumed(state: WebappState, requestId: Int, snapshot: ResumeSnapshot?): Pair<WebappState, List<WebappEffect>> {
        val (monitor, stored) = GatewayMonitorMachine.resumed(state.monitor, requestId, snapshot)
        if (stored == null || snapshot == null) return state.copy(monitor = monitor).alone()
        val (learned, effects) = learn(state.copy(monitor = monitor), snapshot.runtimeId, snapshot.title) {
            it.copy(storedId = snapshot.storedId, running = snapshot.running)
        }
        // Only prompts the monitor alone was keeping are dropped: one the page delivered after the
        // snapshot was taken may not be in it yet.
        val open = snapshot.openRequests.mapTo(mutableSetOf()) { it.rpcId }
        val stale = learned.prompts.filterValues {
            it.request.sessionId == snapshot.runtimeId && it.routes == setOf(PromptRoute.Monitor) && it.request.rpcId !in open
        }
        val next = snapshot.openRequests.fold(learned.copy(prompts = learned.prompts - stale.keys)) { acc, request ->
            acc.open(request, PromptRoute.Monitor)
        }
        return next to effects
    }

    /** Updates a session's facts; a new non-blank [title] also retitles its shown one-shot alerts in place. */
    private fun learn(
        state: WebappState,
        sessionId: String,
        title: String?,
        update: (SessionFacts) -> SessionFacts,
    ): Pair<WebappState, List<WebappEffect>> {
        val known = state.sessions[sessionId] ?: SessionFacts()
        val facts = update(known).let { if (title.isNullOrBlank()) it else it.copy(title = title) }
        val sessions = (state.sessions - sessionId + (sessionId to facts)).newest(WebappState.MAX_SESSIONS)
        val retitled = if (title.isNullOrBlank() || !state.settings.showPreview) emptyMap()
        else state.oneShots.filterValues { it.sessionId == sessionId && it.title != title }.mapValues { (_, alert) -> alert.copy(title = title) }
        return state.copy(sessions = sessions, oneShots = state.oneShots + retitled) to
            retitled.values.map { WebappEffect.Post(it, update = true) }
    }

    private fun oneShot(state: WebappState, route: PromptRoute, event: GatewayEvent, seq: Long?): Pair<WebappState, List<WebappEffect>> {
        val mark = seq?.let { EventMark(event.sessionId, it) }
        if (mark != null && mark in state.seen) return state.alone()
        if (mark == null && route is PromptRoute.Page && state.monitor.covers(event.sessionId)) return state.alone()
        val seen = if (mark == null) state.seen else (state.seen + mark).takeLast(WebappState.MAX_RECENT)
        val alert = oneShotAlert(state, event)
        val oneShots = if (alert == null || !state.settings.showPreview) state.oneShots
        else (state.oneShots - (alert.kind to alert.key) + ((alert.kind to alert.key) to alert)).newest(WebappState.MAX_SESSIONS)
        return state.copy(seen = seen, oneShots = oneShots) to listOfNotNull(alert?.let(WebappEffect::Post))
    }

    private fun trayAnswer(state: WebappState, input: WebappInput.TrayAnswer): Pair<WebappState, List<WebappEffect>> {
        val rpcId = input.target.rpcId
        val prompt = state.prompts[rpcId]?.takeUnless { it.sending || it.answerUncertain } ?: return state.alone()
        val frame = answerFrame(prompt.request, input.reply) ?: return state.alone()
        val route = answerRoute(state, prompt)
            ?: return state.copy(prompts = state.prompts + (rpcId to prompt.copy(answerFailed = true))).alone()
        return state.copy(prompts = state.prompts + (rpcId to prompt.copy(sending = true, answerFailed = false, answerUncertain = false))) to
            listOf(WebappEffect.Answer(rpcId, frame, route))
    }

    private fun answerRoute(state: WebappState, prompt: OpenPrompt): AnswerRoute? {
        if (state.monitor.live != null && PromptRoute.Monitor in prompt.routes) return AnswerRoute.Monitor(state.monitor.gen)
        val doc = state.doc ?: return null
        return prompt.routes.filterIsInstance<PromptRoute.Page>().minByOrNull { it.sock }
            ?.let { AnswerRoute.Page(doc, it.sock) }
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

    private fun oneShotAlert(state: WebappState, event: GatewayEvent): Alert? {
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
        return alert(state, kind, event.sessionId, event.sessionId, reason, detail, actions = null)
    }

    private fun promptAlert(state: WebappState, prompt: OpenPrompt): Alert {
        val request = prompt.request
        val target = AnswerTarget(request.rpcId)
        val result = when (request) {
            is GatewayEvent.ApprovalRequested -> alert(
                state, GatewayAlertKind.APPROVAL, request.rpcId.json, request.sessionId, AlertReason.APPROVAL,
                request.command ?: request.description ?: request.toolName, AlertActions.Decide(target),
            )
            is GatewayEvent.ClarifyRequested -> alert(
                state, GatewayAlertKind.CLARIFY, request.rpcId.json, request.sessionId, AlertReason.QUESTION,
                clarifyDetail(request), clarifyActions(state, request, target),
            )
        }
        return result.copy(
            body = when {
                prompt.answerUncertain -> "Answer may have been sent. Open the chat to check before responding again."
                prompt.answerFailed -> "Not sent: connection unavailable. Try again when connected."
                else -> result.body
            },
            actions = if (prompt.sending || prompt.answerUncertain) null else result.actions,
        )
    }

    private fun alert(
        state: WebappState,
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
            title = alertTitle(state.sessions[sessionId]?.title, detail, preview),
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
        // The page opens chats by stored id and may not know a runtime id the monitor attached.
        val target = state.sessions[sessionId]?.storedId ?: sessionId
        return cleared to listOfNotNull(WebappEffect.FocusSession(target).takeIf { state.doc != null })
    }

    private fun keepAliveChange(before: WebappState, after: WebappState): List<WebappEffect> {
        val on = keepAlive(after)
        return if (keepAlive(before) == on) emptyList() else listOf(WebappEffect.KeepAlive(on))
    }

    /** Adds [route] to an open prompt, or opens it; a settled request stays closed. */
    private fun WebappState.open(request: GatewayEvent.PromptRequest, route: PromptRoute): WebappState {
        if (request.rpcId in settled) return this
        val prompt = prompts[request.rpcId]?.let { it.copy(routes = it.routes + route) } ?: OpenPrompt(request, setOf(route))
        return copy(prompts = prompts + (request.rpcId to prompt))
    }

    private fun WebappState.settle(ids: Set<RpcId>): WebappState {
        if (ids.isEmpty()) return this
        return copy(prompts = prompts - ids, settled = (settled - ids + ids).takeLast(WebappState.MAX_RECENT))
    }

    /** Removes routes that no longer reach the server; a prompt left without any is dropped, not settled. */
    private fun WebappState.dropRoutes(gone: (PromptRoute) -> Boolean): WebappState =
        copy(
            prompts = prompts
                .mapValues { (_, prompt) -> prompt.copy(routes = prompt.routes.filterNotTo(mutableSetOf(), gone)) }
                .filterValues { it.routes.isNotEmpty() }
        )

    private fun <K, V> Map<K, V>.newest(max: Int): Map<K, V> =
        if (size <= max) this else entries.toList().takeLast(max).associate { it.key to it.value }

    private fun WebappState.alone(): Pair<WebappState, List<WebappEffect>> = this to emptyList()
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
        val key = (message.opt("key") as? String)?.let(RpcId::fromToken)
        return when (message.opt("t")) {
            "hello" -> WebappInput.Hello(doc)
            "in" -> if (sock == null || frame == null) null
                else (GatewayProtocol.parseMonitorFrame(frame) as? MonitorFrame.AnswerAck)?.let {
                    WebappInput.PageAck(doc, it.rpcId, it.accepted)
                } ?: GatewayProtocol.parseInbound(frame)?.let { WebappInput.Inbound(doc, sock, it) }
            "out" -> if (sock == null || frame == null) null
                else GatewayProtocol.parseResponseId(frame)?.let { WebappInput.Responded(doc, sock, it) }
            "close" -> sock?.let { WebappInput.SocketClosed(doc, it) }
            "sent" -> null // Queued to a socket, not confirmed by the gateway.
            "sendFailed" -> key?.let { WebappInput.SendResult(it, ok = false) }
            else -> null
        }
    }
}
