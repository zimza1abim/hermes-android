package com.hermeswebui.android.webapp

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webapp.WebappEffect.Answer
import com.hermeswebui.android.webapp.WebappEffect.Cancel
import com.hermeswebui.android.webapp.WebappEffect.MonitorClose
import com.hermeswebui.android.webapp.WebappEffect.MonitorDial
import com.hermeswebui.android.webapp.WebappEffect.MonitorMint
import com.hermeswebui.android.webapp.WebappEffect.MonitorRetryAfter
import com.hermeswebui.android.webapp.WebappEffect.MonitorSend
import com.hermeswebui.android.webapp.WebappEffect.Post
import com.hermeswebui.android.webapp.WebappInput.Hello
import com.hermeswebui.android.webapp.WebappInput.Inbound
import com.hermeswebui.android.webapp.WebappInput.MonitorClosed
import com.hermeswebui.android.webapp.WebappInput.MonitorOpened
import com.hermeswebui.android.webapp.WebappInput.MonitorReceived
import com.hermeswebui.android.webapp.WebappInput.NetworkChanged
import com.hermeswebui.android.webapp.WebappInput.RetryDue
import com.hermeswebui.android.webapp.WebappInput.SendResult
import com.hermeswebui.android.webapp.WebappInput.TicketMinted
import com.hermeswebui.android.webapp.WebappInput.TrayAnswer
import com.hermeswebui.android.webapp.WebappInput.Visibility
import org.junit.Test

class GatewayMonitorTest {
    private val doc = DocId("doc-a")
    private val approvalId = RpcId.fromToken("\"srq-1\"")!!
    private val approval = GatewayEvent.ApprovalRequested("rt1", approvalId, "r1", "rm -rf build", null, null, ApprovalChoice.entries)
    private val runningInfo = GatewayEvent.SessionInfo("rt1", "k1", running = true, title = "Fix the build")
    private val idleInfo = GatewayEvent.SessionInfo("rt2", "k2", running = false, title = null)

    private fun reduceAll(inputs: List<WebappInput>, from: WebappState = WebappState(WebappBackgroundSettings())):
        Pair<WebappState, List<List<WebappEffect>>> {
        var state = from
        val effects = inputs.map { input -> WebappAlertReducer.reduce(state, input).let { (next, out) -> state = next; out } }
        return state to effects
    }

    private fun last(vararg inputs: WebappInput) = reduceAll(inputs.toList()).second.last()
    private fun stateAfter(vararg inputs: WebappInput) = reduceAll(inputs.toList()).first

    /** Hidden with a live document, the page having reported a running chat, monitor ticket minted and socket ready. */
    private val live = arrayOf(
        Hello(doc), Inbound(doc, 1, runningInfo), Inbound(doc, 1, idleInfo), Visibility(false),
        TicketMinted(1, "t"), MonitorOpened(1), MonitorReceived(1, MonitorFrame.Ready),
    )

    private fun snapshot(vararg open: GatewayEvent.PromptRequest, running: Boolean = true) =
        ResumeSnapshot("rt1", "k1", running, "Fix the build", open.toList())

    @Test
    fun `the monitor mints, dials, attaches and asks for capabilities in order`() {
        val (state, effects) = reduceAll(live.toList())
        assertThat(effects[3]).contains(MonitorMint(1))
        assertThat(effects[4]).containsExactly(MonitorDial(1, "t"))
        assertThat(effects[5]).isEmpty()
        assertThat(effects[6]).containsExactly(
            MonitorSend(1, GatewayProtocol.encodeCapabilities(1)),
            MonitorSend(1, GatewayProtocol.encodeResume(2, "k1")),
            MonitorSend(1, GatewayProtocol.encodeResume(3, "k2")),
        ).inOrder()
        assertThat(state.monitor.live!!.resuming).containsExactly(2, "k1", 3, "k2")
    }

