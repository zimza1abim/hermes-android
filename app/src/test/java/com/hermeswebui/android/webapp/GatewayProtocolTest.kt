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
                GatewayEvent.ClarifyRequested("s1", RpcId.fromToken("9")!!, listOf(ClarifyQuestion("q1", "Which branch?"))),
            event("approval.cancelled", """{"session_id":"s1","reason":"timeout","cancelled_count":1,"request_ids":["r1"]}""") to
                GatewayEvent.ApprovalCancelled("s1", setOf("r1")),
            event("approval.cancelled", """{"reason":"interrupted"}""") to
                GatewayEvent.ApprovalCancelled("s1", emptySet()),
            event("background.complete", """{"task_id":"t1","text":"Report ready"}""") to
                GatewayEvent.BackgroundCompleted("s1", "Report ready"),
            event("session.title", """{"title":"Fix the build"}""") to
                GatewayEvent.SessionTitled("s1", "Fix the build"),
            event("session.info", """{"model":"m","tools":{},"skills":{},"cwd":"/w","title":" Fix the build ","running":false,"stored_session_id":"k1"}""") to
                GatewayEvent.SessionInfo("s1", "k1", running = false, title = "Fix the build"),
            event("session.info", """{"running":true}""") to GatewayEvent.SessionInfo("s1", null, running = true, title = null),
            event("message.complete", """{"text":"Done"}""").replace("\"payload\"", "\"seq\":12,\"payload\"") to
                GatewayEvent.TurnEnded("s1", TurnOutcome.COMPLETE, "Done", null, seq = 12),
            event("request.cancel", """{"id":"srq-1","method":"approval","reason":"resolved"}""") to
                GatewayEvent.RequestCancelled("s1", RpcId.fromToken("\"srq-1\"")!!),
        )
    }

    @Test
    fun `answer acknowledgement preserves the request id and server decision`() {
        val frame = """{"jsonrpc":"2.0","method":"hermes.android.answer_ack","params":{"id":"srq-1","accepted":true}}"""
        assertThat(GatewayProtocol.parseMonitorFrame(frame)).isEqualTo(
            MonitorFrame.AnswerAck(RpcId.fromToken("\"srq-1\"")!!, true)
        )
        assertThat(GatewayProtocol.parseMonitorFrame(frame.replace("true", "false"))).isEqualTo(
            MonitorFrame.AnswerAck(RpcId.fromToken("\"srq-1\"")!!, false)
        )
    }

    @Test
    fun `quiet watch fallback notification parses without changing session events`() {
        assertThat(GatewayProtocol.parseMonitorFrame(
            """{"jsonrpc":"2.0","method":"hermes.android.quiet_watch_unavailable","params":{}}"""
        )).isEqualTo(MonitorFrame.QuietWatchFallback)
    }

    @Test
    fun `active sessions supply stored ids and running state`() {
        val result = JSONObject("""{"sessions":[{"id":"r1","session_key":"k1","status":"working","title":"Task"},{"id":"r2","session_key":"k2","status":"idle"},{"id":"invalid","status":"working"}]}""")
        assertThat(GatewayProtocol.parseActiveSessions(result)).containsExactly(
            GatewayEvent.SessionInfo("r1", "k1", true, "Task"),
            GatewayEvent.SessionInfo("r2", "k2", false, null),
        ).inOrder()
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
            event("session.info", """{"model":"m","title":""}"""),
            event("session.info", """{"model":"m"}"""),
            event("request.cancel", """{"method":"approval"}"""),
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
    fun `clarify keeps every question with its id, non-blank choices and selection mode`() {
        val parsed = GatewayProtocol.parseInbound(
            """{"jsonrpc":"2.0","id":"c1","method":"clarify","params":{"session_id":"s1","questions":[""" +
                """{"qid":"q1","question":"Branch?","choices":["main",""," ",3,"dev"]},""" +
                """{"qid":"q2","question":"Perms?","choices":["r","w"],"multi_select":true},""" +
                """{"question":"No id"},7]}}"""
        )
        assertThat(parsed).isEqualTo(
            GatewayEvent.ClarifyRequested(
                "s1", RpcId.fromToken("\"c1\"")!!,
                listOf(
                    ClarifyQuestion("q1", "Branch?", listOf("main", "dev")),
                    ClarifyQuestion("q2", "Perms?", listOf("r", "w"), multiSelect = true),
                    ClarifyQuestion(null, "No id"),
                ),
            )
        )
    }

    @Test
    fun `the clarify answer echoes the raw id and keys the text by question id`() {
        listOf(RpcId.fromToken("9")!!, RpcId.fromToken("\"c-1\"")!!).forEach { id ->
            val frame = GatewayProtocol.encodeClarifyAnswer(id, "q\"1", "say \"hi\"\n")
            val answer = JSONObject(frame)
            assertThat(answer.getString("jsonrpc")).isEqualTo("2.0")
            assertThat(RpcId.of(answer.get("id"))).isEqualTo(id)
            assertThat(answer.getJSONObject("result").getJSONObject("answers").getString("q\"1")).isEqualTo("say \"hi\"\n")
            assertThat(answer.getJSONObject("result").length()).isEqualTo(1)
            assertThat(GatewayProtocol.parseResponseId(frame)).isEqualTo(id)
        }
        assertThat(GatewayProtocol.encodeClarifyAnswer(RpcId.fromToken("9")!!, "q1", "dev"))
            .isEqualTo("""{"jsonrpc":"2.0","id":9,"result":{"answers":{"q1":"dev"}}}""")
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

    @Test
    fun `monitor frames parse to ready, events, capabilities, resume answers and window requests`() {
        assertThat(GatewayProtocol.parseMonitorFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}"""))
            .isEqualTo(MonitorFrame.Ready)
        assertThat(GatewayProtocol.parseMonitorFrame(approvalFrame)).isEqualTo(MonitorFrame.Inbound(fixtures.getValue(approvalFrame)))
        assertThat(GatewayProtocol.parseMonitorFrame("""{"jsonrpc":"2.0","id":1,"result":{"server_requests":["approval"],"declines_not_shown":true}}"""))
            .isEqualTo(MonitorFrame.Capabilities(1, declinesNotShown = true))
        val resumed = """{"jsonrpc":"2.0","id":2,"result":{"session_id":"rt1","session_key":"k1","running":true,""" +
            """"info":{"title":" Fix ","stored_session_id":"k1"},"messages":[],"open_requests":[""" +
            """{"id":"srq-1","method":"approval","params":{"session_id":"rt1","choices":["once","deny"]}},""" +
            """{"id":"srq-2","method":"sudo","params":{"session_id":"rt1"}}]}}"""
        assertThat(GatewayProtocol.parseMonitorFrame(resumed)).isEqualTo(
            MonitorFrame.Resumed(
                2,
                ResumeSnapshot(
                    "rt1", "k1", running = true, title = "Fix",
                    openRequests = listOf(
                        GatewayEvent.ApprovalRequested(
                            "rt1", RpcId.fromToken("\"srq-1\"")!!, null, null, null, null, listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
                        )
                    ),
                ),
            )
        )
        assertThat(GatewayProtocol.parseMonitorFrame("""{"jsonrpc":"2.0","id":3,"error":{"code":4007,"message":"session not found"}}"""))
            .isEqualTo(MonitorFrame.Resumed(3, null))
        assertThat(GatewayProtocol.parseMonitorFrame("""{"jsonrpc":"2.0","id":"srq-9","method":"preview.read","params":{"session_id":"rt1"}}"""))
            .isEqualTo(MonitorFrame.WindowRequest(RpcId.fromToken("\"srq-9\"")!!))
        listOf(
            """{"jsonrpc":"2.0","id":"android-ping-1","result":{"ok":true}}""",
            """{"jsonrpc":"2.0","id":"srq-3","method":"sudo","params":{"session_id":"rt1"}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"rt1","payload":{}}}""",
        ).forEach { frame -> assertWithMessage(frame).that(GatewayProtocol.parseMonitorFrame(frame)).isNull() }
    }

    @Test
    fun `monitor requests encode the stored id and the not-shown decline`() {
        val resume = JSONObject(GatewayProtocol.encodeResume(4, "k\"1")).getJSONObject("params")
        assertThat(resume.getString("session_id")).isEqualTo("k\"1")
        assertThat(resume.getBoolean("lazy")).isTrue()
        assertThat(resume.getBoolean("omit_messages")).isTrue()
        assertThat(resume.getBoolean("inline_images")).isFalse()
        val canonical = JSONObject(GatewayProtocol.adaptForSessionAuthority(GatewayProtocol.encodeResume(4, "k1")))
            .getJSONObject("params")
        assertThat(canonical.has("lazy")).isFalse()
        assertThat(canonical.has("inline_images")).isFalse()
        assertThat(canonical.getBoolean("omit_messages")).isTrue()
        val canonicalResponse = """{"jsonrpc":"2.0","id":5,"result":{"session_id":"r1","stored_session_id":"k1","running":true,"info":{},"open_requests":[]}}"""
        assertThat(GatewayProtocol.parseMonitorFrame(canonicalResponse)).isEqualTo(
            MonitorFrame.Resumed(5, ResumeSnapshot("r1", "k1", true, null, emptyList()))
        )
        val decline = JSONObject(GatewayProtocol.encodeNotShown(RpcId.fromToken("\"srq-9\"")!!))
        assertThat(decline.getString("id")).isEqualTo("srq-9")
        assertThat(decline.getJSONObject("error").getInt("code")).isEqualTo(4404)
    }

    @Test
    fun `the monitor endpoint keeps the server's base path`() {
        assertThat(MonitorEndpoint.from("https://h.example:8443/hermes/?x=1#/s"))
            .isEqualTo(
                MonitorEndpoint(
                    "https://h.example:8443",
                    "https://h.example:8443/hermes/api/webapp/socket-ticket?socket=gateway",
                    "wss://h.example:8443/hermes/api/ws?native_dial=browser",
                )
            )
        assertThat(MonitorEndpoint.from("http://10.0.0.2:9119")?.socketUrl).isEqualTo("ws://10.0.0.2:9119/api/ws?native_dial=browser")
        assertThat(MonitorEndpoint.from("ftp://h")).isNull()
        assertThat(MonitorEndpoint.from("not a url")).isNull()
    }
}
