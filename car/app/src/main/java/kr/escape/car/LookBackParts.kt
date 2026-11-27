package kr.escape.car

import kr.escape.car.voice.Said

/**
 * 지난 자막's titles: every line is shown under the part of the episode it was said in (시작, 퍼즐 1, 퍼즐 2, …, 마무리),
 * so a parent who looks back finds a puzzle's words at a glance.
 */
object LookBackParts {
    /** The part of the episode [ui] is in, as 지난 자막 titles it; null where nothing of the episode is said. */
    fun of(ui: GameUi): String? = when (ui.phase) {
        Phase.INTRO -> "시작"
        Phase.PLAYING, Phase.SOLVED -> "퍼즐 ${ui.index + 1}"
        Phase.TREASURE -> "마무리"
        Phase.SETUP, Phase.RESTORE -> null
    }

    sealed interface Row {
        data class Title(val text: String) : Row
        data class Line(val text: String) : Row
    }

    /** [said] with a title before the first line of each part; a line said outside any part goes on under the last. */
    fun rows(said: List<Said>): List<Row> = buildList {
        var part: String? = null
        for (s in said) {
            if (s.part != null && s.part != part) {
                add(Row.Title(s.part))
                part = s.part
            }
            add(Row.Line(s.text))
        }
    }
}