    @Test
    fun `failures back off with growing delays and a ready socket resets the count`() {
        val minting = arrayOf(Hello(doc), Visibility(false))
        assertThat(last(*minting, TicketMinted(1, null))).containsExactly(MonitorRetryAfter(1, 1_000))
        assertThat(last(*minting, TicketMinted(1, null), RetryDue(1))).containsExactly(MonitorMint(2))
        assertThat(last(*minting, TicketMinted(1, null), RetryDue(1), TicketMinted(2, null))).containsExactly(MonitorRetryAfter(2, 2_000))
        assertThat(last(*minting, TicketMinted(1, "t"), MonitorClosed(1))).containsExactly(MonitorRetryAfter(1, 1_000))
        assertThat(last(*live, MonitorClosed(1))).containsExactly(MonitorRetryAfter(1, 1_000))
        assertThat((1..10).map(GatewayMonitorMachine::retryDelayMs).last()).isEqualTo(30_000)
    }

    @Test
    fun `a session leaving the watch set rotates the shared socket`() {
        val machine = MonitorState(gen = 4, phase = MonitorPhase.Live(attached = mapOf("a" to "ra", "b" to "rb")))
        val (next, effects) = GatewayMonitorMachine.reconcile(machine, true, listOf("a"))
        assertThat(next.phase).isEqualTo(MonitorPhase.Minting(0))
        assertThat(effects).containsExactly(MonitorClose(4), MonitorMint(5)).inOrder()
        assertThat(GatewayMonitorMachine.reconcile(machine, true, listOf("a"), allowRotation = false).second).isEmpty()
    }

    @Test
    fun `quiet watch fallback warns only for the current live monitor`() {
        assertThat(last(*live, MonitorReceived(1, MonitorFrame.QuietWatchFallback))
            .filterIsInstance<WebappEffect.QuietWatchFallback>()).hasSize(1)
        assertThat(last(*live, MonitorReceived(0, MonitorFrame.QuietWatchFallback))).isEmpty()
    }

    @Test
    fun `callbacks from an older attempt are dropped`() {
        val retried = arrayOf(Hello(doc), Visibility(false), TicketMinted(1, null), RetryDue(1))
        assertThat(last(*retried, TicketMinted(1, "stale"))).isEmpty()
        assertThat(last(*retried, RetryDue(1))).isEmpty()
        assertThat(last(*live, MonitorClosed(0))).isEmpty()
        assertThat(stateAfter(*live, MonitorReceived(0, MonitorFrame.Inbound(approval))).prompts).isEmpty()
    }

    @Test
    fun `a network change redials at once`() {
        assertThat(last(*live, NetworkChanged)).containsExactly(MonitorClose(1), MonitorMint(2)).inOrder()
        val backingOff = arrayOf(Hello(doc), Visibility(false), TicketMinted(1, null))
        assertThat(last(*backingOff, NetworkChanged)).containsExactly(MonitorMint(2))
        assertThat(last(Hello(doc), NetworkChanged)).isEmpty()
    }

    @Test
    fun `returning to the app closes the monitor and forgets what only it delivered`() {
        val attached = arrayOf(*live, MonitorReceived(1, MonitorFrame.Resumed(2, snapshot(approval))))
        val (state, effects) = reduceAll(listOf(*attached, Visibility(true)))
        assertThat(effects.last()).contains(MonitorClose(1))
        assertThat(state.monitor.phase).isEqualTo(MonitorPhase.Idle)
        assertThat(state.prompts).isEmpty()
    }

