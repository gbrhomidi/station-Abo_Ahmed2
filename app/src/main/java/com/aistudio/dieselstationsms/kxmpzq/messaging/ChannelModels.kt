package com.aistudio.dieselstationsms.kxmpzq.messaging

import org.json.JSONObject

/** عقد محايد للقنوات؛ adapter يطبع payload الخارجي قبل دخول Conversation/Business Engine. */
enum class MessagingChannel { SMS, WHATSAPP }

data class ChannelMessageEnvelope(
    val channel: MessagingChannel,
    val externalMessageId: String,
    val senderId: String,
    val displayName: String,
    val text: String,
    val timestampSeconds: Long,
    val replyToExternalId: String? = null,
    val rawMetadata: JSONObject = JSONObject()
)

data class ChannelSendResult(
    val channel: MessagingChannel,
    val providerMessageId: String,
    val acceptedAt: Long = System.currentTimeMillis()
)

interface ChannelInboundProcessor {
    suspend fun process(envelope: ChannelMessageEnvelope): Boolean
}
