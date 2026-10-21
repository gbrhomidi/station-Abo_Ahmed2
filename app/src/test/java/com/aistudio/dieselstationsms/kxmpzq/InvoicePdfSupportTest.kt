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
        } finally { malformed.delete() }
    }

    @Test fun validatesEveryPageAndRejectsBlankSecondPage() {
        val blankSecondPage = File.createTempFile("invoice-blank-page", ".pdf")
        val allPagesVisible = File.createTempFile("invoice-visible-pages", ".pdf")
        try {
            writePdf(blankSecondPage, blankSecondPage = true)
            assertFalse("A nonblank first page must not hide a blank second page",
                InvoicePdfSupport.isPdfVisuallyNonBlank(blankSecondPage))

            // Use a distinct file for each PdfDocument. Robolectric's PdfDocument shadow can
            // retain closed-document state when a second document rewrites the same file.
            writePdf(allPagesVisible, blankSecondPage = false)
            assertTrue(InvoicePdfSupport.isPdfVisuallyNonBlank(allPagesVisible))
        } finally {
            blankSecondPage.delete()
            allPagesVisible.delete()
        }
    }

    private fun writePdf(file: File, blankSecondPage: Boolean) {
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                if (index == 0 || !blankSecondPage) {
                    page.canvas.drawText("Invoice page ${index + 1}", 30f, 60f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f })
                }
                document.finishPage(page)
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally { document.close() }
    }
}
