package com.hermeswebui.android.webapp

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webapp.WebappEffect.Cancel
import com.hermeswebui.android.webapp.WebappEffect.KeepAlive
import com.hermeswebui.android.webapp.WebappEffect.Post
import com.hermeswebui.android.webapp.WebappEffect.Send
import com.hermeswebui.android.webapp.WebappInput.DocumentGone
import com.hermeswebui.android.webapp.WebappInput.Hello
import com.hermeswebui.android.webapp.WebappInput.Inbound
import com.hermeswebui.android.webapp.WebappInput.Responded
import com.hermeswebui.android.webapp.WebappInput.SendResult
import com.hermeswebui.android.webapp.WebappInput.SettingsChanged
import com.hermeswebui.android.webapp.WebappInput.SocketClosed
import com.hermeswebui.android.webapp.WebappInput.TrayAnswer
import com.hermeswebui.android.webapp.WebappInput.Visibility
import org.junit.Test

class WebappAlertReducerTest {
    private val doc = DocId("doc-a")
    private val approvalId = RpcId.fromToken("7")!!
    private val approvalKey = PromptKey(1, approvalId)
    private val approval = GatewayEvent.ApprovalRequested(
        "s1", approvalId, "r1", "rm -rf build", "Delete build output", "terminal", ApprovalChoice.entries,
    )
    private val clarifyKey = PromptKey(1, RpcId.fromToken("9")!!)
    private val clarify = GatewayEvent.ClarifyRequested("s1", clarifyKey.rpcId, "Which branch?")
    private val completed = GatewayEvent.TurnEnded("s1", TurnOutcome.COMPLETE, "All done", null)

    /** Runs inputs in order and returns the final state plus the effects of the last input. */
    private fun run(
        vararg inputs: WebappInput,
        from: WebappState = WebappState(WebappBackgroundSettings()),
    ): Pair<WebappState, List<WebappEffect>> {
        var state = from
        var last = emptyList<WebappEffect>()
        inputs.forEach { input -> WebappAlertReducer.reduce(state, input).let { (next, effects) -> state = next; last = effects } }
        return state to last
    }

    private fun effectsOf(vararg inputs: WebappInput) = run(*inputs).second

    private fun allEffects(vararg inputs: WebappInput): List<WebappEffect> {
        var state = WebappState(WebappBackgroundSettings())
        return inputs.flatMap { input -> WebappAlertReducer.reduce(state, input).also { state = it.first }.second }
    }

    private val hidden = arrayOf(Hello(doc), Visibility(false))

    @Test
    fun `keep-alive runs only while enabled, hidden and a gateway document is live`() {
        assertThat(effectsOf(Visibility(false))).isEmpty()
        assertThat(effectsOf(Hello(doc), Visibility(false))).containsExactly(KeepAlive(true))
        assertThat(effectsOf(Visibility(false), Hello(doc))).containsExactly(KeepAlive(true))
        assertThat(effectsOf(*hidden, Visibility(true))).containsExactly(KeepAlive(false))
        assertThat(effectsOf(*hidden, DocumentGone)).containsExactly(KeepAlive(false))
        assertThat(effectsOf(*hidden, SettingsChanged(WebappBackgroundSettings(keepAlive = false))))
            .containsExactly(KeepAlive(false))
        assertThat(
            run(Hello(doc), Visibility(false), from = WebappState(WebappBackgroundSettings(keepAlive = false))).second
        ).isEmpty()
    }

    @Test
    fun `keep-alive is emitted only on transitions`() {
        assertThat(effectsOf(*hidden, Hello(doc))).isEmpty()
        assertThat(effectsOf(*hidden, Hello(DocId("doc-b")))).isEmpty()
        assertThat(effectsOf(*hidden, Visibility(false))).isEmpty()
        assertThat(allEffects(*hidden, Visibility(true), Visibility(false)).filterIsInstance<KeepAlive>())
            .containsExactly(KeepAlive(true), KeepAlive(false), KeepAlive(true)).inOrder()
    }

    @Test
    fun `one-shot alerts post only while hidden and enabled`() {
        assertThat(effectsOf(Hello(doc), Inbound(doc, 1, completed))).isEmpty()
        val posted = effectsOf(*hidden, Inbound(doc, 1, completed)).single() as Post
        assertThat(posted.alert).isEqualTo(Alert(GatewayAlertKind.TURN_COMPLETE, "s1", "Task finished", "All done", null))
        val disabled = WebappBackgroundSettings(disabled = setOf(GatewayAlertKind.TURN_COMPLETE))
        assertThat(effectsOf(*hidden, SettingsChanged(disabled), Inbound(doc, 1, completed))).isEmpty()
    }

