package kr.escape.car.story

import kr.escape.car.engine.Dial
import kr.escape.car.engine.Puzzle
import org.junit.Assert.assertFalse
import org.junit.Test

class LinesTest {
    // the app's own scene and praise also play inside an AI story (a scene the checks left out, an empty result), so
    // they never name a place of the default star train story
    private val train = Regex("기차|칸")

    @Test
    fun `the lines that can play in any story name no train`() {
        val quizzes = Dial.entries.flatMap { d -> d.kinds.flatMap { k -> d.values(k).map { Puzzle.Quiz(k, d, it) } } }
        val lines = quizzes.map { Lines.scene(it).speech } + Lines.scene(Puzzle.BeltMission).speech + Lines.SUCCESS
        for (line in lines) assertFalse(line, train.containsMatchIn(line))
    }
}
