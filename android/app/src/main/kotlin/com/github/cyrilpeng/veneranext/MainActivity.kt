package com.github.cyrilpeng.veneranext

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dev.flutter.packages.file_selector_android.FileUtils
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : FlutterFragmentActivity() {
    var volumeListen = VolumeListen()
    var listening = false
    private val storageRequestCode = 0x10
    private var storagePermissionRequest: ((Boolean) -> Unit)? = null
    private val nextLocalRequestCode = AtomicInteger()
    private val sharedTexts = ArrayList<String>()
    private var textShareHandler: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::handleSharedText)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::handleSharedText)
        }
    }

    private fun handleSharedText(text: String) {
        if (textShareHandler != null) textShareHandler?.invoke(text) else sharedTexts.add(text)
    }

    private fun <I, O> startContractForResult(
        contract: ActivityResultContract<I, O>, input: I, callback: ActivityResultCallback<O>
    ) {
        val key = "activity_rq_for_result#${nextLocalRequestCode.getAndIncrement()}"
        val registry = activityResultRegistry
        var launcher: ActivityResultLauncher<I>? = null
        val observer = object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    launcher?.unregister()
                    lifecycle.removeObserver(this)
                }
            }
        }
        lifecycle.addObserver(observer)
        launcher = registry.register(key, contract, ActivityResultCallback { value ->
            launcher?.unregister()
            lifecycle.removeObserver(observer)
            callback.onActivityResult(value)
        })
        launcher.launch(input)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        GeneratedPluginRegistrant.registerWith(flutterEngine)

        // Same Dart channel as before, now backed by the SSR OCR/bubble detector
        // and BubbleRenderer, with our Portuguese translation adapter.
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.github.kiastr.venera_next/translate"
        ).setMethodCallHandler(VeneraSsrEmbeddedPlugin(this))

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "venera/method_channel")
            .setMethodCallHandler { call, res ->
                when (call.method) {
                    "getProxy" -> res.success(getProxy())
                    "setScreenOn" -> {
                        val set = call.argument<Boolean>("set") ?: false
                        if (set) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        res.success(null)
                    }
                    "getDirectoryPath" -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        startContractForResult(ActivityResultContracts.StartActivityForResult(), intent) { activityResult ->
                            if (activityResult.resultCode != Activity.RESULT_OK) {
                                res.success(null)
                                return@startContractForResult
                            }
                            val pickedDirectoryUri = activityResult.data?.data
                            if (pickedDirectoryUri == null) res.success(null) else onPickedDirectory(pickedDirectoryUri, res)
                        }
                    }
                    else -> res.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "venera/volume").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    listening = true
                    volumeListen.onUp = { events.success(1) }
                    volumeListen.onDown = { events.success(2) }
                }
                override fun onCancel(arguments: Any?) { listening = false }
            }
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "venera/storage")
            .setMethodCallHandler { _, res -> requestStoragePermission { res.success(it) } }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "venera/select_file")
            .setMethodCallHandler { req, res -> openFile(res, req.arguments<String>()!!) }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "venera/text_share").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    textShareHandler = { text -> events.success(text) }
                    sharedTexts.forEach(events::success)
                    sharedTexts.clear()
                }
                override fun onCancel(arguments: Any?) { textShareHandler = null }
            }
        )
    }

    private fun getProxy(): String {
        val host = System.getProperty("http.proxyHost")
        val port = System.getProperty("http.proxyPort")
        return if (host != null && port != null) "$host:$port" else "No Proxy"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (listening) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { volumeListen.down(); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { volumeListen.up(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onPickedDirectory(uri: Uri, result: MethodChannel.Result) {
        if (hasStoragePermission()) {
            var plain = uri.toString()
            if (plain.contains("%3A")) plain = Uri.decode(plain)
            val prefix = "content://com.android.externalstorage.documents/tree/primary:"
            if (plain.startsWith(prefix)) {
                result.success(Environment.getExternalStorageDirectory().absolutePath + "/" + plain.substring(prefix.length))
            }
        }
        val resolver = contentResolver
        val dirName = DocumentFile.fromTreeUri(this, uri)?.name ?: "selected"
        val tmp = File(cacheDir, dirName)
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdir()
        Thread {
            try {
                copyDirectory(resolver, uri, tmp)
                result.success(tmp.absolutePath)
            } catch (e: Exception) {
                result.error("copy error", e.message, null)
            }
        }.start()
    }

    private fun copyDirectory(resolver: ContentResolver, srcUri: Uri, destDir: File) {
        val src = DocumentFile.fromTreeUri(this, srcUri) ?: return
        for (file in src.listFiles()) {
            if (file.isDirectory) {
                val newDir = File(destDir, file.name ?: "dir")
                newDir.mkdir()
                copyDirectory(resolver, file.uri, newDir)
            } else {
                val newFile = File(destDir, file.name ?: "file")
                resolver.openInputStream(file.uri)?.use { input ->
                    FileOutputStream(newFile).use { output -> input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE) }
                }
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else Environment.isExternalStorageManager()
    }

    private fun requestStoragePermission(result: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!read || !write) {
                storagePermissionRequest = result
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), storageRequestCode)
            } else result(true)
        } else if (!Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    addCategory("android.intent.category.DEFAULT")
                    data = Uri.parse("package:$packageName")
                }
                startContractForResult(ActivityResultContracts.StartActivityForResult(), intent) { result(Environment.isExternalStorageManager()) }
            } catch (_: Exception) { result(false) }
        } else result(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == storageRequestCode) {
            storagePermissionRequest?.invoke(grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            storagePermissionRequest = null
        }
    }

    private fun openFile(result: MethodChannel.Result, mimeType: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        startContractForResult(ActivityResultContracts.StartActivityForResult(), intent) { activityResult ->
            if (activityResult.resultCode != Activity.RESULT_OK) { result.success(null); return@startContractForResult }
            val uri = activityResult.data?.data
            if (uri == null) { result.success(null); return@startContractForResult }
            val file = DocumentFile.fromSingleUri(this, uri)
            val fileName = file?.name
            if (file == null || fileName == null) { result.success(null); return@startContractForResult }
            if (hasStoragePermission()) {
                try {
                    result.success(FileUtils.getPathFromUri(this, uri))
                    return@startContractForResult
                } catch (_: Exception) { }
            }
            val tmp = File(cacheDir, fileName)
            if (tmp.exists()) tmp.delete()
            Log.i("VeneraNext", "copy file ($fileName) to ${tmp.absolutePath}")
            Thread {
                try {
                    contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE) } }
                    result.success(tmp.absolutePath)
                } catch (e: Exception) { result.error("copy error", e.message, null) }
            }.start()
        }
    }
}

class VolumeListen {
    var onUp = fun() {}
    var onDown = fun() {}
    fun up() = onUp()
    fun down() = onDown()
}
