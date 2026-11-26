package kr.escape.car.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Lumi's voice: every line is a recording the story server made with Gemini's speech model in a picture-book
 * storyteller's voice (server app/voice.py), fetched once and kept on the device ([cache]). The car's own TTS is never
 * used. A line with no recording (no server, no key, every key refused) is shown as a caption ([caption]) instead.
 * The screen shows "말하는 중" while [speaking] is true and offers [repeat] for the parent.
 */
class Lumi(private val cache: VoiceCache?) {
    private val main = Handler(Looper.getMainLooper())
    /** say() fetches here, one line at a time; prefetch() has its own thread so it never holds a line up. */
    private val now = Executors.newSingleThreadExecutor()
    private val ahead = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private var last: String? = null
    /** Bumped by every say() and stop(), so a line that arrives late is not played over a newer one. */
    private var turn = 0

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    /** The line on screen while it has no sound (still being fetched, or none to be had); null while it plays. */
    private val _caption = MutableStateFlow<String?>(null)
    val caption: StateFlow<String?> = _caption

    /** Says [text], cutting off whatever Lumi was saying. Call on the main thread. */
    fun say(text: String) {
        last = text
        val mine = ++turn
        stopPlayer()
        _speaking.value = false
        _caption.value = text
        val c = cache ?: return
        // a line kept on the device plays at once; any other is fetched first
        val kept = c.cached(text)
        if (kept != null) {
            play(kept, text, mine)
            return
        }
        now.execute {
            val file = c.get(text)
            main.post { if (mine == turn && file != null) play(file, text, mine) }
        }
    }

    /** Fetches lines that are coming (the episode's story, questions and praise), so they play without a wait. */
    fun prefetch(lines: Collection<String>) {
        val c = cache ?: return
        val todo = lines.map(VoiceCache::tidy).filter { it.isNotEmpty() }.distinct()
        ahead.execute { todo.forEach { line -> if (c.cached(line) == null) c.get(line) } }
    }

    /** 다시 듣기. */
    fun repeat() {
        last?.let { say(it) }
    }

    fun stop() {
        turn++
        stopPlayer()
        _speaking.value = false
        _caption.value = null
    }

    private fun play(file: File, text: String, mine: Int) {
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                if (player === it && mine == turn) {
                    it.start()
                    _speaking.value = true
                    _caption.value = null
                }
            }
            mp.setOnCompletionListener { done ->
                if (player !== done) return@setOnCompletionListener
                release(done)
                _speaking.value = false
            }
            mp.setOnErrorListener { failed, what, extra ->
                if (player === failed) {
                    log("녹음 재생 오류 $what/$extra: ${file.name}")
                    release(failed)
                    _speaking.value = false
                    // no sound after all: the words stay on screen
                    if (mine == turn) _caption.value = text
                }
                true
            }
            player = mp
            mp.prepareAsync()
        }.onFailure {
            log("녹음 재생 실패: ${it.message}")
            if (player === mp) player = null
            runCatching { mp.release() }
            if (mine == turn) _caption.value = text
        }
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

    private fun log(msg: String) = Log.i("EscapeCar", msg)
}
