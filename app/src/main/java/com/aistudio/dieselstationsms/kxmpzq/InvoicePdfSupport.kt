package com.aistudio.dieselstationsms.kxmpzq

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Shared invoice filename and PDF acceptance rules. Every page is checked, not only page one. */
internal object InvoicePdfSupport {
    fun sanitizeInvoiceFileName(invoiceNumber: String): String =
        invoiceNumber.trim()
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)

    fun isPdfVisuallyNonBlank(file: File): Boolean {
        if (!file.isFile || file.length() < 100L) return false
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount < 1) return false
                    for (index in 0 until renderer.pageCount) {
                        val page = renderer.openPage(index)
                        val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var visible = 0
                            for (y in 0 until bitmap.height) {
                                for (x in 0 until bitmap.width) {
                                    val pixel = bitmap.getPixel(x, y)
                                    val r = Color.red(pixel)
                                    val g = Color.green(pixel)
                                    val b = Color.blue(pixel)
                                    if (maxOf(r, g, b) < 245 || (maxOf(r, g, b) - minOf(r, g, b)) > 20) visible++
                                }
                            }
                            if (visible < 4) return false
                        } finally {
                            bitmap.recycle()
                            page.close()
                        }
                    }
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
