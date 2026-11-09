package com.hermeswebui.android.webapp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONObject
import org.junit.Test

class WebappBridgeEnvelopeTest {
    private val doc = DocId("lx2k-abc")
    private val complete =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"s1","payload":{"text":"Done"}}}"""

    private fun envelope(vararg fields: Pair<String, Any>): String =
        JSONObject().put("doc", doc.value).apply { fields.forEach { (k, v) -> put(k, v) } }.toString()

    @Test
    fun `each envelope type parses to its input`() {
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "hello"))).isEqualTo(WebappInput.Hello(doc))
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "in", "sock" to 2, "data" to complete)))
            .isEqualTo(WebappInput.Inbound(doc, 2, GatewayEvent.TurnEnded("s1", TurnOutcome.COMPLETE, "Done", null)))
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "out", "sock" to 2, "data" to """{"id":7,"result":{}}""")))
            .isEqualTo(WebappInput.Responded(doc, 2, RpcId.fromToken("7")!!))
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "close", "sock" to 2)))
            .isEqualTo(WebappInput.SocketClosed(doc, 2))
        val id = RpcId.fromToken("\"srq-1\"")!!
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "sent", "sock" to 2, "key" to id.json)))
            .isNull()
        val ack = """{"method":"hermes.android.answer_ack","params":{"id":"srq-1","accepted":true}}"""
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "in", "sock" to 2, "data" to ack)))
            .isEqualTo(WebappInput.PageAck(doc, id, true))
        assertThat(WebappBridgeEnvelope.parse(envelope("t" to "sendFailed", "sock" to 2, "key" to id.json)))
            .isEqualTo(WebappInput.SendResult(id, ok = false))
    }

    @Test
    fun `malformed envelopes are rejected`() {
        listOf(
            null,
            "",
            "not json",
            """{"t":"hello"}""",
            """{"t":"hello","doc":""}""",
            """{"t":"hello","doc":"${"d".repeat(65)}"}""",
            """{"t":"hello","doc":7}""",
            envelope("t" to "mystery"),
            envelope("t" to "in", "data" to complete),
            envelope("t" to "in", "sock" to "2", "data" to complete),
            envelope("t" to "in", "sock" to 0, "data" to complete),
            envelope("t" to "in", "sock" to 2, "data" to """{"method":"event","params":{"type":"message.delta"}}"""),
            envelope("t" to "out", "sock" to 2, "data" to """{"id":7,"method":"x"}"""),
            envelope("t" to "close"),
            envelope("t" to "sent", "key" to "bogus"),
        ).forEach { data -> assertWithMessage(data.toString()).that(WebappBridgeEnvelope.parse(data)).isNull() }
    }
}
