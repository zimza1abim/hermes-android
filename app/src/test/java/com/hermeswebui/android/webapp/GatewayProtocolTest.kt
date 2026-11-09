package com.hermeswebui.android.webapp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test

class GatewayProtocolTest {
    private fun event(type: String, payload: String, session: String = "s1") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}"""

    private val approvalFrame =
        """{"jsonrpc":"2.0","id":7,"method":"approval","params":{"session_id":"s1","request_id":"r1",""" +
            """"command":"rm -rf build","description":"Delete build output","choices":["once","session","always","deny"],""" +
            """"allow_session":true,"allow_permanent":true,"tool_name":"terminal"}}"""

    private val fixtures: Map<String, GatewayEvent> by lazy {
        mapOf(
            event("message.complete", """{"text":"Done","status":"complete"}""") to
                GatewayEvent.TurnEnded("s1", TurnOutcome.COMPLETE, "Done", null),
            event("message.complete", """{"text":"","status":"error","error":"boom"}""") to
                GatewayEvent.TurnEnded("s1", TurnOutcome.ERROR, null, "boom"),
            event("message.complete", """{"status":"interrupted","failure_reason":"user stop"}""") to
                GatewayEvent.TurnEnded("s1", TurnOutcome.INTERRUPTED, null, "user stop"),
            approvalFrame to GatewayEvent.ApprovalRequested(
                "s1", RpcId.fromToken("7")!!, "r1", "rm -rf build", "Delete build output", "terminal",
                ApprovalChoice.entries,
            ),
            """{"jsonrpc":"2.0","id":"abc","method":"approval","params":{"session_id":"s1","choices":["session","deny"]}}""" to
                GatewayEvent.ApprovalRequested(
                    "s1", RpcId.fromToken("\"abc\"")!!, null, null, null, null,
                    listOf(ApprovalChoice.SESSION, ApprovalChoice.DENY),
                ),
            """{"jsonrpc":"2.0","id":9,"method":"clarify","params":{"session_id":"s1","questions":[{"qid":"q1","question":"Which branch?","multi_select":false}]}}""" to
                GatewayEvent.ClarifyRequested("s1", RpcId.fromToken("9")!!, "Which branch?"),
            event("approval.cancelled", """{"session_id":"s1","reason":"timeout","cancelled_count":1,"request_ids":["r1"]}""") to
                GatewayEvent.ApprovalCancelled("s1", setOf("r1")),
            event("approval.cancelled", """{"reason":"interrupted"}""") to
                GatewayEvent.ApprovalCancelled("s1", emptySet()),
            event("background.complete", """{"task_id":"t1","text":"Report ready"}""") to
                GatewayEvent.BackgroundCompleted("s1", "Report ready"),
            event("session.title", """{"title":"Fix the build"}""") to
                GatewayEvent.SessionTitled("s1", "Fix the build"),
        )
    }

    @Test
    fun `each watched frame parses to its domain event`() {
        fixtures.forEach { (frame, expected) ->
            assertWithMessage(frame).that(GatewayProtocol.parseInbound(frame)).isEqualTo(expected)
        }
    }

    @Test
    fun `every parseable fixture contains a marker the tap forwards on`() {
        fixtures.keys.forEach { frame ->
            assertWithMessage(frame).that(GatewayProtocol.WATCHED_MARKERS.any { frame.contains(it) }).isTrue()
        }
    }

    @Test
    fun `unwatched, malformed and incomplete frames are ignored`() {
        listOf(
            event("message.delta", """{"text":"partial"}"""),
            event("message.complete", """{"status":"exploded"}"""),
            event("session.title", """{}"""),
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","payload":{}}}""",
            """{"jsonrpc":"2.0","method":"approval","params":{"session_id":"s1"}}""",
            """{"jsonrpc":"2.0","id":1.5,"method":"approval","params":{"session_id":"s1"}}""",
            """{"jsonrpc":"2.0","id":2,"method":"clarify","params":{}}""",
            """[1,2,3]""",
            """not json approval""",
            "",
        ).forEach { frame -> assertWithMessage(frame).that(GatewayProtocol.parseInbound(frame)).isNull() }
    }

    @Test
    fun `an approval without choices offers only once and deny`() {
        val parsed = GatewayProtocol.parseInbound(
            """{"jsonrpc":"2.0","id":3,"method":"approval","params":{"session_id":"s1"}}"""
        ) as GatewayEvent.ApprovalRequested
        assertThat(parsed.choices).containsExactly(ApprovalChoice.ONCE, ApprovalChoice.DENY)
    }

    @Test
    fun `only page responses yield a response id`() {
        assertThat(GatewayProtocol.parseResponseId("""{"jsonrpc":"2.0","id":7,"result":{"choice":"once"}}"""))
            .isEqualTo(RpcId.fromToken("7"))
        assertThat(GatewayProtocol.parseResponseId("""{"jsonrpc":"2.0","id":"x","error":{"code":1}}"""))
            .isEqualTo(RpcId.fromToken("\"x\""))
        assertThat(GatewayProtocol.parseResponseId("""{"jsonrpc":"2.0","id":4,"method":"prompt","params":{"result":1}}"""))
            .isNull()
        assertThat(GatewayProtocol.parseResponseId("""{"jsonrpc":"2.0","id":4}""")).isNull()
        assertThat(GatewayProtocol.parseResponseId("garbage \"result\"")).isNull()
    }

    @Test
    fun `the approval answer echoes the raw id token`() {
        listOf("7", "\"abc\"", "\"quote\\\"d\"").forEach { token ->
            val id = RpcId.fromToken(token)!!
            val answer = JSONObject(GatewayProtocol.encodeApprovalAnswer(id, ApprovalChoice.ONCE))
            assertThat(answer.getString("jsonrpc")).isEqualTo("2.0")
            assertThat(RpcId.of(answer.get("id"))).isEqualTo(id)
            assertThat(answer.getJSONObject("result").getString("choice")).isEqualTo("once")
            assertThat(GatewayProtocol.parseResponseId(answer.toString())).isEqualTo(id)
        }
    }

    @Test
    fun `rpc id tokens round trip and reject non-canonical input`() {
        assertThat(RpcId.fromToken("7")?.json).isEqualTo("7")
        assertThat(RpcId.fromToken("\"a|b\"")?.json).isEqualTo("\"a|b\"")
        listOf("07", "1.5", "true", "null", "{}", "\"unterminated", " 7").forEach { token ->
            assertWithMessage(token).that(RpcId.fromToken(token)).isNull()
        }
    }

    @Test
    fun `allow prefers once and falls back to the first non-deny choice`() {
        assertThat(GatewayProtocol.choiceFor(Decision.ALLOW, ApprovalChoice.entries)).isEqualTo(ApprovalChoice.ONCE)
        assertThat(GatewayProtocol.choiceFor(Decision.ALLOW, listOf(ApprovalChoice.DENY, ApprovalChoice.SESSION)))
            .isEqualTo(ApprovalChoice.SESSION)
        assertThat(GatewayProtocol.choiceFor(Decision.ALLOW, listOf(ApprovalChoice.DENY))).isNull()
        assertThat(GatewayProtocol.choiceFor(Decision.DENY, ApprovalChoice.entries)).isEqualTo(ApprovalChoice.DENY)
        assertThat(GatewayProtocol.choiceFor(Decision.DENY, listOf(ApprovalChoice.ONCE))).isNull()
    }
}
