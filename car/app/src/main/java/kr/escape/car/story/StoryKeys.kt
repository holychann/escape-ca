package kr.escape.car.story

import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Rhythm
import kr.escape.car.engine.Seat

/**
 * The answer keys shared with the story server (server/app/keys.py). A key names a puzzle with its answer, so the
 * server writes words for exactly that answer and the app looks the words up by the puzzle it is playing.
 */
object StoryKeys {
    fun key(p: Puzzle): String = when (p) {
        is Puzzle.WindPath ->
            "WIND:${if (p.direction == FanDir.FACE) "FACE" else "FLOOR"}:${if (gentle(p)) "GENTLE" else "STRONG"}"
        is Puzzle.DustCloud -> if (p.fromOutside) "DUST:OUTSIDE" else "DUST:INSIDE"
        is Puzzle.TwoRooms -> if (p.penguinLeft) "ROOMS:LEFT" else "ROOMS:RIGHT"
        Puzzle.FoggyWindow -> "FOG"
        is Puzzle.SeatSearch -> "SEAT:${p.seat.name}"
        is Puzzle.Together -> "TOGETHER:${p.rhythm.name}:${if (p.childReachesLight) "REACH" else "TIMING"}"
        Puzzle.BeltMission -> "BELT"
    }

    /** Same split as [Lines]: the gentle answer starts at the lowest steps. */
    private fun gentle(p: Puzzle.WindPath) = p.speed.first <= 2

    /** The planned answer first, then every answer [Planner.refresh] may switch it to when the puzzle starts. */
    fun variants(p: Puzzle): List<Puzzle> = when (p) {
        is Puzzle.WindPath -> listOf(p, p.copy(direction = if (p.direction == FanDir.FACE) FanDir.FLOOR else FanDir.FACE))
        is Puzzle.DustCloud -> listOf(p, p.copy(fromOutside = !p.fromOutside))
        is Puzzle.TwoRooms -> listOf(p, p.copy(penguinLeft = !p.penguinLeft))
        is Puzzle.SeatSearch -> listOf(p) + Seat.entries.filter { it != p.seat }.map { p.copy(seat = it) }
        else -> listOf(p)
    }

    /** Back from a stored key, for an episode kept on disk until the next drive. */
    fun puzzle(key: String, fanSpeedRange: IntRange, childSeat: Seat): Puzzle? {
        val parts = key.split(":")
        return runCatching {
            when (parts[0]) {
                "WIND" -> Puzzle.WindPath(
                    if (parts[1] == "FACE") FanDir.FACE else FanDir.FLOOR,
                    Planner.windSpeed(fanSpeedRange, gentle = parts[2] == "GENTLE"),
                )
                "DUST" -> Puzzle.DustCloud(fromOutside = parts[1] == "OUTSIDE")
                "ROOMS" -> Puzzle.TwoRooms(penguinLeft = parts[1] == "LEFT")
                "FOG" -> Puzzle.FoggyWindow
                "SEAT" -> Puzzle.SeatSearch(Seat.valueOf(parts[1]))
                "TOGETHER" -> Puzzle.Together(childSeat, Rhythm.valueOf(parts[1]), childReachesLight = parts[2] == "REACH")
                "BELT" -> Puzzle.BeltMission
                else -> null
            }
        }.getOrNull()
    }
}
