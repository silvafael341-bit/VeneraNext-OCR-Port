package com.github.cyrilpeng.veneranext

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit

/**
 * Multilingual OCR used by the embedded page translator.
 *
 * We deliberately run CJK + Latin recognizers at 0/90/270 degrees. This is
 * important for manhua pages where speech bubbles can contain vertical text.
 */
class OcrEngine {
    private val chinese = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val japanese = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )
    private val korean = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )
    private val latin = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    private val recognizers: List<TextRecognizer> = listOf(
        chinese, japanese, korean, latin
    )

    fun scan(bitmap: Bitmap): List<OcrTextBlock> {
        val candidates = mutableListOf<List<OcrTextBlock>>()
        for (rotation in intArrayOf(0, 90, 270)) {
            val rotated = if (rotation == 0) bitmap else rotate(bitmap, rotation)
            try {
                for (recognizer in recognizers) {
                    val result = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(rotated, 0)),
                        15,
                        TimeUnit.SECONDS
                    )
                    val blocks = result.textBlocks.mapNotNull { block ->
                        val text = block.text.trim()
                        val box = block.boundingBox ?: return@mapNotNull null
                        if (text.isEmpty() || box.width() < 6 || box.height() < 6) {
                            return@mapNotNull null
                        }
                        OcrTextBlock(
                            text = text,
                            boundingBox = mapRect(
                                box,
                                rotation,
                                bitmap.width,
                                bitmap.height
                            ),
                            vertical = isVertical(box, text)
                        )
                    }
                    if (blocks.isNotEmpty()) candidates += blocks
                }
            } finally {
                if (rotation != 0) rotated.recycle()
            }
        }

        return selectBest(candidates)
    }

    private fun selectBest(candidates: List<List<OcrTextBlock>>): List<OcrTextBlock> {
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .map { blocks ->
                val textScore = blocks.sumOf { it.text.length }
                val blockScore = blocks.size * 18
                val verticalScore = blocks.count { it.vertical } * 5
                blocks to textScore + blockScore + verticalScore
            }
            .maxByOrNull { it.second }
            ?.first
            ?.distinctBy { "${it.text}|${it.boundingBox.left}|${it.boundingBox.top}|${it.boundingBox.right}|${it.boundingBox.bottom}" }
            ?.sortedWith(compareBy<OcrTextBlock>({ it.boundingBox.top }, { it.boundingBox.left }))
            ?: emptyList()
    }

    private fun isVertical(box: Rect, text: String): Boolean {
        if (box.height() <= box.width() * 1.45f) return false
        val cjk = text.count { ch ->
            ch.code in 0x3400..0x4DBF ||
                ch.code in 0x4E00..0x9FFF ||
                ch.code in 0x3040..0x30FF ||
                ch.code in 0xAC00..0xD7AF
        }
        return cjk >= maxOf(2, text.length / 3)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun mapRect(rect: Rect, rotation: Int, width: Int, height: Int): Rect {
        return when (rotation) {
            90 -> Rect(
                height - rect.bottom,
                rect.left,
                height - rect.top,
                rect.right
            )
            270 -> Rect(
                rect.top,
                width - rect.right,
                rect.bottom,
                width - rect.left
            )
            else -> Rect(rect)
        }
    }

    fun close() {
        chinese.close()
        japanese.close()
        korean.close()
        latin.close()
    }
}

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
    val vertical: Boolean
)
