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

    /**
     * Require a light paper background and visible content. Dark WebView surfaces must not
     * pass merely because they contain many non-white pixels.
     */
    fun isBitmapVisuallyInvoiceLike(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        var samples = 0
        var lightPaper = 0
        var content = 0
        val stepX = maxOf(1, bitmap.width / 96)
        val stepY = maxOf(1, bitmap.height / 128)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val maxChannel = maxOf(r, g, b)
                val minChannel = minOf(r, g, b)
                samples++
                if (r >= 200 && g >= 200 && b >= 200) lightPaper++
                if (maxChannel < 235 || (maxChannel - minChannel) > 24) content++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return false
        return lightPaper.toDouble() / samples >= 0.35 &&
            content.toDouble() / samples >= 0.002
    }

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
                            if (!isBitmapVisuallyInvoiceLike(bitmap)) return false
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