    @Test
    fun `the watch set is the page's chat, running chats and chats with open prompts`() {
        val base = WebappState(WebappBackgroundSettings())
        assertThat(WebappAlertReducer.watchSet(stateAfter(Hello(doc), Inbound(doc, 1, runningInfo), Inbound(doc, 1, idleInfo))))
            .containsExactly("k1", "k2")
        val other = GatewayEvent.SessionInfo("rt3", "k3", running = false, title = null)
        val notCurrent = stateAfter(Hello(doc), Inbound(doc, 1, other), Inbound(doc, 1, idleInfo))
        assertThat(WebappAlertReducer.watchSet(notCurrent)).containsExactly("k2")
        val prompted = stateAfter(
            Hello(doc), Inbound(doc, 1, other), Inbound(doc, 1, idleInfo),
            Inbound(doc, 1, GatewayEvent.ApprovalRequested("rt3", approvalId, null, null, null, null, ApprovalChoice.entries)),
        )
        assertThat(WebappAlertReducer.watchSet(prompted)).containsExactly("k3", "k2").inOrder()
        assertThat(WebappAlertReducer.watchSet(stateAfter(Hello(doc), Inbound(doc, 1, GatewayEvent.SessionTitled("rt9", "No id"))))).isEmpty()
        assertThat(WebappAlertReducer.watchSet(base)).isEmpty()
        val monitorInfo = stateAfter(*live, MonitorReceived(1, MonitorFrame.Inbound(GatewayEvent.SessionInfo("rt4", "k4", false, null))))
        assertThat(monitorInfo.pageSession).isEqualTo("rt2")
    }

    @Test
    fun `a refused resume is not retried on the same socket`() {
        val (state, effects) = reduceAll(listOf(*live, MonitorReceived(1, MonitorFrame.Resumed(2, null)), Inbound(doc, 1, runningInfo)))
        assertThat(state.monitor.live!!.refused).containsExactly("k1")
        assertThat(effects.last().filterIsInstance<MonitorSend>()).isEmpty()
    }

    @Test
    fun `the attach snapshot opens the session's waiting prompts and they post`() {
        val effects = last(*live, MonitorReceived(1, MonitorFrame.Resumed(2, snapshot(approval))))
        val alert = (effects.single() as Post).alert
        assertThat(alert.key).isEqualTo(approvalId.json)
        assertThat(alert.title).isEqualTo("Fix the build")
    }

