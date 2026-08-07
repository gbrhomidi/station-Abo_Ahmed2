package com.aistudio.dieselstationsms.kxmpzq.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Yemen phone normalization contract.
 *
 * Valid Yemen mobile prefixes: 70, 71, 73, 77, 78
 * Canonical format: 967 + 9-digit national number
 */
class PhoneUtilsTest {

    // ═══════════════════════════════════════════════════════
    // Valid Yemen mobile numbers — MUST normalize correctly
    // ═══════════════════════════════════════════════════════

    @Test
    fun `normalize +967771234567 returns 967771234567`() {
        assertEquals("967771234567", PhoneUtils.normalize("+967771234567"))
    }

    @Test
    fun `normalize 00967771234567 returns 967771234567`() {
        assertEquals("967771234567", PhoneUtils.normalize("00967771234567"))
    }

    @Test
    fun `normalize 967771234567 returns 967771234567`() {
        assertEquals("967771234567", PhoneUtils.normalize("967771234567"))
    }

    @Test
    fun `normalize 771234567 returns 967771234567`() {
        assertEquals("967771234567", PhoneUtils.normalize("771234567"))
    }

    @Test
    fun `normalize 0771234567 returns 967771234567`() {
        assertEquals("967771234567", PhoneUtils.normalize("0771234567"))
    }

    @Test
    fun `normalize 0781234567 returns 967781234567`() {
        assertEquals("967781234567", PhoneUtils.normalize("0781234567"))
    }

    @Test
    fun `normalize 0731234567 returns 967731234567`() {
        assertEquals("967731234567", PhoneUtils.normalize("0731234567"))
    }

    @Test
    fun `normalize 0711234567 returns 967711234567`() {
        assertEquals("967711234567", PhoneUtils.normalize("0711234567"))
    }

    @Test
    fun `normalize 0701234567 returns 967701234567`() {
        assertEquals("967701234567", PhoneUtils.normalize("0701234567"))
    }

    @Test
    fun `normalize 781234567 returns 967781234567`() {
        assertEquals("967781234567", PhoneUtils.normalize("781234567"))
    }

    @Test
    fun `normalize 731234567 returns 967731234567`() {
        assertEquals("967731234567", PhoneUtils.normalize("731234567"))
    }

    @Test
    fun `normalize 711234567 returns 967711234567`() {
        assertEquals("967711234567", PhoneUtils.normalize("711234567"))
    }

    @Test
    fun `normalize 701234567 returns 967701234567`() {
        assertEquals("967701234567", PhoneUtils.normalize("701234567"))
    }

    // ═══════════════════════════════════════════════════════
    // Separators handling
    // ═══════════════════════════════════════════════════════

    @Test
    fun `normalize with spaces returns canonical`() {
        assertEquals("967771234567", PhoneUtils.normalize("+967 77 123 4567"))
    }

    @Test
    fun `normalize with dashes returns canonical`() {
        assertEquals("967771234567", PhoneUtils.normalize("+967-77-123-4567"))
    }

    @Test
    fun `normalize with parentheses returns canonical`() {
        assertEquals("967771234567", PhoneUtils.normalize("(+967) 77 123 4567"))
    }

    // ═══════════════════════════════════════════════════════
    // Invalid inputs — MUST return null
    // ═══════════════════════════════════════════════════════

    @Test
    fun `normalize null returns null`() {
        assertNull(PhoneUtils.normalize(null))
    }

    @Test
    fun `normalize empty string returns null`() {
        assertNull(PhoneUtils.normalize(""))
    }

    @Test
    fun `normalize blank string returns null`() {
        assertNull(PhoneUtils.normalize("   "))
    }

    @Test
    fun `normalize too short returns null`() {
        assertNull(PhoneUtils.normalize("123"))
    }

    @Test
    fun `normalize unsupported prefix 72 returns null`() {
        assertNull(PhoneUtils.normalize("721234567"))
    }

    @Test
    fun `normalize unsupported prefix 79 returns null`() {
        assertNull(PhoneUtils.normalize("791234567"))
    }

    @Test
    fun `normalize unsupported prefix 69 returns null`() {
        assertNull(PhoneUtils.normalize("0691234567"))
    }

    @Test
    fun `normalize non numeric returns null`() {
        assertNull(PhoneUtils.normalize("abc123"))
    }

    @Test
    fun `normalize wrong length after strip returns null`() {
        assertNull(PhoneUtils.normalize("12345678901"))
    }

    // ═══════════════════════════════════════════════════════
    // isSameNumber
    // ═══════════════════════════════════════════════════════

    @Test
    fun `isSameNumber with different formats returns true`() {
        assertTrue(PhoneUtils.isSameNumber("0771234567", "+967771234567"))
    }

    @Test
    fun `isSameNumber with different numbers returns false`() {
        assertFalse(PhoneUtils.isSameNumber("0771234567", "0781234567"))
    }

    // ═══════════════════════════════════════════════════════
    // startsWithPrefix
    // ═══════════════════════════════════════════════════════

    @Test
    fun `startsWithPrefix 96777 returns true for Yemen Mobile`() {
        assertTrue(PhoneUtils.startsWithPrefix("0771234567", "96777"))
    }

    @Test
    fun `startsWithPrefix 96770 returns true for Y`() {
        assertTrue(PhoneUtils.startsWithPrefix("0701234567", "96770"))
    }
}