package kr.escape.car

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.escape.car.car.VehicleSource
import kr.escape.car.engine.FriendBook
import kr.escape.car.story.FriendIntro
import kr.escape.car.story.HeardStories
import kr.escape.car.story.ProgressStore
import kr.escape.car.story.QuestionBank
import kr.escape.car.story.StoryClient
import kr.escape.car.story.StoryRepo
import kr.escape.car.voice.Lumi
import kr.escape.car.voice.VoiceCache
import java.io.File
import java.util.UUID

/**
 * Everything one drive needs, kept while Android rebuilds the screen (night mode, a config change): the episode the
 * child is playing goes on where it was instead of starting over.
 */
class GameModel(app: Application) : AndroidViewModel(app) {
    val source = VehicleSource(app).also { it.start() }
    private val client = StoryClient(BuildConfig.STORY_URL)
    private val install = installId(app)
    // every line is read by the story server's Gemini voice and kept on the device; the car's TTS is not used
    val lumi = Lumi(VoiceCache(File(app.filesDir, "voice_cache")) { text -> client.speak(install, text) })
    private val bank = QuestionBank.load(app)
    val stories = StoryRepo(
        File(app.filesDir, "next_episode.json"), install, client, viewModelScope,
        questions = bank.questions, bank = bank, heard = HeardStories(File(app.filesDir, "heard_stories.json")),
        log = { Log.i("EscapeCar", it) },
    )
    val book = FriendBook(File(app.filesDir, "friend_book"))
    val game = Game(
        source.state, lumi, stories, book, restorer = source, bank = bank,
        progress = ProgressStore(File(app.filesDir, "playing_episode.json")),
    )

    init {
        // 지난 자막 puts each line under the part of the episode it was said in
        lumi.part = { LookBackParts.of(game.ui.value) }
        // the choices the ready story was written for (its wish, the ages), so it is ready again after a restart
        stories.savedSetup()?.let { saved -> game.setup { saved } }
        keepBundledVoice(app)
    }

    /**
     * The friends' self-introductions on the waiting screen ship recorded (assets/voice, named by friend), so they play
     * at once while the server is writing, and with no server at all. Each goes into the voice cache under the name
     * [VoiceCache] gives its line.
     */
    private fun keepBundledVoice(app: Application) = Thread {
        val dir = File(app.filesDir, "voice_cache").apply { mkdirs() }
        val shipped = app.assets.list("voice").orEmpty().toSet()
        FriendIntro.RECORDINGS.forEach { (name, line) ->
            if ("$name.wav" !in shipped) return@forEach
            val to = File(dir, VoiceCache.key(VoiceCache.tidy(line)) + ".wav")
            if (to.isFile) return@forEach
            val part = File(to.path + ".part")
            runCatching {
                app.assets.open("voice/$name.wav").use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(to)
            }.onFailure { part.delete() }
        }
    }.start()

    override fun onCleared() {
        lumi.stop()
        source.stop()
    }

    /** A random id made once per install, only for the story server's daily limit. */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences("story", Context.MODE_PRIVATE)
        return prefs.getString("installId", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("installId", it).apply() }
    }
}
