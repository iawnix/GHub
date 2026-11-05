package dev.remotegpt.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*
import kotlinx.coroutines.flow.MutableStateFlow

class SpeechInput(private val context: Context, private val onText: (String) -> Unit, private val onError: (String) -> Unit) {
    val listening = MutableStateFlow(false)
    private var recognizer: SpeechRecognizer? = null
    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError(tr(R.string.ui_speech_recognition_is_unavailable_you_can_use)); return }
        cancel()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (recognizer === r) listening.value = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (recognizer === r) listening.value = false }
            override fun onError(error: Int) {
                if (recognizer !== r) return
                listening.value = false
                onError(when (error) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr(R.string.ui_no_speech_detected_please_try_again); SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tr(R.string.ui_microphone_permission_is_required_for_voice_input); else -> tr(R.string.ui_speech_recognition_is_unavailable_please_use_the) })
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return
                listening.value = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        listening.value = true
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }
    fun cancel() {
        val previous = recognizer
        recognizer = null
        listening.value = false
        previous?.cancel()
        previous?.destroy()
    }
}
