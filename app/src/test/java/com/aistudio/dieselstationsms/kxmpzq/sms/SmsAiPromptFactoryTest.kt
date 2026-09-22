package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsAiPromptFactoryTest {
    @Test
    fun responseSchemaIsStrictAndRequiresCanonicalFields() {
        val schema = SmsAiPromptFactory.responseFormat()
            .getJSONObject("json_schema")
            .getJSONObject("schema")
        assertTrue(schema.getBoolean("additionalProperties").not())
        assertEquals(8, schema.getJSONArray("required").length())
        val entities = schema.getJSONObject("properties").getJSONObject("entities")
        assertFalse(entities.getBoolean("additionalProperties"))
        assertTrue(entities.getJSONObject("properties").has("quantity_liters"))
        assertTrue(entities.getJSONObject("properties").has("time_window"))
    }
}
