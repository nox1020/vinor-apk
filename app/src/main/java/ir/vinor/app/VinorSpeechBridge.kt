package ir.vinor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * تشخیص گفتار بومی اندروید — fallback وقتی Web Speech API در WebView نیست.
 */
class VinorSpeechBridge(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView?,
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingStart = false
    private var sessionFinished = false

    private val audioPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!VinorSafe.isActivityAlive(activity)) return@registerForActivityResult
        if (granted && pendingStart) {
            pendingStart = false
            startListeningInternal()
        } else {
            pendingStart = false
            notifyWeb("error", "not-allowed")
        }
    }

    @JavascriptInterface
    fun isNativeAvailable(): Boolean {
        return runCatching { SpeechRecognizer.isRecognitionAvailable(activity.applicationContext) }
            .getOrDefault(false)
    }

    @JavascriptInterface
    fun startNative() {
        activity.runOnUiThread {
            if (!VinorSafe.isActivityAlive(activity)) return@runOnUiThread
            if (!SpeechRecognizer.isRecognitionAvailable(activity.applicationContext)) {
                notifyWeb("error", "unavailable")
                return@runOnUiThread
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingStart = true
                runCatching { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    .onFailure {
                        pendingStart = false
                        notifyWeb("error", "not-allowed")
                    }
                return@runOnUiThread
            }
            startListeningInternal()
        }
    }

    @JavascriptInterface
    fun stopNative() {
        activity.runOnUiThread {
            pendingStart = false
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
        }
    }

    fun destroy() {
        pendingStart = false
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun startListeningInternal() {
        if (!VinorSafe.isActivityAlive(activity)) {
            notifyWeb("error", "unavailable")
            return
        }
        sessionFinished = false
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(activity.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "createSpeechRecognizer failed", e)
            notifyWeb("error", "unavailable")
            return
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                notifyWeb("start", "")
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (sessionFinished) return
                sessionFinished = true
                val code = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NO_MATCH -> "no-speech"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    else -> "error"
                }
                notifyWeb("error", code)
            }

            override fun onResults(results: Bundle?) {
                if (sessionFinished) return
                sessionFinished = true
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    notifyWeb("complete", text)
                } else {
                    notifyWeb("error", "no-speech")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) notifyWeb("partial", text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "جستجو را بگویید…")
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            notifyWeb("error", "error")
        }
    }

    private fun notifyWeb(type: String, payload: String) {
        val safeType = type.replace("'", "")
        val safePayload = payload
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
        VinorSafe.evalJs(
            webViewProvider(),
            "window.__vinorVoiceNativeEvent && window.__vinorVoiceNativeEvent('$safeType','$safePayload');",
        )
    }

    companion object {
        private const val TAG = "VinorSpeechBridge"
        const val JS_INTERFACE_NAME = "VinorSpeech"
    }
}
