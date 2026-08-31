package com.venera.next

import android.graphics.BitmapFactory
import android.util.Base64
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

/**
 * Android bridge for the embedded translation pipeline.
 *
 * This first stage deliberately keeps OCR/translation behind a small native
 * boundary. The actual engine can be supplied independently without changing
 * the Flutter Reader API.
 */
class TranslationEmbedPlugin : MethodChannel.MethodCallHandler {
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "translateImage") {
            result.notImplemented()
            return
        }

        val encoded = call.argument<ByteArray>("imageBytes")
        if (encoded == null || encoded.isEmpty()) {
            result.error("INVALID_IMAGE", "imageBytes is missing or empty", null)
            return
        }

        // Validate that the input is a decodable Android image before handing
        // it to a future OCR/translation engine.
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        if (bitmap == null) {
            result.error("INVALID_IMAGE", "Unable to decode image", null)
            return
        }
        bitmap.recycle()

        // No engine is wired yet: returning null tells Flutter to keep the
        // original page. This keeps the bridge safe and compilable while the
        // OCR engine is ported.
        result.success(null)
    }
}
