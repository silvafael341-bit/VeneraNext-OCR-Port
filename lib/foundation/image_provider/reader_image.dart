import 'dart:async' show Future;
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_qjs/flutter_qjs.dart';
import 'package:venera_next/foundation/file_system.dart';
import 'package:venera_next/foundation/js_engine.dart';
import 'package:venera_next/network/images.dart';
import 'base_image_provider.dart';
import 'package:venera_next/foundation/appdata.dart';
import 'package:venera_next/foundation/translation_embed.dart';

final Object _imageProcessingCanceled = Object();

@visibleForTesting
Future<dynamic> debugWaitForReaderImageProcessingResult(
  Future<dynamic> image,
  void Function() onCancel,
  void Function() checkStop, {
  Future<void>? cancelSignal,
}) {
  return _waitForReaderImageProcessingResult(
    image,
    onCancel,
    checkStop,
    cancelSignal: cancelSignal,
  );
}

Future<dynamic> _waitForReaderImageProcessingResult(
  Future<dynamic> image,
  void Function() onCancel,
  void Function() checkStop, {
  Future<void>? cancelSignal,
}) async {
  final result = await Future.any<dynamic>([
    image,
    (cancelSignal ?? BaseImageProvider.cancelSignalOf(checkStop)).then(
      (_) => _imageProcessingCanceled,
    ),
  ]);
  if (identical(result, _imageProcessingCanceled)) {
    onCancel();
    checkStop();
  }
  return result ?? Uint8List(0);
}

class ReaderImageProvider extends BaseImageProvider<ReaderImageProvider> {
  const ReaderImageProvider(
    this.imageKey,
    this.sourceKey,
    this.cid,
    this.eid,
    this.page, {
    this.enableResize = false,
  });

  final String imageKey;
  final String? sourceKey;
  final String cid;
  final String eid;
  final int page;

  @override
  final bool enableResize;

  @override
  Future<Uint8List> load(chunkEvents, checkStop) async {
    Uint8List? imageBytes;
    if (imageKey.startsWith('file://')) {
      final file = File(imageKey);
      if (await file.exists()) {
        imageBytes = await file.readAsBytes();
      } else {
        throw 'Error: File not found.';
      }
    } else {
      await for (final event in ImageDownloader.loadComicImage(
        imageKey,
        sourceKey,
        cid,
        eid,
      )) {
        checkStop();
        chunkEvents.add(
          ImageChunkEvent(
            cumulativeBytesLoaded: event.currentBytes,
            expectedTotalBytes: event.totalBytes,
          ),
        );
        if (event.imageBytes != null) {
          imageBytes = event.imageBytes;
          break;
        }
      }
    }
    if (imageBytes == null) {
      throw 'Error: Empty response body.';
    }

    if (appdata.settings['enableCustomImageProcessing']) {
      final script = appdata.settings['customImageProcessing'].toString();
      if (script.contains('function processImage')) {
        final func = JsEngine().runCode('''
          (() => {
            $script
            return processImage;
          })()
        ''');
        if (func is JSInvokable) {
          final autoFreeFunc = JSAutoFreeFunction(func);
          final result = autoFreeFunc([imageBytes, cid, eid, page, sourceKey]);
          if (result is Uint8List) {
            imageBytes = result;
          } else if (result is Future) {
            final futureResult = await result;
            if (futureResult is Uint8List) {
              imageBytes = futureResult;
            }
          } else if (result is Map) {
            final image = result['image'];
            if (image is Uint8List) {
              imageBytes = image;
            } else if (image is Future) {
              JSAutoFreeFunction? onCancel;
              if (result['onCancel'] is JSInvokable) {
                onCancel = JSAutoFreeFunction(result['onCancel']);
              }
              if (onCancel == null) {
                final futureImage = await image;
                if (futureImage is Uint8List) {
                  imageBytes = futureImage;
                }
              } else {
                final cancelImageProcessing = onCancel;
                final futureImage = await _waitForReaderImageProcessingResult(
                  image,
                  () => cancelImageProcessing([]),
                  checkStop,
                );
                if (futureImage is Uint8List) {
                  imageBytes = futureImage;
                }
              }
            }
          }
        }
      }
    }

    // Embedded OCR/translation runs after custom JS image processing.
    // A null result deliberately leaves the original page unchanged.
    final translated = await TranslationEmbed.translateImage(
      imageBytes: imageBytes,
      language: 'pt-BR',
    );
    if (translated != null && translated.isNotEmpty) {
      imageBytes = translated;
    }

    return imageBytes;
  }

  @override
  Future<ReaderImageProvider> obtainKey(ImageConfiguration configuration) {
    return SynchronousFuture(this);
  }

  @override
  String get key => '$imageKey@$sourceKey@$cid@$eid@$enableResize';
}
