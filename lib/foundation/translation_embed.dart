import 'dart:typed_data';

import 'package:flutter/services.dart';

/// Contract for the native embedded-page translation pipeline.
///
/// The native implementation is responsible for OCR, text-region detection,
/// translation and redrawing. Returning null means that the original page
/// should be displayed unchanged.
class TranslationEmbed {
  TranslationEmbed._();

  static const MethodChannel _channel =
      MethodChannel('com.github.kiastr.venera_next/translate');

  static Future<Uint8List?> translateImage({
    required Uint8List imageBytes,
    String language = 'pt-BR',
    bool forceOcr = false,
  }) async {
    try {
      final result = await _channel.invokeMethod<Uint8List>('translateImage', {
        'imageBytes': imageBytes,
        'language': language,
        'forceOcr': forceOcr,
        'renderImage': true,
      });
      return result;
    } on PlatformException {
      return null;
    }
  }
}
