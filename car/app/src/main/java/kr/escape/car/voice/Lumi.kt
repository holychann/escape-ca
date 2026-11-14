package kr.escape.car.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Lumi's voice: the car's own TTS (Pleos TextToSpeech, on-device Korean) reads the story and the clues through the
 * car speakers. The screen shows "말하는 중" while [speaking] is true and offers [repeat] for the parent.
 */
class Lumi(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var last: String? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    init {
        // a car without the speech service (or permission) just stays silent; the game still runs on screen
        runCatching { setUp(context.applicationContext) }.onFailure { log("목소리 SDK 사용 불가: ${it.message}") }
    }

    private fun setUp(context: Context) {
        val t = TextToSpeech(context, TtsMode.ON_DEVICE)
        tts = t
        val events = object : EventListener {
            override fun onStart() = main.post { _speaking.value = true }.let { }
            override fun onReady() {}
            override fun onDone() = main.post { _speaking.value = false }.let { }
            override fun onError(error: TtsError) = main.post { _speaking.value = false; log("목소리 오류: $error") }.let { }
            override fun onStop() = main.post { _speaking.value = false }.let { }
            override fun onUpdatedRms(rms: Double) {}
        }
        t.initialize(object : TtsInit {
            override fun onSuccess() = main.post {
                ready = true
                // the SDK only accepts listeners once initialize() has set it up
                runCatching { t.addEventListener(events) }
                runCatching { t.setLocale(Locale.KOREA) }
                runCatching { t.setSpeakerType(VOICE) }.onFailure { log("목소리 바꾸기 실패: ${it.message}") }
                pending?.let { say(it) }
                pending = null
            }.let { }
            override fun onFailure(error: TtsError) = main.post { log("목소리 초기화 실패: $error") }.let { }
        })
    }

    /** Says [text], cutting off whatever Lumi was saying. Before the engine is ready the last line waits. */
    fun say(text: String) {
        last = text
        val t = tts ?: return
        if (!ready) {
            pending = text
            return
        }
        _speaking.value = true
        runCatching { t.speak(text, true) }.onFailure {
            _speaking.value = false
            log("목소리 실패: ${it.message}")
        }
    }

    /** 다시 듣기. */
    fun repeat() {
        last?.let { say(it) }
    }

    fun stop() {
        runCatching { tts?.stop() }
        _speaking.value = false
    }

    private fun log(msg: String) = Log.i("EscapeCar", msg)

    companion object {
        /**
         * The car's Korean voices in Gleo AI's own order (설정 > 목소리 > 음성 1~6): SEOYOON, EUNWOO, HAEUN, SANGHYEOK,
         * SIA, DOHYUN. Lumi speaks with 음성 5, SIA.
         */
        const val VOICE = "SIA"
    }
}
