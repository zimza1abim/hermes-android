package com.hermeswebui.android.webapp

/**
 * The native gateway monitor's connection. Each attempt runs under its own [MonitorState.gen]; a
 * callback from an older attempt is dropped, so a late close or ticket can never act on a newer socket.
 */
sealed interface MonitorPhase {
    /** Not wanted: the app is visible, keep-alive is off or no gateway document is live. */
    data object Idle : MonitorPhase

    /** Minting a one-use socket ticket. [attempt] counts failures since the last ready socket. */
    data class Minting(val attempt: Int) : MonitorPhase

    data class Connecting(val attempt: Int) : MonitorPhase

    /** The socket is open; waiting for `gateway.ready`. */
    data class Attaching(val attempt: Int) : MonitorPhase

    /** Ready and attaching to the watched sessions, one `session.resume` each. */
    data class Live(
        /** Request id of each `session.resume` in flight, to the stored id it attaches. */
        val resuming: Map<Int, String> = emptyMap(),
        /** Stored id to the runtime id its events carry. */
        val attached: Map<String, String> = emptyMap(),
        /** Stored ids the server refused on this socket; retried on the next one. */
        val refused: Set<String> = emptySet(),
        /** The server counts a 4404 decline as one client declining, not as the answer. */
        val declinesNotShown: Boolean = false,
    ) : MonitorPhase

    data class Backoff(val attempt: Int) : MonitorPhase
}

data class MonitorState(
    val gen: Int = 0,
    val phase: MonitorPhase = MonitorPhase.Idle,
    val nextRequestId: Int = 1,
) {
    val live: MonitorPhase.Live? get() = phase as? MonitorPhase.Live

    /** Whether the monitor is attached to [runtimeId], so it delivers that session's events itself. */
    fun covers(runtimeId: String): Boolean = live?.attached?.containsValue(runtimeId) == true
}

/** The connection half of the monitor: phase transitions only. What the frames mean is the reducer's. */
object GatewayMonitorMachine {
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30_000L

    fun retryDelayMs(attempt: Int): Long = (BASE_DELAY_MS shl attempt.coerceIn(0, 5)).coerceAtMost(MAX_DELAY_MS)

    /** Brings the connection to [want], then attaches every session in [watch] not yet attached or refused. */
    fun reconcile(state: MonitorState, want: Boolean, watch: List<String>): Pair<MonitorState, List<WebappEffect>> {
        if (!want) {
            return if (state.phase == MonitorPhase.Idle) state to emptyList()
            else state.copy(gen = state.gen + 1, phase = MonitorPhase.Idle) to listOf(WebappEffect.MonitorClose(state.gen))
        }
        if (state.phase == MonitorPhase.Idle) return mint(state, attempt = 0)
        val live = state.live ?: return state to emptyList()
        val missing = watch.filter { it !in live.attached && it !in live.resuming.values && it !in live.refused }
        if (missing.isEmpty()) return state to emptyList()
        val ids = missing.indices.map { state.nextRequestId + it }
        val sends = missing.zip(ids) { stored, id -> WebappEffect.MonitorSend(state.gen, GatewayProtocol.encodeResume(id, stored)) }
        val next = state.copy(
            phase = live.copy(resuming = live.resuming + ids.zip(missing)),
            nextRequestId = state.nextRequestId + missing.size,
        )
        return next to sends
    }

    fun step(state: MonitorState, input: WebappInput.MonitorInput): Pair<MonitorState, List<WebappEffect>> {
        if (input.gen != state.gen) return state to emptyList()
        val phase = state.phase
        return when (input) {
            is WebappInput.TicketMinted -> if (phase !is MonitorPhase.Minting) state to emptyList()
                else input.ticket?.let { state.copy(phase = MonitorPhase.Connecting(phase.attempt)) to listOf(WebappEffect.MonitorDial(state.gen, it)) }
                    ?: backoff(state, phase.attempt)
            is WebappInput.MonitorOpened -> if (phase !is MonitorPhase.Connecting) state to emptyList()
                else state.copy(phase = MonitorPhase.Attaching(phase.attempt)) to emptyList()
            is WebappInput.MonitorClosed -> when (phase) {
                is MonitorPhase.Connecting -> backoff(state, phase.attempt)
                is MonitorPhase.Attaching -> backoff(state, phase.attempt)
                is MonitorPhase.Live -> backoff(state, 0)
                else -> state to emptyList()
            }
            is WebappInput.RetryDue -> if (phase !is MonitorPhase.Backoff) state to emptyList() else mint(state, phase.attempt + 1)
            is WebappInput.MonitorReceived -> received(state, input.frame)
        }
    }

    /** A new default network: dial again now, on it, instead of waiting out a backoff or a dead socket's timeout. */
    fun networkChanged(state: MonitorState): Pair<MonitorState, List<WebappEffect>> = when (val phase = state.phase) {
        MonitorPhase.Idle -> state to emptyList()
        is MonitorPhase.Backoff -> mint(state, phase.attempt)
        else -> mint(state, attempt = 0).let { (next, effects) -> next to listOf(WebappEffect.MonitorClose(state.gen)) + effects }
    }

    /** Records a `session.resume` answer. Returns the stored id it attached or refused, or null for an unknown request. */
    fun resumed(state: MonitorState, requestId: Int, snapshot: ResumeSnapshot?): Pair<MonitorState, String?> {
        val live = state.live ?: return state to null
        val stored = live.resuming[requestId] ?: return state to null
        val next = if (snapshot == null) live.copy(resuming = live.resuming - requestId, refused = live.refused + stored)
        else live.copy(resuming = live.resuming - requestId, attached = live.attached + (stored to snapshot.runtimeId))
        return state.copy(phase = next) to stored
    }

    private fun received(state: MonitorState, frame: MonitorFrame): Pair<MonitorState, List<WebappEffect>> {
        val phase = state.phase
        return when (frame) {
            MonitorFrame.Ready -> if (phase !is MonitorPhase.Attaching) state to emptyList()
                else state.copy(phase = MonitorPhase.Live(), nextRequestId = state.nextRequestId + 1) to
                    listOf(WebappEffect.MonitorSend(state.gen, GatewayProtocol.encodeCapabilities(state.nextRequestId)))
            is MonitorFrame.Capabilities -> if (phase !is MonitorPhase.Live) state to emptyList()
                else state.copy(phase = phase.copy(declinesNotShown = frame.declinesNotShown)) to emptyList()
            is MonitorFrame.WindowRequest ->
                state to listOfNotNull(
                    WebappEffect.MonitorSend(state.gen, GatewayProtocol.encodeNotShown(frame.rpcId))
                        .takeIf { (phase as? MonitorPhase.Live)?.declinesNotShown == true }
                )
            is MonitorFrame.Inbound, is MonitorFrame.Resumed -> state to emptyList()
        }
    }

    private fun mint(state: MonitorState, attempt: Int): Pair<MonitorState, List<WebappEffect>> {
        val gen = state.gen + 1
        return state.copy(gen = gen, phase = MonitorPhase.Minting(attempt)) to listOf(WebappEffect.MonitorMint(gen))
    }

    private fun backoff(state: MonitorState, attempt: Int): Pair<MonitorState, List<WebappEffect>> =
        state.copy(phase = MonitorPhase.Backoff(attempt)) to
            listOf(WebappEffect.MonitorRetryAfter(state.gen, retryDelayMs(attempt)))
}
