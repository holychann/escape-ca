package kr.escape.car.engine

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The friend book: the same six friends the pictures draw and the story server may write about. */
enum class Friend(val label: String, val picture: String) {
    SPROUT_ROBOT("새싹 로봇", "crew_sprout_robot"),
    OTTER_COOK("수달 요리사", "crew_otter_cook"),
    PENGUIN_CAPTAIN("펭귄 선장", "crew_penguin_captain"),
    GRUMPY_MACHINE("투덜이 기계", "crew_grumpy_machine"),
    FENNEC_FOX("사막여우", "crew_fennec_fox"),
    RABBIT_ENGINEER("토끼 기관사", "crew_rabbit_engineer");

    val silhouette: String get() = "${picture}_silhouette"

    companion object {
        /** The friend a puzzle helps; solving it the first time puts that friend in the book. */
        fun of(p: Puzzle): Friend? = when (p) {
            is Puzzle.WindPath -> SPROUT_ROBOT
            is Puzzle.DustCloud -> OTTER_COOK
            is Puzzle.TwoRooms -> PENGUIN_CAPTAIN
            Puzzle.FoggyWindow -> GRUMPY_MACHINE
            is Puzzle.SeatSearch -> FENNEC_FOX
            is Puzzle.Together -> RABBIT_ENGINEER
            Puzzle.BeltMission -> null
        }
    }
}

/** Which friends this car has met, kept across episodes in one small file (friend names, one per line). */
class FriendBook(private val file: File) {
    private val _met = MutableStateFlow(load())
    val met: StateFlow<Set<Friend>> = _met

    /** Returns true when [f] is new to the book. */
    fun meet(f: Friend): Boolean {
        if (f in _met.value) return false
        _met.value = _met.value + f
        runCatching { file.writeText(_met.value.joinToString("\n") { it.name }) }
        return true
    }

    private fun load(): Set<Friend> = runCatching {
        file.readLines().mapNotNull { line -> Friend.entries.firstOrNull { it.name == line.trim() } }.toSet()
    }.getOrDefault(emptySet())
}
