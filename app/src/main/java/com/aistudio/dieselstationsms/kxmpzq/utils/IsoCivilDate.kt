package com.aistudio.dieselstationsms.kxmpzq.utils

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Strict parser for ISO civil dates (YYYY-MM-DD), never timestamps or display strings. */
object IsoCivilDate {
    private val isoDateShape = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    fun parse(value: String?): LocalDate? {
        if (value == null || !isoDateShape.matches(value)) return null
        return runCatching {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                .takeIf { it.year in 1..9999 }
        }.getOrNull()
    }

    fun isValid(value: String?): Boolean = parse(value) != null

    fun requireValid(value: String, fieldLabel: String = "التاريخ"): String {
        require(isValid(value)) { "$fieldLabel غير صالح؛ استخدم الصيغة YYYY-MM-DD وتاريخاً تقويمياً صحيحاً" }
        return value
    }

    /** Null and the exactly-empty string mean an omitted optional boundary; whitespace is invalid. */
    fun requireOptionalRange(from: String?, to: String?) {
        val start = from?.takeUnless { it.isEmpty() }
        val end = to?.takeUnless { it.isEmpty() }
        if (start != null) requireValid(start, "تاريخ البداية")
        if (end != null) requireValid(end, "تاريخ النهاية")
        if (start != null && end != null) {
            require(!parse(start)!!.isAfter(parse(end))) { "تاريخ البداية يجب ألا يكون بعد تاريخ النهاية" }
        }
    }
}
