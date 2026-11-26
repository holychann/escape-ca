package kr.escape.car.voice

import kotlinx.coroutines.flow.StateFlow

/** What the game needs from Lumi's voice ([Lumi]), so a test can listen instead of the car's speakers. */
interface Voice {
    val speaking: StateFlow<Boolean>

    /** Says [text], cutting off whatever was being said. */
    fun say(text: String)

    /** Says [text] like [say], but [repeat] still repeats the line before it. */
    fun aside(text: String)

    /** 다시 듣기. */
    fun repeat()

    fun stop()
}
