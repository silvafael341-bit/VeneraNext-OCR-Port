package com.github.cyrilpeng.veneranext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Lightweight adaptive renderer for the first embedded translation pipeline. */
object TranslationRenderer {
    fun render(source: Bitmap, blocks: List<Pair<OcrTextBlock, String>>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        for ((block, translated) in blocks) {
            val text = translated.trim()
            if (text.isEmpty()) continue

            val rect = safeRect(block.boundingBox, output.width, output.height)
            if (rect.width() < 8 || rect.height() < 8) continue

            val background = sampleBackground(output, rect)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = background
                style = Paint.Style.FILL
            }
            val margin = max(2, min(rect.width(), rect.height()) / 20)
            val eraseRect = Rect(
                max(0, rect.left - margin),
                max(0, rect.top - margin),
                min(output.width, rect.right + margin),
                min(output.height, rect.bottom + margin)
            )
            canvas.drawRect(eraseRect, fill)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (luminance(background) < 0.48f) Color.WHITE else Color.BLACK
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }

            if (block.vertical) {
                drawVertical(canvas, text, rect, paint)
            } else {
                drawHorizontal(canvas, text, rect, paint)
            }
        }

        return output
    }

    private fun drawHorizontal(canvas: Canvas, text: String, rect: Rect, paint: Paint) {
        val availableWidth = max(8, rect.width() - 8)
        var size = max(10f, rect.height() * 0.72f)
        paint.textSize = size
        while (size > 8f && measureWrappedHeight(text, paint, availableWidth) > rect.height() - 4) {
            size -= 1f
            paint.textSize = size
        }

        val lines = wrap(text, paint, availableWidth)
        val fm = paint.fontMetrics
        var y = rect.top + 4 - fm.top
        for (line in lines) {
            if (y + fm.bottom > rect.bottom) break
            canvas.drawText(line, rect.left + 4f, y, paint)
            y += (fm.bottom - fm.top) * 0.92f
        }
    }

    private fun drawVertical(canvas: Canvas, text: String, rect: Rect, paint: Paint) {
        val chars = text.replace(" ", "").toCharArray()
        val size = max(10f, min(rect.width(), rect.height() / max(1, chars.size)) * 0.85f)
        paint.textSize = size
        val fm = paint.fontMetrics
        var y = rect.top + 4 - fm.top
        val x = rect.centerX().toFloat() - (paint.measureText("中") / 2f)
        for (char in chars) {
            if (y + fm.bottom > rect.bottom) break
            canvas.drawText(char.toString(), x, y, paint)
            y += (fm.bottom - fm.top) * 0.92f
        }
    }

    private fun wrap(text: String, paint: Paint, width: Int): List<String> {
        val result = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) result += line.toString()
                line = StringBuilder(word)
                if (paint.measureText(line.toString()) > width) {
                    line.clear()
                    for (ch in word) {
                        val next = line.toString() + ch
                        if (paint.measureText(next) > width && line.isNotEmpty()) {
                            result += line.toString()
                            line = StringBuilder(ch.toString())
                        } else {
                            line.append(ch)
                        }
                    }
                }
            }
        }
        if (line.isNotEmpty()) result += line.toString()
        return result.ifEmpty { listOf(text) }
    }

    private fun measureWrappedHeight(text: String, paint: Paint, width: Int): Float {
        val lines = wrap(text, paint, width).size
        val fm = paint.fontMetrics
        return lines * (fm.bottom - fm.top) * 0.92f
    }

    private fun safeRect(rect: Rect, width: Int, height: Int): Rect {
        return Rect(
            rect.left.coerceIn(0, width),
            rect.top.coerceIn(0, height),
            rect.right.coerceIn(0, width),
            rect.bottom.coerceIn(0, height)
        )
    }

    private fun sampleBackground(bitmap: Bitmap, rect: Rect): Int {
        val points = listOf(
            rect.left.coerceIn(0, bitmap.width - 1) to rect.centerY().coerceIn(0, bitmap.height - 1),
            rect.right.minus(1).coerceIn(0, bitmap.width - 1) to rect.centerY().coerceIn(0, bitmap.height - 1),
            rect.centerX().coerceIn(0, bitmap.width - 1) to rect.top.coerceIn(0, bitmap.height - 1),
            rect.centerX().coerceIn(0, bitmap.width - 1) to rect.bottom.minus(1).coerceIn(0, bitmap.height - 1)
        )
        var r = 0L
        var g = 0L
        var b = 0L
        for ((x, y) in points) {
            val c = bitmap.getPixel(x, y)
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
        }
        return Color.rgb((r / points.size).toInt(), (g / points.size).toInt(), (b / points.size).toInt())
    }

    private fun luminance(color: Int): Float {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
    }
}
