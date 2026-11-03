package com.aistudio.dieselstationsms.kxmpzq

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

internal data class InvoicePdfPageSlice(
    val sourceTop: Int,
    val sourceBottom: Int,
    val targetHeight: Int
)

/** Shared invoice filename and PDF acceptance rules. Every page is checked, not only page one. */
internal object InvoicePdfSupport {
    /**
     * Split a full-height WebView capture into A4 source bands. The returned bands are
     * contiguous and cover every source row exactly once, including the final partial page.
     */
    fun calculatePageSlices(
        sourceWidth: Int,
        sourceHeight: Int,
        pageWidth: Int = 595,
        pageHeight: Int = 842
    ): List<InvoicePdfPageSlice> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source bitmap dimensions must be positive" }
        require(pageWidth > 0 && pageHeight > 0) { "PDF page dimensions must be positive" }

        val sourcePageHeight = (
            sourceWidth.toLong() * pageHeight.toLong() / pageWidth.toLong()
        ).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pageCount = (
            sourceHeight.toLong() + sourcePageHeight.toLong() - 1L
        ).div(sourcePageHeight.toLong()).toInt()

        return List(pageCount) { pageIndex ->
            val top = (pageIndex.toLong() * sourcePageHeight.toLong()).toInt()
            val bottom = minOf(sourceHeight.toLong(), top.toLong() + sourcePageHeight.toLong()).toInt()
            val targetHeight = (
                (bottom - top).toLong() * pageWidth.toLong() / sourceWidth.toLong()
            ).toInt().coerceIn(1, pageHeight)
            InvoicePdfPageSlice(
                sourceTop = top,
                sourceBottom = bottom,
                targetHeight = targetHeight
            )
        }
    }

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
