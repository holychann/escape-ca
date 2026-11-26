package kr.escape.car

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kr.escape.car.engine.Book
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Seat
import kr.escape.car.guide.Rulebook
import kr.escape.car.story.StoryRepo
import kr.escape.car.ui.Clay
import kr.escape.car.ui.Fonts
import kr.escape.car.voice.Lumi
import kr.escape.car.voice.Subtitles
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * The screens as PNG files, drawn on the JVM by Robolectric (no emulator): 「놀이 전 약속」, [사용법] and the child's
 * subtitles, landscape (2560 × 1440, the car's screen) and portrait. They land in the build folder's screenshots/
 * (build.gradle.kts: screenshots.dir), C:\dev\build\escape-car\app\screenshots on Windows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").also { it.mkdirs() }
    private val book = Rulebook.parse(File(System.getProperty("rulebook.file") ?: "../../docs/차근차근탐험단_룰북.md").readText())

    /** A parked car with a parent in the driver seat and every device the puzzles use. */
    private val car = CarState(
        gear = Gear.PARK, driverSeated = true, hvacPower = true, recirculation = false, fanSpeed = 3,
        fanDirection = FanDir.FACE, dual = false, tempLeft = 20f, tempRight = 20f, defrostFront = false,
        readingLights = Seat.entries.associateWith { false }, acOn = false, seatHeat = 0, devices = Device.entries.toSet(),
    )

    /**
     * Robolectric draws with a native library it unpacks at run time. A PC whose app control (Windows Smart App
     * Control) blocks unsigned libraries cannot load it; there the screenshots are skipped, not failed, and they are
     * made on macOS, Linux or CI instead.
     */
    private fun shot(name: String, sheet: Sheet, playing: Boolean = false, subtitle: String? = null) {
        blocked?.let { throw AssumptionViolatedException("native graphics blocked on this PC, no screenshot: $it") }
        try {
            draw(name, sheet, playing, subtitle)
        } catch (e: UnsatisfiedLinkError) {
            blocked = e.message
            throw AssumptionViolatedException("native graphics blocked on this PC, no screenshot: ${e.message}")
        }
    }

    private fun draw(name: String, sheet: Sheet, playing: Boolean, subtitle: String?) {
        Fonts.load(ApplicationProvider.getApplicationContext<Context>().assets)
        val lumi = Lumi(null)
        val game = Game(MutableStateFlow(car), lumi, rnd = Random(7))
        if (playing) {
            game.begin()
            game.startPuzzle(0)
            // [사용법] opened during play holds the game, as the screen does
            if (sheet == Sheet.GUIDE) game.hold()
        }
        val file = File(dir, "$name.png")
        captureRoboImage(file.path) {
            MaterialTheme(colorScheme = lightColorScheme(primary = Clay.Ink, background = Clay.NightBottom, surface = Clay.Cream)) {
                Screen(
                    game, game.ui.value, car, Book(), speaking = subtitle != null, reading = false, subtitle = subtitle,
                    view = StoryView(StoryRepo.Status.NONE, null, emptyList()), subs = Subs(true) {}, lumi = lumi,
                    rulebook = book, sheet = sheet, onSheet = {}, skippedToday = false, onSkipToday = {},
                )
            }
        }
        assertTrue("$file not written", file.isFile && file.length() > 0)
    }

    /** The first piece of the friend's greeting, as the subtitle bar shows it. */
    private val greeting = Subtitles.pieces("안녕, 탐험대장! 나는 반테야. 오늘 밤 별 축제에 가야 하는데 별빛 기차 칸 곳곳이 고장 났대.").first()

    @Test @Config(qualifiers = LAND)
    fun promiseLandscape() = shot("promise_land", Sheet.PROMISE)

    @Test @Config(qualifiers = PORT)
    fun promisePortrait() = shot("promise_port", Sheet.PROMISE)

    @Test @Config(qualifiers = LAND)
    fun guideLandscape() = shot("guide_land", Sheet.GUIDE, playing = true)

    @Test @Config(qualifiers = PORT)
    fun guidePortrait() = shot("guide_port", Sheet.GUIDE, playing = true)

    @Test @Config(qualifiers = LAND)
    fun subtitleLandscape() = shot("subtitle_land", Sheet.NONE, playing = true, subtitle = greeting)

    @Test @Config(qualifiers = PORT)
    fun subtitlePortrait() = shot("subtitle_port", Sheet.NONE, playing = true, subtitle = greeting)

    companion object {
        /** Why the native graphics library could not load, once it has failed: every later screenshot is skipped too. */
        private var blocked: String? = null

        /** The car's screen: 2560 × 1440 at 160 dpi. */
        const val LAND = "w2560dp-h1440dp-land-mdpi"
        const val PORT = "w1440dp-h2560dp-port-mdpi"
    }
}