    @Test
    fun `failed and interrupted turns and background tasks use their own kinds`() {
        val failed = GatewayEvent.TurnEnded("s1", TurnOutcome.ERROR, "partial", "boom")
        val stopped = GatewayEvent.TurnEnded("s1", TurnOutcome.INTERRUPTED, null, null)
        val background = GatewayEvent.BackgroundCompleted("s2", "Report ready")
        fun alertFor(event: GatewayEvent) = (effectsOf(*hidden, Inbound(doc, 1, event)).single() as Post).alert
        assertThat(alertFor(failed)).isEqualTo(Alert(GatewayAlertKind.TURN_FAILED, "s1", "Task failed", "boom", null))
        assertThat(alertFor(stopped)).isEqualTo(Alert(GatewayAlertKind.TURN_FAILED, "s1", "Task stopped", null, null))
        assertThat(alertFor(background))
            .isEqualTo(Alert(GatewayAlertKind.BACKGROUND_COMPLETE, "s2", "Background task finished", "Report ready", null))
    }

    @Test
    fun `preview off strips the body and the session title`() {
        val noPreview = SettingsChanged(WebappBackgroundSettings(showPreview = false))
        val titled = Inbound(doc, 1, GatewayEvent.SessionTitled("s1", "Fix the build"))
        val turn = (effectsOf(*hidden, titled, noPreview, Inbound(doc, 1, completed)).single() as Post).alert
        assertThat(turn.title).isEqualTo("Task finished")
        assertThat(turn.body).isNull()
        val prompt = (effectsOf(*hidden, titled, noPreview, Inbound(doc, 1, approval)).single() as Post).alert
        assertThat(prompt.title).isEqualTo("Approval needed")
        assertThat(prompt.body).isNull()

        val withTitle = (effectsOf(*hidden, titled, Inbound(doc, 1, completed)).single() as Post).alert
        assertThat(withTitle.title).isEqualTo("Task finished · Fix the build")
    }

    @Test
    fun `turning preview off re-posts open prompts without their detail`() {
        val effects = effectsOf(*hidden, Inbound(doc, 1, approval), SettingsChanged(WebappBackgroundSettings(showPreview = false)))
        assertThat((effects.single() as Post).alert.body).isNull()
    }

    @Test
    fun `an approval shows with an answer target while hidden`() {
        val alert = (effectsOf(*hidden, Inbound(doc, 1, approval)).single() as Post).alert
        assertThat(alert).isEqualTo(
            Alert(GatewayAlertKind.APPROVAL, approvalKey.encode(), "Approval needed", "rm -rf build", AnswerTarget(doc, approvalKey))
        )
        val question = (effectsOf(*hidden, Inbound(doc, 1, clarify)).single() as Post).alert
        assertThat(question).isEqualTo(
            Alert(GatewayAlertKind.CLARIFY, clarifyKey.encode(), "Hermes has a question", "Which branch?", null)
        )
    }

