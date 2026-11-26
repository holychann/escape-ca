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
 * used. Every line is also shown to the child as subtitles ([subtitle]), a piece at a time along the recording; a line
 * with no recording (no server, no key, every key refused) is shown at reading pace and [noSound] says so.
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

    /** The piece of the line on screen now (no speech tags in it); null when Lumi is not saying anything. */
    private val _subtitle = MutableStateFlow<String?>(null)
    val subtitle: StateFlow<String?> = _subtitle

    /** True when the line on screen has no sound at all, so its subtitles are the only way to get it. */
    private val _noSound = MutableStateFlow(false)
    val noSound: StateFlow<Boolean> = _noSound

    /** Tags the subtitle steps posted on [main], so a new line or stop() takes them all back. */
    private val steps = Any()

    /** Says [text], cutting off whatever Lumi was saying. Call on the main thread. */
    fun say(text: String) {
        last = text
        val mine = ++turn
        stopPlayer()
        _speaking.value = false
        _noSound.value = false
        val pieces = Subtitles.pieces(text)
        // the first piece is up at once; the rest follow the recording when it starts, or reading pace without one
        show(pieces, mine, null)
        val c = cache ?: return silent(pieces, mine)
        // a line kept on the device plays at once; any other is fetched first
        val kept = c.cached(text)
        if (kept != null) {
            play(kept, pieces, mine)
            return
        }
        now.execute {
            val file = c.get(text)
            main.post {
                if (mine != turn) return@post
                if (file != null) play(file, pieces, mine) else silent(pieces, mine)
            }
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
        main.removeCallbacksAndMessages(steps)
        _speaking.value = false
        _subtitle.value = null
        _noSound.value = false
    }

    /**
     * Puts [pieces] up one after another: along a recording [totalMs] long, or at reading pace when it is null. Along
     * a recording the last piece goes away a moment after the sound ends; without one it stays for the child to read.
     */
    private fun show(pieces: List<String>, mine: Int, totalMs: Long?) {
        main.removeCallbacksAndMessages(steps)
        if (pieces.isEmpty()) {
            _subtitle.value = null
            return
        }
        _subtitle.value = pieces.first()
        if (totalMs == null && !_noSound.value) return
        val starts = if (totalMs != null) Subtitles.starts(pieces, totalMs)
        else pieces.runningFold(0L) { at, p -> at + Subtitles.readMs(p) }.dropLast(1)
        pieces.zip(starts).drop(1).forEach { (piece, at) ->
            main.postDelayed({ if (mine == turn) _subtitle.value = piece }, steps, at)
        }
        if (totalMs != null) main.postDelayed({ if (mine == turn && player == null) _subtitle.value = null }, steps, totalMs + LINGER_MS)
    }

    /** No sound for this line: its subtitles are all the child gets. */
    private fun silent(pieces: List<String>, mine: Int) {
        if (mine != turn) return
        _speaking.value = false
        _noSound.value = true
        show(pieces, mine, null)
    }

    private fun play(file: File, pieces: List<String>, mine: Int) {
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
                    show(pieces, mine, it.duration.toLong().takeIf { d -> d > 0 } ?: pieces.sumOf(Subtitles::readMs))
                }
            }
            mp.setOnCompletionListener { done ->
                if (player !== done) return@setOnCompletionListener
                release(done)
                _speaking.value = false
                main.postDelayed({ if (mine == turn) _subtitle.value = null }, steps, LINGER_MS)
            }
            mp.setOnErrorListener { failed, what, extra ->
                if (player === failed) {
                    log("녹음 재생 오류 $what/$extra: ${file.name}")
                    release(failed)
                    _speaking.value = false
                    // no sound after all: the subtitles carry the line
                    silent(pieces, mine)
                }
                true
            }
            player = mp
            mp.prepareAsync()
        }.onFailure {
            log("녹음 재생 실패: ${it.message}")
            if (player === mp) player = null
            runCatching { mp.release() }
            silent(pieces, mine)
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

    companion object {
        /** How long the last piece stays after the sound ends. */
        const val LINGER_MS = 1500L
    }
}