    @Test
    fun `a prompt from both sockets alerts once and either socket's settlement clears it`() {
        val viaMonitor = MonitorReceived(1, MonitorFrame.Inbound(approval))
        val both = reduceAll(listOf(*live, Inbound(doc, 1, approval), viaMonitor))
        assertThat(both.second.dropLast(1).last().filterIsInstance<Post>()).hasSize(1)
        assertThat(both.second.last()).isEmpty()
        assertThat(both.first.prompts.getValue(approvalId).routes).containsExactly(PromptRoute.Page(1), PromptRoute.Monitor)

        val cancel = MonitorReceived(1, MonitorFrame.Inbound(GatewayEvent.RequestCancelled("rt1", approvalId)))
        assertThat(last(*live, Inbound(doc, 1, approval), cancel)).containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalId.json))
        assertThat(last(*live, viaMonitor, cancel, Inbound(doc, 1, approval))).isEmpty()
    }

    @Test
    fun `the snapshot drops prompts only the monitor was keeping`() {
        val other = GatewayEvent.ApprovalRequested("rt1", RpcId.fromToken("\"srq-2\"")!!, null, "ls", null, null, ApprovalChoice.entries)
        val attached = arrayOf(*live, MonitorReceived(1, MonitorFrame.Resumed(2, snapshot(approval))), Inbound(doc, 1, other))
        val reconnected = arrayOf(*attached, MonitorClosed(1), RetryDue(1), TicketMinted(2, "t2"), MonitorOpened(2), MonitorReceived(2, MonitorFrame.Ready))
        val state = stateAfter(*reconnected, MonitorReceived(2, MonitorFrame.Resumed(5, snapshot())))
        assertThat(state.prompts.keys).containsExactly(other.rpcId)
    }

    @Test
    fun `a tray answer goes on the monitor when it delivered the prompt, else on the page`() {
        val viaMonitor = MonitorReceived(1, MonitorFrame.Inbound(approval))
        val allow = TrayAnswer(AnswerTarget(approvalId), TrayReply.Decide(Decision.ALLOW))
        val frame = GatewayProtocol.encodeApprovalAnswer(approvalId, ApprovalChoice.ONCE)
        assertThat(last(*live, Inbound(doc, 1, approval), viaMonitor, allow).first()).isEqualTo(Answer(approvalId, frame, AnswerRoute.Monitor(1)))
        assertThat(last(*live, Inbound(doc, 2, approval), allow).filterIsInstance<Answer>().single().via).isEqualTo(AnswerRoute.Page(doc, 2))
        assertThat(last(*live, viaMonitor, MonitorClosed(1), allow).filterIsInstance<Post>().single().alert.body).contains("Not sent")
        val pending = stateAfter(*live, viaMonitor, allow)
        assertThat(pending.prompts[approvalId]?.sending).isTrue()
        val ack = MonitorReceived(1, MonitorFrame.AnswerAck(approvalId, true))
        assertThat(stateAfter(*live, viaMonitor, allow, ack, viaMonitor).prompts).isEmpty()
    }

    @Test
    fun `acknowledgement controls whether a submitted prompt is removed`() {
        val fromMonitor = MonitorReceived(1, MonitorFrame.Inbound(approval))
        val allow = TrayAnswer(AnswerTarget(approvalId), TrayReply.Decide(Decision.ALLOW))
        val beforeAck = stateAfter(*live, fromMonitor, allow)
        assertThat(beforeAck.prompts[approvalId]?.sending).isTrue()
        assertThat(WebappAlertReducer.promptAlerts(beforeAck)).isNotEmpty()
        val rejected = MonitorReceived(1, MonitorFrame.AnswerAck(approvalId, false))
        val rejectedState = stateAfter(*live, fromMonitor, allow, rejected)
        assertThat(rejectedState.prompts[approvalId]?.answerFailed).isTrue()
        val uncertain = WebappAlertReducer.promptAlerts(rejectedState).values.single()
        assertThat(uncertain.body).contains("may have been sent")
        assertThat(uncertain.actions).isNull()
        assertThat(WebappAlertReducer.reduce(rejectedState, allow).second.filterIsInstance<Answer>()).isEmpty()
        val accepted = MonitorReceived(1, MonitorFrame.AnswerAck(approvalId, true))
        assertThat(stateAfter(*live, fromMonitor, allow, accepted).prompts).isEmpty()
    }

    @Test
    fun `a finished turn alerts once across the hand-off`() {
        val done = GatewayEvent.TurnEnded("rt1", TurnOutcome.COMPLETE, "All done", null, seq = 41)
        val attached = arrayOf(*live, MonitorReceived(1, MonitorFrame.Resumed(2, snapshot())))
        val fromMonitor = MonitorReceived(1, MonitorFrame.Inbound(done))
        assertThat(last(*attached, fromMonitor).filterIsInstance<Post>()).hasSize(1)
        assertThat(last(*attached, fromMonitor, Inbound(doc, 1, done))).isEmpty()
        assertThat(last(*attached, Inbound(doc, 1, done), fromMonitor)).isEmpty()
        assertThat(last(*attached, fromMonitor, MonitorClosed(1), Inbound(doc, 1, done))).isEmpty()
        assertThat(last(*attached, fromMonitor, Inbound(doc, 1, done.copy(seq = 42))).filterIsInstance<Post>()).hasSize(1)
    }

    @Test
    fun `without a seq the page's copy is dropped only for sessions the monitor covers`() {
        val done = GatewayEvent.TurnEnded("rt1", TurnOutcome.COMPLETE, "All done", null)
        val attached = arrayOf(*live, MonitorReceived(1, MonitorFrame.Resumed(2, snapshot())))
        assertThat(last(*attached, Inbound(doc, 1, done))).isEmpty()
        assertThat(last(*live, Inbound(doc, 1, done)).filterIsInstance<Post>()).hasSize(1)
        assertThat(last(*attached, Inbound(doc, 1, done.copy(sessionId = "rt2"))).filterIsInstance<Post>()).hasSize(1)
    }

    @Test
    fun `window-owned requests are declined only when the server counts declines`() {
        val window = MonitorReceived(1, MonitorFrame.WindowRequest(approvalId))
        assertThat(last(*live, window)).isEmpty()
        assertThat(last(*live, MonitorReceived(1, MonitorFrame.Capabilities(1, declinesNotShown = true)), window))
            .containsExactly(MonitorSend(1, GatewayProtocol.encodeNotShown(approvalId)))
    }
}
