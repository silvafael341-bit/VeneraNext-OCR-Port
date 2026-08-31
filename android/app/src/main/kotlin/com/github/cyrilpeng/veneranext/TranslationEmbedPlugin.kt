package com.github.cyrilpeng.veneranext

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Android endpoint for the embedded OCR + translation + redraw pipeline. */
class TranslationEmbedPlugin : MethodChannel.MethodCallHandler {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocr = OcrEngine()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "translateImage") {
            result.notImplemented()
            return
        }

        val imageBytes = call.argument<ByteArray>("imageBytes")
        val targetLanguage = call.argument<String>("language")?.ifBlank { "pt" } ?: "pt"

        if (imageBytes == null || imageBytes.isEmpty()) {
            result.error("INVALID_IMAGE", "imageBytes is missing or empty", null)
            return
        }

        executor.execute {
            try {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap == null) {
                    mainHandler.post { result.error("INVALID_IMAGE", "Unable to decode image", null) }
                    return@execute
                }

                val blocks = ocr.scan(bitmap)
                if (blocks.isEmpty()) {
                    bitmap.recycle()
                    mainHandler.post { result.success(null) }
                    return@execute
                }

                val translator = EmbeddedTranslator(targetLanguage)
                val translated = blocks.mapNotNull { block ->
                    val text = translator.translate(block.text) ?: return@mapNotNull null
                    if (text.isBlank()) null else block to text
                }

                if (translated.isEmpty()) {
                    bitmap.recycle()
                    mainHandler.post { result.success(null) }
                    return@execute
                }

                val rendered = TranslationRenderer.render(bitmap, translated)
                bitmap.recycle()

                val output = ByteArrayOutputStream()
                rendered.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                rendered.recycle()
                val bytes = output.toByteArray()
                output.close()

                mainHandler.post { result.success(bytes) }
            } catch (e: Exception) {
                mainHandler.post {
                    result.error("TRANSLATION_FAILED", e.message ?: "Embedded translation failed", null)
                }
            }
        }
    }
}
