package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * قناة أحداث داخل العملية نفسها؛ تصلح لتحديث WebView المفتوح على نفس التطبيق
 * عبر JavaScript Bridge، ولا تنشئ خادمًا محليًا أو REST أو localhost.
 */
object LiveUpdateHub {
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    fun subscribe(listener: (String) -> Unit) { listeners += listener }
    fun unsubscribe(listener: (String) -> Unit) { listeners -= listener }
    fun publish(event: JSONObject) {
        val payload = event.toString()
        listeners.forEach { listener -> runCatching { listener(payload) } }
    }
}
