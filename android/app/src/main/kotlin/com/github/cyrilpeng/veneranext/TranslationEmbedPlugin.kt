package com.github.cyrilpeng.veneranext

import android.graphics.BitmapFactory
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Android bridge for the embedded page-translation pipeline. */
class TranslationEmbedPlugin : MethodChannel.MethodCallHandler {
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "translateImage") {
            result.notImplemented()
            return
        }

        val imageBytes = call.argument<ByteArray>("imageBytes")
        if (imageBytes == null || imageBytes.isEmpty()) {
            result.error("INVALID_IMAGE", "imageBytes is missing or empty", null)
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (bitmap == null) {
            result.error("INVALID_IMAGE", "Unable to decode image", null)
            return
        }
        bitmap.recycle()

        // The OCR/translation engine will be connected in the next stage.
        // null instructs the Dart side to keep the original page.
        result.success(null)
    }
}
