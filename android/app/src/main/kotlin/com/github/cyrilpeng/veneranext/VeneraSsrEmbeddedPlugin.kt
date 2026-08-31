package com.github.cyrilpeng.veneranext

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.manga.translate.BubbleRenderer
import com.manga.translate.BubbleTranslation
import com.manga.translate.TranslationLanguage
import com.manga.translate.TranslationMetadata
import com.manga.translate.TranslationPipeline
import com.manga.translate.TranslationResult
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Venera-SSR backed page translator.
 *
 * SSR supplies the on-device page-region/OCR/bubble pipeline and the native
 * BubbleRenderer. The final text translation is kept behind EmbeddedTranslator
 * so the Flutter contract can target Portuguese (pt-BR) instead of SSR's
 * default zh-CN public-translation target.
 */
class VeneraSsrEmbeddedPlugin(private val context: android.content.Context) : MethodChannel.MethodCallHandler {
    private val executor = Executors.newSingleThreadExecutor()
    private val pipeline by lazy { TranslationPipeline(context.applicationContext) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "translateImage" -> translateImage(call, result)
            else -> result.notImplemented()
        }
    }

    private fun translateImage(call: MethodCall, result: MethodChannel.Result) {
        val bytes = call.argument<ByteArray>("imageBytes")
        if (bytes == null || bytes.isEmpty()) {
            result.error("BAD_ARGS", "imageBytes required", null)
            return
        }
        val forceOcr = call.argument<Boolean>("forceOcr") ?: false

        executor.execute {
            var inputFile: File? = null
            var sourceBitmap: Bitmap? = null
            var outputBitmap: Bitmap? = null
            try {
                inputFile = File(context.cacheDir, "venera_ssr_translate_${System.currentTimeMillis()}.img")
                FileOutputStream(inputFile).use { it.write(bytes) }

                val ocrPage = runBlocking {
                    pipeline.ocrImage(
                        imageFile = inputFile!!,
                        forceOcr = forceOcr,
                        language = TranslationLanguage.CHN_ENG_TO_ZH,
                        onProgress = { }
                    )
                }

                if (ocrPage == null || ocrPage.bubbles.isEmpty()) {
                    result.success(null)
                    return@execute
                }

                val translator = EmbeddedTranslator("pt")
                val translatedBubbles = ocrPage.bubbles.mapNotNull { bubble ->
                    val source = bubble.text.trim()
                    if (source.isEmpty()) return@mapNotNull null
                    val translated = translator.translate(source)?.trim().orEmpty()
                    if (translated.isEmpty()) null else BubbleTranslation.translated(
                        id = bubble.id,
                        rect = bubble.rect,
                        translatedText = translated,
                        originalText = source,
                        source = bubble.source,
                        maskContour = bubble.maskContour
                    )
                }

                if (translatedBubbles.isEmpty()) {
                    result.success(null)
                    return@execute
                }

                sourceBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (sourceBitmap == null) {
                    result.error("TRANSLATE_RENDER_FAILED", "failed to decode source image", null)
                    return@execute
                }

                val translation = TranslationResult(
                    imageName = inputFile.name,
                    width = sourceBitmap!!.width,
                    height = sourceBitmap!!.height,
                    bubbles = translatedBubbles,
                    metadata = TranslationMetadata(
                        mode = TranslationMetadata.MODE_STANDARD,
                        language = "auto_to_pt-BR",
                        status = com.manga.translate.PageTranslationStatus.SUCCESS
                    )
                )

                outputBitmap = runBlocking {
                    BubbleRenderer(context.applicationContext).render(
                        sourceBitmap!!,
                        translation,
                        false
                    )
                }

                val png = ByteArrayOutputStream().use { out ->
                    if (outputBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
                }
                if (png == null) {
                    result.error("TRANSLATE_RENDER_FAILED", "failed to encode PNG", null)
                    return@execute
                }
                result.success(png)
            } catch (t: Throwable) {
                result.error("TRANSLATE_FAILED", t.message ?: "Venera-SSR translation failed", null)
            } finally {
                outputBitmap?.takeIf { it !== sourceBitmap }?.recycle()
                sourceBitmap?.recycle()
                inputFile?.delete()
            }
        }
    }
}
