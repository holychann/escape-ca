package kr.escape.car.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Lumi's voice. A line the story server read aloud (a picture-book storyteller, see server/app/voice.py) is played
 * from its recording; every other line, and any recording that will not play, is read by the car's own TTS (Pleos
 * TextToSpeech, on-device Korean), which has no rate, pitch or style control. The screen shows "말하는 중" while
 * [speaking] is true and offers [repeat] for the parent.
 */
class Lumi(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var player: MediaPlayer? = null

    /** The last line as it was asked for, for [repeat]. */
    private var last: Line? = null

    private data class Line(val text: String, val audio: String?, val then: String?)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    init {
        // a car without the speech service (or permission) just stays silent; the game still runs on screen
        runCatching { setUp(context.applicationContext) }.onFailure { log("목소리 SDK 사용 불가: ${it.message}") }
    }

    private fun setUp(context: Context) {
        val t = TextToSpeech(context, TtsMode.ON_DEVICE)
        tts = t
        // while a recording plays, the TTS's own stop (cut off by the recording) must not clear "말하는 중"
        val events = object : EventListener {
            override fun onStart() = main.post { _speaking.value = true }.let { }
            override fun onReady() {}
            override fun onDone() = main.post { if (player == null) _speaking.value = false }.let { }
            override fun onError(error: TtsError) = main.post { if (player == null) _speaking.value = false; log("목소리 오류: $error") }.let { }
            override fun onStop() = main.post { if (player == null) _speaking.value = false }.let { }
            override fun onUpdatedRms(rms: Double) {}
        }
        t.initialize(object : TtsInit {
            override fun onSuccess() = main.post {
                ready = true
                // the SDK only accepts listeners once initialize() has set it up
                runCatching { t.addEventListener(events) }
                runCatching { t.setLocale(Locale.KOREA) }
                runCatching { t.setSpeakerType(VOICE) }.onFailure { log("목소리 바꾸기 실패: ${it.message}") }
                pending?.let { speak(it) }
                pending = null
            }.let { }
            override fun onFailure(error: TtsError) = main.post { log("목소리 초기화 실패: $error") }.let { }
        })
    }

    /**
     * Says [text], cutting off whatever Lumi was saying. With [audio] (a recorded WAV on the device) the recording
     * plays instead, and [then] is read by the car's voice right after it; without a recording, or when it fails to
     * play, the car's voice reads [text] and [then].
     */
    fun say(text: String, audio: String? = null, then: String? = null) {
        last = Line(text, audio, then)
        // a line still waiting for the TTS engine must not start over this one
        pending = null
        stopPlayer()
        val file = audio?.let(::File)?.takeIf { it.isFile }
        if (file == null || !play(file, then)) speak(join(text, then))
    }

    /** 다시 듣기: the last line again, recording and all. */
    fun repeat() {
        last?.let { say(it.text, it.audio, it.then) }
    }

    fun stop() {
        stopPlayer()
        runCatching { tts?.stop() }
        _speaking.value = false
    }

    private fun join(text: String, then: String?) = if (then.isNullOrBlank()) text else "$text $then"

    /** Starts the recording; false when it cannot (the caller reads the line with the car's voice instead). */
    private fun play(file: File, then: String?): Boolean {
        val line = last
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { if (player === it) it.start() }
            mp.setOnCompletionListener { done ->
                if (player !== done) return@setOnCompletionListener
                release(done)
                if (then.isNullOrBlank()) _speaking.value = false else speak(then)
            }
            mp.setOnErrorListener { failed, what, extra ->
                if (player === failed) {
                    log("녹음 재생 오류 $what/$extra: ${file.name}")
                    release(failed)
                    // same words with the car's voice, so the child still hears the clue
                    line?.let { speak(join(it.text, it.then)) }
                }
                true
            }
            // the TTS may still be reading the line before this one
            runCatching { tts?.stop() }
            player = mp
            _speaking.value = true
            mp.prepareAsync()
        }.onFailure {
            log("녹음 재생 실패: ${it.message}")
            if (player === mp) player = null
            runCatching { mp.release() }
        }.isSuccess
    }

    private fun release(mp: MediaPlayer) {
        if (player === mp) player = null
        runCatching { mp.release() }
    }

    private fun stopPlayer() {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        runCatching { mp.release() }
    }

    /** The car's own voice. Before the engine is ready the last line waits. */
    private fun speak(text: String) {
        val t = tts ?: run { _speaking.value = false; return }
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

    private fun log(msg: String) = Log.i("EscapeCar", msg)

    companion object {
        /**
         * The car's Korean voices in Gleo AI's own order (설정 > 목소리 > 음성 1~6): SEOYOON, EUNWOO, HAEUN, SANGHYEOK,
         * SIA, DOHYUN. Lumi speaks with 음성 5, SIA, when a line has no recording.
         */
        const val VOICE = "SIA"
    }
}
