package kr.escape.car.story

import kr.escape.car.engine.Dial
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind

/**
 * The answer keys shared with the story server (server app/quiz.py). A key names a puzzle with its answer, so the
 * server writes a question for exactly that answer and the app looks the words up by the puzzle it is playing.
 * A quiz is "Q:<kind>:<dial>:<value>" (Q:MATH:FAN:5, Q:REASON:AC:1); the belt mission is "BELT".
 */
object StoryKeys {
    fun key(p: Puzzle): String = when (p) {
        is Puzzle.Quiz -> "Q:${p.kind.name}:${p.dial.name}:${p.value}"
        Puzzle.BeltMission -> "BELT"
    }

    /** Back from a stored key, for an episode kept on disk until the next drive; null for a key this app cannot play. */
    fun puzzle(key: String): Puzzle? {
        if (key == "BELT") return Puzzle.BeltMission
        val parts = key.split(":")
        if (parts.size != 4 || parts[0] != "Q") return null
        return runCatching {
            val dial = Dial.valueOf(parts[2])
            val value = parts[3].toInt()
            val kind = QuizKind.valueOf(parts[1])
            if (kind !in dial.kinds || value !in dial.values(kind)) null else Puzzle.Quiz(kind, dial, value)
        }.getOrNull()
    }
}
