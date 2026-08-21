package ir.vinor.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.File

/**
 * پشتیبانی از &lt;input type="file"&gt; در WebView (آپلود آواتار و سایر فایل‌ها).
 */
class VinorWebViewFileChooser {

    private val hostActivity: () -> Activity?

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var pendingChooserIntent: Intent? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private lateinit var chooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>

    constructor(activity: ComponentActivity) : this({ activity })

    constructor(fragment: Fragment) : this({ fragment.activity })

    private constructor(hostActivity: () -> Activity?) {
        this.hostActivity = hostActivity
    }

    fun register(activity: ComponentActivity) {
        chooserLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            deliverResult(result.resultCode == Activity.RESULT_OK, result.data)
        }
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result[Manifest.permission.CAMERA] == true
            onCameraPermissionResult(granted)
        }
        audioPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> onMediaPermissionResult(granted) }
    }

    fun register(fragment: Fragment) {
        chooserLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            deliverResult(result.resultCode == Activity.RESULT_OK, result.data)
        }
        permissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result[Manifest.permission.CAMERA] == true
            onCameraPermissionResult(granted)
        }
        audioPermissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> onMediaPermissionResult(granted) }
    }

    fun createChromeClient(
        onProgressChanged: ((Int) -> Unit)? = null,
    ): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 0) onProgressChanged?.invoke(newProgress)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?,
            ): Boolean {
                return showFileChooser(callback, params)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                handlePermissionRequest(request)
            }
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val origin = request.origin?.toString().orEmpty()
        if (!VinorBridgeGate.isTrustedOrigin(origin)) {
            runCatching { request.deny() }
            return
        }
        val activity = hostActivity()
        if (!VinorSafe.isActivityAlive(activity) || activity == null) {
            runCatching { request.deny() }
            return
        }
        val resources = request.resources ?: run {
            runCatching { request.deny() }
            return
        }
        val needsAudio = resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        val needsVideo = resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
        if (needsAudio && !hasRecordAudioPermission(activity)) {
            pendingPermissionRequest = request
            if (::audioPermissionLauncher.isInitialized) {
                runCatching { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    .onFailure {
                        pendingPermissionRequest = null
                        runCatching { request.deny() }
                    }
            } else {
                runCatching { request.deny() }
            }
            return
        }
        if (needsVideo && !hasCameraPermission(activity)) {
            pendingPermissionRequest = request
            if (::permissionLauncher.isInitialized) {
                runCatching { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }
                    .onFailure {
                        pendingPermissionRequest = null
                        runCatching { request.deny() }
                    }
            } else {
                runCatching { request.deny() }
            }
            return
        }
        grantPermittedResources(activity, request, resources)
    }

    private fun onCameraPermissionResult(granted: Boolean) {
        if (pendingPermissionRequest != null) {
            onMediaPermissionResult(granted)
            return
        }
        launchPendingChooser()
    }

    private fun onMediaPermissionResult(granted: Boolean) {
        val req = pendingPermissionRequest
        pendingPermissionRequest = null
        val activity = hostActivity()
        if (!granted || req == null || !VinorSafe.isActivityAlive(activity) ||
            !VinorBridgeGate.isTrustedOrigin(req.origin?.toString())
        ) {
            runCatching { req?.deny() }
            return
        }
        grantPermittedResources(activity!!, req, req.resources ?: emptyArray())
    }

    private fun grantPermittedResources(
        activity: Activity,
        request: PermissionRequest,
        resources: Array<String>,
    ) {
        val allowed = resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasRecordAudioPermission(activity)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasCameraPermission(activity)
                else -> true
            }
        }.toTypedArray()
        runCatching {
            if (allowed.isEmpty()) request.deny() else request.grant(allowed)
        }
    }

    private fun showFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        val activity = hostActivity() ?: run {
            runCatching { callback?.onReceiveValue(null) }
            return false
        }
        if (!VinorSafe.isActivityAlive(activity)) {
            runCatching { callback?.onReceiveValue(null) }
            return false
        }

        filePathCallback?.let { runCatching { it.onReceiveValue(null) } }
        filePathCallback = callback

        val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() }.orEmpty()
        val acceptsImages = acceptTypes.isEmpty() ||
            acceptTypes.any { it.contains("image", ignoreCase = true) }

        val pickIntent = buildPickIntent(params)
        val initialIntents = mutableListOf<Intent>()
        if (acceptsImages) {
            createCameraIntent(activity)?.let { initialIntents.add(it) }
        }

        val chooserTitle = "انتخاب فایل"
        pendingChooserIntent = if (initialIntents.isEmpty()) {
            Intent.createChooser(pickIntent, chooserTitle)
        } else {
            Intent.createChooser(pickIntent, chooserTitle).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
            }
        }

        if (initialIntents.isNotEmpty() && !hasCameraPermission(activity)) {
            if (::permissionLauncher.isInitialized) {
                runCatching { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }
                    .onFailure { cancelCallback() }
            } else {
                cancelCallback()
            }
            return true
        }

        launchPendingChooser()
        return true
    }

    private fun launchPendingChooser() {
        val activity = hostActivity()
        val intent = pendingChooserIntent
        pendingChooserIntent = null
        if (activity == null || intent == null) {
            cancelCallback()
            return
        }
        try {
            chooserLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            cancelCallback()
        } catch (_: IllegalStateException) {
            cancelCallback()
        } catch (_: Exception) {
            cancelCallback()
        }
    }

    private fun buildPickIntent(params: WebChromeClient.FileChooserParams?): Intent {
        params?.createIntent()?.let { return@buildPickIntent it }
        val accept = params?.acceptTypes?.firstOrNull { it.isNotBlank() }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                !accept.isNullOrBlank() -> accept
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }
    }

    private fun createCameraIntent(activity: Activity): Intent? {
        return try {
            val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("capture_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file,
            )
            cameraOutputUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            cameraOutputUri = null
            null
        }
    }

    private fun deliverResult(success: Boolean, data: Intent?) {
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return

        if (!success) {
            runCatching { callback.onReceiveValue(null) }
            cameraOutputUri = null
            return
        }

        val results = mutableListOf<Uri>()
        data?.data?.let { results.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { results.add(it) }
                }
            }
        }
        if (results.isEmpty()) {
            cameraOutputUri?.let { results.add(it) }
        }
        cameraOutputUri = null

        if (results.isEmpty()) {
            runCatching { callback.onReceiveValue(null) }
        } else {
            runCatching { callback.onReceiveValue(results.toTypedArray()) }
        }
    }

    private fun cancelCallback() {
        runCatching { filePathCallback?.onReceiveValue(null) }
        filePathCallback = null
        cameraOutputUri = null
    }

    private fun hasCameraPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        fun configureWebViewSettings(settings: android.webkit.WebSettings) {
            settings.allowContentAccess = true
            // file:// در WebView همچنان غیرفعال می‌ماند؛ content:// از انتخابگر مجاز است
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
        }
    }
}
