package com.aistudio.dieselstationsms.kxmpzq

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InvoicePdfSupportTest {
    @Test fun fileNameSanitizationIsStableAndBlocksPathTraversal() {
        assertEquals("INV_2026_001", InvoicePdfSupport.sanitizeInvoiceFileName("../INV 2026/001"))
        assertEquals("فاتورة_١٢٣", InvoicePdfSupport.sanitizeInvoiceFileName("فاتورة ١٢٣"))
        assertEquals("", InvoicePdfSupport.sanitizeInvoiceFileName(" ../ "))
    }

    @Test fun rejectsMissingAndMalformedPdf() {
        val missing = File.createTempFile("invoice-missing", ".pdf").apply { delete() }
        assertFalse(InvoicePdfSupport.isPdfVisuallyNonBlank(missing))
        val malformed = File.createTempFile("invoice-bad", ".pdf")
        try {
            malformed.writeText("not a PDF")
            assertFalse(InvoicePdfSupport.isPdfVisuallyNonBlank(malformed))
        } finally {
            malformed.delete()
        }
    }

    @Test fun acceptsVisiblePdfAndRejectsBlankPdf() {
        val visible = File.createTempFile("invoice-visible", ".pdf")
        val blank = File.createTempFile("invoice-blank", ".pdf")
        try {
            // Robolectric's PdfDocument implementation closes the document while advancing
            // to a second page. Keep this unit test to one page per document; page-by-page
            // validation remains implemented in InvoicePdfSupport and needs device/instrumented
            // coverage for a true multi-page PDF fixture.
            writeSinglePagePdf(visible, drawText = true)
            writeSinglePagePdf(blank, drawText = false)

            assertTrue("A page containing invoice text must be accepted",
                InvoicePdfSupport.isPdfVisuallyNonBlank(visible))
            assertFalse("A completely white page must be rejected",
                InvoicePdfSupport.isPdfVisuallyNonBlank(blank))
        } finally {
            visible.delete()
            blank.delete()
        }
    }

    private fun writeSinglePagePdf(file: File, drawText: Boolean) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            if (drawText) {
                page.canvas.drawText("Invoice page", 30f, 60f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f })
            }
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
