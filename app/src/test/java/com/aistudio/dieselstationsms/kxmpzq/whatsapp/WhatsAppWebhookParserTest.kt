package com.aistudio.dieselstationsms.kxmpzq.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsAppWebhookParserTest {
    @Test
    fun parsesTextAndDeliveryStatus() {
        val payload = """
            {
              "object":"whatsapp_business_account",
              "entry":[{"changes":[{"field":"messages","value":{
                "contacts":[{"profile":{"name":"عميل"},"wa_id":"967700000000"}],
                "messages":[{"from":"967700000000","id":"wamid.in-1","timestamp":"1700000000","type":"text","text":{"body":"اريد ديزل"}}],
                "statuses":[{"id":"wamid.out-1","recipient_id":"967700000000","status":"delivered","timestamp":"1700000001"}]
              }}]}]
            }
        """.trimIndent()
        val events = WhatsAppWebhookParser.parse(payload)
        assertEquals(2, events.size)
        val inbound = events.filterIsInstance<WhatsAppWebhookEvent.InboundMessage>().single()
        assertEquals("wamid.in-1", inbound.envelope.externalMessageId)
        assertEquals("اريد ديزل", inbound.envelope.text)
        assertEquals("whatsapp", inbound.envelope.channel.name.lowercase())
        val status = events.filterIsInstance<WhatsAppWebhookEvent.StatusUpdate>().single()
        assertEquals("delivered", status.update.status)
        assertTrue(status.update.messageId.startsWith("wamid"))
    }

    @Test
    fun parsesInteractiveReplyAsUserText() {
        val payload = """
            {"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{"messages":[{"from":"967700000000","id":"wamid.in-2","timestamp":"1700000002","type":"interactive","interactive":{"button_reply":{"id":"confirm","title":"تأكيد"}}}]}}]}]}
        """.trimIndent()
        val event = WhatsAppWebhookParser.parse(payload).filterIsInstance<WhatsAppWebhookEvent.InboundMessage>().single()
        assertEquals("تأكيد", event.envelope.text)
        assertEquals("confirm", event.envelope.rawMetadata.getJSONObject("interactive").getJSONObject("button_reply").getString("id"))
    }
}