    @Test
    fun `return cancels open prompts and leaving again re-posts them`() {
        val open = arrayOf(*hidden, Inbound(doc, 1, approval))
        assertThat(effectsOf(*open, Visibility(true)))
            .containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()), KeepAlive(false))
        val again = effectsOf(*open, Visibility(true), Visibility(false))
        assertThat(again.filterIsInstance<Post>().single().alert.answer).isEqualTo(AnswerTarget(doc, approvalKey))
    }

    @Test
    fun `a prompt that arrives while visible appears once the app is hidden`() {
        assertThat(effectsOf(Hello(doc), Inbound(doc, 1, approval))).isEmpty()
        assertThat(effectsOf(Hello(doc), Inbound(doc, 1, approval), Visibility(false)).filterIsInstance<Post>()).hasSize(1)
    }

    @Test
    fun `tray allow sends the encoded answer and hides the prompt`() {
        val effects = effectsOf(*hidden, Inbound(doc, 1, approval), TrayAnswer(AnswerTarget(doc, approvalKey), Decision.ALLOW))
        assertThat(effects).containsExactly(
            Send(doc, 1, approvalKey, GatewayProtocol.encodeApprovalAnswer(approvalId, ApprovalChoice.ONCE)),
            Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()),
        ).inOrder()
    }

    @Test
    fun `a failed send re-posts the prompt and a successful one forgets it`() {
        val sending = arrayOf(*hidden, Inbound(doc, 1, approval), TrayAnswer(AnswerTarget(doc, approvalKey), Decision.DENY))
        assertThat(effectsOf(*sending, SendResult(doc, approvalKey, ok = false)).single()).isInstanceOf(Post::class.java)
        val (state, effects) = run(*sending, SendResult(doc, approvalKey, ok = true))
        assertThat(effects).isEmpty()
        assertThat(state.doc!!.prompts).isEmpty()
    }

    @Test
    fun `a prompt already sending is not sent twice`() {
        val target = AnswerTarget(doc, approvalKey)
        assertThat(effectsOf(*hidden, Inbound(doc, 1, approval), TrayAnswer(target, Decision.ALLOW), TrayAnswer(target, Decision.DENY)))
            .isEmpty()
    }

    @Test
    fun `clarify prompts cannot be answered from the tray`() {
        assertThat(effectsOf(*hidden, Inbound(doc, 1, clarify), TrayAnswer(AnswerTarget(doc, clarifyKey), Decision.ALLOW)))
            .isEmpty()
    }

    @Test
    fun `the page answering a prompt itself cancels it`() {
        assertThat(effectsOf(*hidden, Inbound(doc, 1, approval), Responded(doc, 1, approvalId)))
            .containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()))
        assertThat(effectsOf(*hidden, Inbound(doc, 1, approval), Responded(doc, 2, approvalId))).isEmpty()
    }

    @Test
    fun `approval cancelled withdraws matching approvals only`() {
        val other = GatewayEvent.ApprovalRequested("s1", RpcId.fromToken("8")!!, "r2", "ls", null, null, ApprovalChoice.entries)
        val otherKey = PromptKey(1, other.rpcId)
        val open = arrayOf(*hidden, Inbound(doc, 1, approval), Inbound(doc, 1, other), Inbound(doc, 1, clarify))
        assertThat(effectsOf(*open, Inbound(doc, 1, GatewayEvent.ApprovalCancelled("s1", setOf("r1")))))
            .containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()))
        assertThat(effectsOf(*open, Inbound(doc, 1, GatewayEvent.ApprovalCancelled("s1", emptySet()))))
            .containsExactly(
                Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()),
                Cancel(GatewayAlertKind.APPROVAL, otherKey.encode()),
            )
        assertThat(effectsOf(*open, Inbound(doc, 1, GatewayEvent.ApprovalCancelled("s9", emptySet())))).isEmpty()
    }

    @Test
    fun `a closed socket cancels its prompts`() {
        assertThat(effectsOf(*hidden, Inbound(doc, 1, approval), Inbound(doc, 2, clarify), SocketClosed(doc, 1)))
            .containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()))
    }

    @Test
    fun `a new document cancels the old prompts and stale answers are dropped`() {
        val docB = DocId("doc-b")
        val open = arrayOf(*hidden, Inbound(doc, 1, approval))
        assertThat(effectsOf(*open, Hello(docB))).containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()))
        assertThat(effectsOf(*open, Hello(docB), TrayAnswer(AnswerTarget(doc, approvalKey), Decision.ALLOW))).isEmpty()
        assertThat(effectsOf(*open, Hello(docB), Inbound(doc, 1, completed))).isEmpty()
        assertThat(effectsOf(*open, DocumentGone))
            .containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()), KeepAlive(false))
        assertThat(effectsOf(*open, DocumentGone, Inbound(doc, 1, approval))).isEmpty()
    }

    @Test
    fun `disabling a kind cancels its live prompts and enabling restores them`() {
        val off = SettingsChanged(WebappBackgroundSettings(disabled = setOf(GatewayAlertKind.APPROVAL)))
        assertThat(effectsOf(*hidden, Inbound(doc, 1, approval), off))
            .containsExactly(Cancel(GatewayAlertKind.APPROVAL, approvalKey.encode()))
        assertThat(effectsOf(*hidden, Inbound(doc, 1, approval), off, SettingsChanged(WebappBackgroundSettings())).single())
            .isInstanceOf(Post::class.java)
    }

    @Test
    fun `session titles stay bounded`() {
        val inputs = (1..40).map { Inbound(doc, 1, GatewayEvent.SessionTitled("s$it", "t$it")) }
        val state = run(Hello(doc), *inputs.toTypedArray()).first
        assertThat(state.doc!!.titles).hasSize(GatewayDocument.MAX_TITLES)
        assertThat(state.doc!!.titles).containsKey("s40")
        assertThat(state.doc!!.titles).doesNotContainKey("s1")
    }

    @Test
    fun `notification ids stay inside each kind's range`() {
        GatewayAlertKind.entries.forEach { kind ->
            listOf("s1", "1|7", "", "x".repeat(200)).forEach { key ->
                assertThat(kind.notificationId(key) ushr 24).isEqualTo(kind.ordinal + 1)
            }
        }
        assertThat(GatewayAlertKind.entries.map { it.channelId }.toSet()).hasSize(GatewayAlertKind.entries.size)
        assertThat(GatewayAlertKind.entries.map { it.storageKey }.toSet()).hasSize(GatewayAlertKind.entries.size)
    }

    @Test
    fun `disabled kinds round trip through storage and unknown keys are ignored`() {
        val settings = WebappBackgroundSettings(disabled = setOf(GatewayAlertKind.CLARIFY, GatewayAlertKind.TURN_FAILED))
        assertThat(WebappBackgroundSettings.disabledFromStorage(settings.disabledStorageKeys())).isEqualTo(settings.disabled)
        assertThat(WebappBackgroundSettings.disabledFromStorage(setOf("approval", "from_the_future")))
            .containsExactly(GatewayAlertKind.APPROVAL)
        assertThat(WebappBackgroundSettings.disabledFromStorage(null)).isEmpty()
    }

    @Test
    fun `prompt keys round trip and reject malformed text`() {
        listOf(approvalKey, PromptKey(3, RpcId.fromToken("\"a|b\"")!!)).forEach {
            assertThat(PromptKey.decode(it.encode())).isEqualTo(it)
        }
        listOf("", "|7", "0|7", "x|7", "1|", "1|1.5", "17").forEach { assertThat(PromptKey.decode(it)).isNull() }
    }
}
