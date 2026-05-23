package ir.vinor.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

    private lateinit var chooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

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
        ) { launchPendingChooser() }
    }

    fun register(fragment: Fragment) {
        chooserLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            deliverResult(result.resultCode == Activity.RESULT_OK, result.data)
        }
        permissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { launchPendingChooser() }
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
        }
    }

    private fun showFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        val activity = hostActivity() ?: run {
            callback?.onReceiveValue(null)
            return false
        }

        filePathCallback?.onReceiveValue(null)
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
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
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
            callback.onReceiveValue(null)
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
            callback.onReceiveValue(null)
        } else {
            callback.onReceiveValue(results.toTypedArray())
        }
    }

    private fun cancelCallback() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        cameraOutputUri = null
    }

    private fun hasCameraPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
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
