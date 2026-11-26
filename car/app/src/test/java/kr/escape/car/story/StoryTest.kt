package kr.escape.car.story

import kr.escape.car.GameUi
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import kotlin.random.Random

class StoryTest {
    private val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet())

    /** Every climate value known, so situations are planned too. */
    private val known = parked.copy(
        fanSpeed = 3, tempLeft = 21f, acOn = false, recirculation = false, defrostFront = false, seatHeat = 0,
        readingLights = Seat.entries.associateWith { false },
    )

    /** Every quiz a key can name: each kind, dial and value the server knows. */
    private val everyQuiz: List<Puzzle.Quiz> =
        Dial.entries.flatMap { d -> d.kinds.flatMap { k -> d.values(k).map { v -> Puzzle.Quiz(k, d, v) } } }

    private val fan = Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5)
    private val lights = Puzzle.Quiz(QuizKind.ENGLISH, Dial.LIGHTS, 2)
    private val heat = Puzzle.Quiz(QuizKind.ENGLISH, Dial.SEAT_HEAT, 3)
    private val cold = Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_DOWN, 2)
    private val ac = Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1)
    private val defrost = Puzzle.Quiz(QuizKind.REASON, Dial.DEFROST, 1)

    // ------------------------------------------------------------ answer keys

    @Test
    fun keysUseTheServerFormat() {
        assertEquals("Q:MATH:FAN:5", StoryKeys.key(fan))
        assertEquals("Q:ENGLISH:LIGHTS:2", StoryKeys.key(lights))
        assertEquals("Q:PATTERN:TEMP_DOWN:3", StoryKeys.key(Puzzle.Quiz(QuizKind.PATTERN, Dial.TEMP_DOWN, 3)))
        assertEquals("Q:REASON:AC:0", StoryKeys.key(Puzzle.Quiz(QuizKind.REASON, Dial.AC, 0)))
        assertEquals("Q:REASON:TEMP_UP:1", StoryKeys.key(Puzzle.Quiz(QuizKind.REASON, Dial.TEMP_UP, 1)))
        assertEquals("Q:REASON:LIGHTS_OFF:0", StoryKeys.key(Puzzle.Quiz(QuizKind.REASON, Dial.LIGHTS_OFF, 0)))
        assertEquals("BELT", StoryKeys.key(Puzzle.BeltMission))
    }

    @Test
    fun storedKeysComeBackAsTheSamePuzzles() {
        for (p in everyQuiz + Puzzle.BeltMission) assertEquals(p, StoryKeys.puzzle(StoryKeys.key(p)))
        repeat(50) { seed ->
            for (car in listOf(parked, known)) {
                for (p in Planner.plan(Setup(minutes = 15), car, Random(seed))) assertEquals(p, StoryKeys.puzzle(StoryKeys.key(p)))
            }
        }
    }

    @Test
    fun keysThisAppCannotPlayAreRejected() {
        val bad = listOf(
            // values out of the dial's range
            "Q:MATH:FAN:0", "Q:MATH:FAN:9", "Q:ENGLISH:LIGHTS:5", "Q:ENGLISH:SEAT_HEAT:4", "Q:MATH:TEMP_UP:4",
            "Q:REASON:AC:2", "Q:REASON:AC:-1", "Q:REASON:RECIRC:2", "Q:REASON:DEFROST:0", "Q:REASON:SEAT_HEAT_OFF:1",
            "Q:REASON:LIGHTS_OFF:1", "Q:REASON:FAN_UP:2",
            // a situation on the temperature only says which way
            "Q:REASON:TEMP_UP:2", "Q:REASON:TEMP_DOWN:3",
            // a situation device asks no number, a number device tells no situation, and small answers are no
            // number sequence
            "Q:MATH:AC:1", "Q:ENGLISH:AC:0", "Q:PATTERN:AC:1", "Q:MATH:FAN_UP:1", "Q:ENGLISH:LIGHT_CHILD:1",
            "Q:REASON:FAN:3", "Q:REASON:LIGHTS:2", "Q:REASON:SEAT_HEAT:1",
            "Q:PATTERN:TEMP_UP:2", "Q:PATTERN:SEAT_HEAT:1",
            // junk and keys of the old puzzles
            "Q:RIDDLE:AC:1", "Q:RIDDLE:FAN:3", "", "BELT ", "belt", "Q", "Q:MATH:FAN", "Q:MATH:FAN:5:1", "X:MATH:FAN:5", "Q:SPELLING:FAN:5", "Q:math:FAN:5",
            "Q:MATH:WIND:5", "Q:MATH:FAN:five", "SEAT:REAR_RIGHT", "WIND:FLOOR:STRONG", "DUST:OUTSIDE", "FOG",
        )
        for (key in bad) assertNull(key, StoryKeys.puzzle(key))
    }

    // ------------------------------------------------------------ questions

    @Test
    fun aQuestionSurvivesJson() {
        val questions = listOf(
            Question(QuizKind.MATH, "조개 2개에 3개를 더하면?", expr = "2+3"),
            Question(QuizKind.ENGLISH, "영어로 TWO!", word = "TWO"),
            Question(QuizKind.PATTERN, "2, 4, 6, 그다음은?", terms = listOf(2, 4, 6)),
            Question(QuizKind.REASON, "앞 유리 안쪽이 뿌예지려고 해. 차 버튼으로 펭귄 선장을 도와줘!"),
        )
        for (q in questions) {
            assertEquals(q, Question.parse(q.toJson()))
            assertEquals(q, Question.parse(JSONObject(q.toJson().toString())))
        }
        // only kind and text are needed
        assertEquals(Question(QuizKind.MATH, "1 더하기 1은?"), Question.parse(JSONObject("""{"kind":"MATH","text":"1 더하기 1은?"}""")))
    }

    @Test
    fun aQuestionThisAppCannotAskIsLeftOut() {
        assertNull(Question.parse(JSONObject("""{"kind":"SPELLING","text":"몇 글자?"}""")))
        assertNull(Question.parse(JSONObject("""{"kind":"MATH","text":"  "}""")))
        assertNull(Question.parse(JSONObject("""{"kind":"MATH","expr":"2+3"}""")))
        assertNull(Question.parse(JSONObject("""{"text":"2 더하기 3은?"}""")))
        // a kind this app does not ask
        assertNull(Question.parse(JSONObject("""{"kind":"RIDDLE","text":"누구?","choices":[{"text":"토끼","value":1}]}""")))
    }

    // ------------------------------------------------------------ the story server's words

    private val json = """
        {"storyId":"0123456789abcdef0123456789abcdef","model":"m","theme":"SEA_SUB","title":"잠수함 대소동",
         "intro":"안녕!","treasureBadge":"배지!",
         "scenes":{
           "Q:MATH:FAN:5":{"bubble":"바람이 필요해!","speech":"잠수함 새싹이 시들었어.","hint":"손가락으로 세어 봐!",
             "question":{"kind":"MATH","text":"조개 2개에 3개를 더하면 몇 개일까?","expr":"2+3"},
             "result":"바람이 솔솔, 새싹이 다시 일어났어!"},
           "Q:ENGLISH:LIGHTS:2":{"bubble":"깜깜해!","speech":"등대가 꺼졌어.","hint":"세어 봐!",
             "question":{"kind":"MATH","text":"1 더하기 1은?","expr":"1+1"},"result":"등대가 반짝!"},
           "Q:ENGLISH:SEAT_HEAT:3":{"bubble":"추워!","speech":"의자가 차가워.","hint":"세어 봐!"},
           "Q:MATH:TEMP_DOWN:2":{"bubble":"더워!","speech":"방이 더워.","hint":"세어 봐!",
             "question":{"kind":"SPELLING","text":"몇 글자?"}},
           "Q:REASON:DEFROST:1":{"bubble":"앞이 안 보여!","speech":"잠수함 창이 흐려졌어.","hint":"앞 유리를 맑게 하는 버튼!",
             "question":{"kind":"REASON","text":"펭귄 선장의 입김에 앞 유리 안쪽이 뿌예지려고 해. 차 버튼으로 펭귄 선장을 도와줘!"},
             "result":""},
           "Q:REASON:LIGHT_CHILD:1":{"bubble":"글씨가 안 보여!","speech":"보물 지도가 작아.","hint":"네 자리를 밝게!",
             "question":{"kind":"REASON","text":"보물 지도 글씨가 너무 작아. 차 버튼으로 보물 찾기를 도와줘!"},
             "result":"네 자리가 환해져서 지도가 잘 보여!"},
           "Q:REASON:LIGHTS_OFF:0":{"bubble":"잠이 안 와!","speech":"아기 해마가 졸려.","hint":"불을 모두 꺼 봐!",
             "question":{"kind":"REASON","text":"아기 해마가 낮잠을 자려는데 불빛 때문에 잠이 안 온대. 차 버튼으로 아기 해마를 도와줘!"},
             "result":"깜깜해지자 아기 해마가 새근새근 잠들었어!"},
           "BELT":{"bubble":"딸깍!","speech":"벨트를 딸깍!","hint":"딸깍 소리!","result":"딸깍! 출발 준비 끝!"}},
         "dropped":["Q:REASON:AC:1"]}
    """.trimIndent()

    @Test
    fun theServersQuestionIsAskedWithTheAppsOwnAskLineAndAnswer() {
        val story = Story.parse(JSONObject(json))
        val scene = story.scene(fan)
        val base = Lines.scene(fan)
        val q = scene.question!!
        assertEquals("조개 2개에 3개를 더하면 몇 개일까?", q.text)
        assertEquals("바람이 필요해!", scene.bubble)
        assertEquals("손가락으로 세어 봐!", scene.hint)
        assertEquals("잠수함 새싹이 시들었어. 조개 2개에 3개를 더하면 몇 개일까? " + Lines.ask(fan, q), scene.speech)
        assertEquals("풍량 5단 (2 + 3 = 5)", scene.answer)
        assertEquals(base.pictures, scene.pictures)
        assertEquals(base.buttons, scene.buttons)
    }

    @Test
    fun theServersSituationIsToldWithNothingAfterIt() {
        val story = Story.parse(JSONObject(json))
        val scene = story.scene(defrost)
        val q = scene.question!!
        assertEquals(QuizKind.REASON, q.kind)
        assertEquals("앞이 안 보여!", scene.bubble)
        assertEquals("앞 유리를 맑게 하는 버튼!", scene.hint)
        // the situation already asks for help with a car button, so the app adds no instruction of its own
        assertEquals("잠수함 창이 흐려졌어. 펭귄 선장의 입김에 앞 유리 안쪽이 뿌예지려고 해. 차 버튼으로 펭귄 선장을 도와줘!", scene.speech)
        assertEquals("앞 유리 성에 제거 켜기", scene.answer)
        assertEquals(listOf("앞 유리 성에 제거"), scene.buttons)
        assertNull(Lines.card(q))
    }

    @Test
    fun withoutAMatchingQuestionTheAppAsksItsOwn() {
        val story = Story.parse(JSONObject(json))
        // the server's question is of another kind
        assertEquals(Lines.scene(lights), story.scene(lights))
        // no question at all
        assertEquals(Lines.scene(heat), story.scene(heat))
        // a question this app cannot ask
        assertEquals(Lines.scene(cold), story.scene(cold))
        // dropped by the server's checker
        assertEquals(Lines.scene(ac), story.scene(ac))
        // the belt mission has no question, so the server's words are used
        assertEquals("벨트를 딸깍!", story.scene(Puzzle.BeltMission).speech)
        assertEquals(Lines.scene(Puzzle.BeltMission).pictures, story.scene(Puzzle.BeltMission).pictures)
        assertEquals("theme_sea_sub", story.themePicture)
        assertEquals(story, Story.parse(story.toJson()))
    }

    @Test
    fun anOlderStoryStillPlays() {
        // written before the friend told the story: generic praise, a gift line, a new friend, and no scene results
        val old = JSONObject(json).put("success", JSONArray(listOf("최고야!"))).put("treasureGift", "선물!")
            .put("friend", JSONObject().put("name", "꼬마 해마"))
        old.getJSONObject("scenes").keys().forEach { old.getJSONObject("scenes").getJSONObject(it).remove("result") }
        val story = Story.parse(old)
        assertEquals("배지!", story.treasureBadge)
        assertTrue(story.scenes.values.all { it.result.isEmpty() })
        assertEquals("", story.scene(fan).result)
        assertEquals(story, Story.parse(story.toJson()))
    }

    // ------------------------------------------------------------ when a puzzle is solved

    private fun solvedLine(p: Puzzle, story: Story? = Story.parse(JSONObject(json))) =
        GameUi(puzzles = listOf(Puzzle.BeltMission, p), index = 1, story = story).solvedLine

    @Test
    fun aSolvedPuzzleEndsWithItsScenesResultLine() {
        val story = Story.parse(JSONObject(json))
        assertEquals("바람이 솔솔, 새싹이 다시 일어났어!", story.scene(fan).result)
        assertEquals("바람이 솔솔, 새싹이 다시 일어났어!", solvedLine(fan))
        assertEquals("딸깍! 출발 준비 끝!", GameUi(puzzles = listOf(Puzzle.BeltMission), story = story).solvedLine)
        // a result the server dropped
        assertEquals(Lines.SUCCESS, solvedLine(defrost))
        // the scene is played from the app's own lines, so the story's line would follow words the child never heard
        assertEquals(Lines.SUCCESS, solvedLine(lights))
        // dropped by the server, or no story at all
        assertEquals(Lines.SUCCESS, solvedLine(ac))
        assertEquals(Lines.SUCCESS, solvedLine(fan, story = null))
    }

    @Test
    fun aQuizSwappedAsItStartsEndsWithItsOwnResultLine() {
        val lightChild = Puzzle.Quiz(QuizKind.REASON, Dial.LIGHT_CHILD, 1)
        // the child's light was switched on during an earlier puzzle: the app tells the opposite story instead
        val lit = known.copy(readingLights = known.readingLights + (Seat.REAR_RIGHT to true))
        val puzzles = Planner.fitted(listOf(Puzzle.BeltMission, lightChild), 1, lit, Setup(), Random(0))
        val swapped = Puzzle.Quiz(QuizKind.REASON, Dial.LIGHTS_OFF, 0)
        assertEquals(listOf(Puzzle.BeltMission, swapped), puzzles)
        val ui = GameUi(puzzles = puzzles, index = 1, story = Story.parse(JSONObject(json)))
        assertEquals("깜깜해지자 아기 해마가 새근새근 잠들었어!", ui.solvedLine)
        assertEquals("네 자리가 환해져서 지도가 잘 보여!", solvedLine(lightChild))
    }

    @Test
    fun theFriendGetsCloserInItsOwnWords() {
        assertEquals("우리 한층 더 친해졌어!", Lines.CLOSER)
    }

    @Test
    fun aDrawnPictureReplacesTheScenesOwnOnlyOnceItIsOnTheDevice() {
        val base = Story.parse(JSONObject(json))
        val key = StoryKeys.key(fan)
        val words = base.scenes.getValue(key).copy(picture = "p1")
        val fromServer = base.copy(scenes = base.scenes + (key to words), images = mapOf("p1" to "/v1/images/x/p1.webp"))
        // still on the server: the app's own picture
        assertEquals(Lines.scene(fan).pictures, fromServer.scene(fan).pictures)
        val local = fromServer.copy(images = mapOf("p1" to "/data/story_images/x/p1.webp", "cover" to "/data/story_images/x/cover.webp"))
        assertEquals(listOf("file:/data/story_images/x/p1.webp"), local.scene(fan).pictures)
        assertEquals("file:/data/story_images/x/cover.webp", local.picture("cover"))
        assertEquals(null, local.picture("ending"))
        assertEquals(local, Story.parse(local.toJson()))
    }

    @Test
    fun aDroppedSceneKeepsItsPuzzlesDrawnPicture() {
        val base = Story.parse(JSONObject(json))
        val story = base.copy(
            images = mapOf("p2" to "/data/story_images/x/p2.webp", "p3" to "/data/story_images/x/p3.webp"),
            scenePictures = mapOf(StoryKeys.key(ac) to "p2", StoryKeys.key(lights) to "p3"),
        )
        // dropped by the server: the app's own words, today's picture
        val scene = story.scene(ac)
        assertEquals(Lines.scene(ac).speech, scene.speech)
        assertEquals(listOf("file:/data/story_images/x/p2.webp"), scene.pictures)
        // a question of another kind: the app's own question, today's picture too
        val other = story.scene(lights)
        assertEquals(Lines.scene(lights).speech, other.speech)
        assertEquals(listOf("file:/data/story_images/x/p3.webp"), other.pictures)
        assertEquals(story, Story.parse(story.toJson()))
    }

    // ------------------------------------------------------------ the app's own lines

    /** Works out "(4*3)-7": brackets first, then * and /, then + and -. */
    private class Calc(expr: String) {
        private val s = expr.replace(" ", "")
        private var i = 0

        fun value(): Int = sum().also { check(i == s.length) { "left over in $s" } }

        private fun sum(): Int {
            var v = product()
            while (i < s.length && s[i] in "+-") {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun product(): Int {
            var v = factor()
            while (i < s.length && s[i] in "*/") {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        private fun factor(): Int {
            if (s[i] == '(') {
                i++
                return sum().also { check(s[i++] == ')') }
            }
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toInt()
        }
    }

    @Test
    fun theAppsOwnQuestionsWorkOutToTheAnswer() {
        assertEquals(5, Calc("(4*3)-7").value())
        for (p in everyQuiz) {
            val q = Lines.question(p)
            val name = StoryKeys.key(p)
            assertEquals(name, p.kind, q.kind)
            assertTrue(name, q.text.isNotBlank())
            when (p.kind) {
                QuizKind.MATH -> assertEquals(name, p.value, Calc(q.expr).value())
                QuizKind.ENGLISH -> {
                    assertEquals(name, p.value, Lines.NUMBER_WORDS.indexOf(q.word) + 1)
                    assertTrue(name, q.text.contains(q.word))
                }
                QuizKind.PATTERN -> {
                    assertTrue(name, q.terms.size >= 3 && q.terms.all { it >= 0 })
                    val step = q.terms[1] - q.terms[0]
                    assertNotEquals(name, 0, step)
                    assertTrue(name, q.terms.zipWithNext().all { (a, b) -> b - a == step })
                    assertEquals(name, p.value, q.terms.last() + step)
                }
                QuizKind.REASON -> {
                    // the bank's form: the trouble, then a request for help with a car button, never the button's name
                    assertTrue(name, q.text.contains("차 버튼으로") && q.text.endsWith("도와줘!"))
                    assertTrue(name, q.expr.isEmpty() && q.word.isEmpty() && q.terms.isEmpty())
                    for (w in ownButton.getValue(p.dial)) assertFalse("$name: $w", q.text.contains(w))
                }
            }
            // the same puzzle always gets the same question, so the parent's answer and Lumi's words agree
            assertEquals(name, q, Lines.question(p.copy()))
            assertEquals(name, q, Lines.scene(p).question)
        }
    }

    /** The words a situation never says for its own function (server quiz.REASON_OWN). */
    private val ownButton = mapOf(
        Dial.AC to listOf("에어컨"), Dial.RECIRC to listOf("내기", "외기", "순환"), Dial.DEFROST to listOf("성에"),
        Dial.TEMP_UP to listOf("온도", "히터"), Dial.TEMP_DOWN to listOf("온도", "히터"),
        Dial.FAN_UP to listOf("풍량", "바람 세기"), Dial.FAN_DOWN to listOf("풍량", "바람 세기"),
        Dial.SEAT_HEAT_ON to listOf("열선"), Dial.SEAT_HEAT_OFF to listOf("열선"),
        Dial.LIGHT_CHILD to listOf("독서등"), Dial.LIGHTS_OFF to listOf("독서등"),
    )

    @Test
    fun lumiAlwaysEndsWithTheAppsOwnAskLine() {
        for (p in everyQuiz) {
            val scene = Lines.scene(p)
            val q = scene.question!!
            val ask = Lines.ask(p, q)
            val name = StoryKeys.key(p)
            if (p.kind == QuizKind.REASON) {
                // a situation ends with its own request, and nothing follows it
                assertEquals(name, "", ask)
                assertTrue(name, scene.speech.endsWith(q.text))
                assertFalse(name, scene.speech.contains("안전 지킴이에게"))
                continue
            }
            assertTrue(name, ask.contains("안전 지킴이"))
            assertTrue(name, scene.speech.endsWith(ask))
            if (p.kind != QuizKind.ENGLISH) assertTrue(name, scene.speech.contains(q.text))
        }
    }

    @Test
    fun noLineUsesAMiddleDot() {
        val lines = everyQuiz.flatMap { p -> Lines.scene(p).let { listOf(p.title, it.bubble, it.speech, it.hint, it.answer) + it.buttons } } +
            listOf(Lines.AUTO_OFF, Lines.AUTO_OFF_PARENT)
        for (line in lines) assertFalse(line, line.contains('·'))
    }

    @Test
    fun lumiSaysTheEnglishWordInSmallLetters() {
        val p = Puzzle.Quiz(QuizKind.ENGLISH, Dial.FAN, 2)
        val q = Question(QuizKind.ENGLISH, "영어로 TWO! 우리말로 몇일까?", word = "TWO")
        assertEquals("새싹이 목말라. 영어로 two! 우리말로 몇일까? " + Lines.ask(p, q), Lines.spoken(" 새싹이 목말라. ", q, p))
        // no story before the question: Lumi starts with the question
        assertTrue(Lines.spoken("  ", q, p).startsWith("영어로 two!"))
        assertFalse(Lines.scene(Puzzle.Quiz(QuizKind.ENGLISH, Dial.LIGHTS, 3)).speech.contains("THREE"))
    }

    @Test
    fun aSituationIsToldThenLeftToTheChild() {
        val q = Question(QuizKind.REASON, "수달 요리사가 매운 걸 먹고 헥헥, 바람은 나오는데 안 시원하대. 차 버튼으로 수달 요리사를 도와줘!")
        assertEquals("", Lines.ask(ac, q))
        assertEquals("부엌이 시끌벅적해. ${q.text}", Lines.spoken(" 부엌이 시끌벅적해. ", q, ac))
        assertEquals("에어컨 켜기", Lines.answer(ac, q))
        assertNull(Lines.card(q))
        val scene = Lines.scene(ac, q to "시원한 바람을 떠올려 봐")
        assertEquals("시원한 바람을 떠올려 봐", scene.hint)
        assertEquals(listOf("에어컨"), scene.buttons)
        // the story before it never names the button
        assertFalse(scene.speech.removeSuffix(q.text).contains("에어컨"))
    }

    @Test
    fun theParentsAnswerShowsHowTheQuestionWorksOut() {
        assertEquals("풍량 5단 ((4 × 3) - 7 = 5)", Lines.answer(fan, Question(QuizKind.MATH, "?", expr = "(4*3)-7")))
        assertEquals("(4 × 3) - 7 = ?", Lines.card(Question(QuizKind.MATH, "?", expr = "(4*3)-7")))
        val down = Puzzle.Quiz(QuizKind.ENGLISH, Dial.TEMP_DOWN, 3)
        assertEquals("운전석 온도를 퍼즐 시작 때보다 3도 내리기 (THREE = 3)", Lines.answer(down, Question(QuizKind.ENGLISH, "?", word = "three")))
        assertEquals("THREE = ?", Lines.card(Question(QuizKind.ENGLISH, "?", word = "three")))
        val four = Puzzle.Quiz(QuizKind.ENGLISH, Dial.LIGHTS, 4)
        assertEquals("독서등 모두 4개 켜기 (STAR, 4글자)", Lines.answer(four, Question(QuizKind.ENGLISH, "?", word = "star")))
        assertEquals("STAR 몇 글자?", Lines.card(Question(QuizKind.ENGLISH, "?", word = "star")))
        val pattern = Puzzle.Quiz(QuizKind.PATTERN, Dial.FAN, 8)
        assertEquals("풍량 8단 (2, 4, 6 다음은 8)", Lines.answer(pattern, Question(QuizKind.PATTERN, "?", terms = listOf(2, 4, 6))))
        assertEquals("2, 4, 6, ?", Lines.card(Question(QuizKind.PATTERN, "?", terms = listOf(2, 4, 6))))
        // a question without its parts still says what to set
        assertEquals("운전석 열선 2단", Lines.answer(Puzzle.Quiz(QuizKind.MATH, Dial.SEAT_HEAT, 2), Question(QuizKind.MATH, "?")))
        assertEquals("운전석 온도를 퍼즐 시작 때보다 1도 올리기", Lines.answer(Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_UP, 1), Question(QuizKind.MATH, "?")))
        // a situation says what to do, with nothing to work out
        val situation = Question(QuizKind.REASON, "?")
        assertEquals("운전석 온도를 퍼즐 시작 때보다 올리기", Lines.answer(Puzzle.Quiz(QuizKind.REASON, Dial.TEMP_UP, 1), situation))
        assertEquals("내기 순환 끄기 (외기 유입)", Lines.answer(Puzzle.Quiz(QuizKind.REASON, Dial.RECIRC, 0), situation))
        assertEquals("아이 자리 독서등 켜기", Lines.answer(Puzzle.Quiz(QuizKind.REASON, Dial.LIGHT_CHILD, 1), situation))
        assertEquals("풍량을 퍼즐 시작 때보다 약하게 (끄지 않기)", Lines.answer(Puzzle.Quiz(QuizKind.REASON, Dial.FAN_DOWN, 1), situation))
    }

    @Test
    fun everyScenePictureAndItsSolvedPictureShipWithTheApp() {
        val img = File("src/main/assets/img")
        assertTrue(img.absolutePath, img.isDirectory)
        for (p in everyQuiz + Puzzle.BeltMission) {
            val scene = Lines.scene(p).pictures.single()
            val solved = Lines.successPicture(p)
            assertNotEquals(scene, solved)
            for (name in listOf(scene, solved)) assertTrue(name, File(img, "$name.webp").isFile)
        }
        assertTrue(File(img, Lines.successPicture(null) + ".webp").isFile)
    }

    // ------------------------------------------------------------ the request

    @Test
    fun aWishAsksForThatStoryAndItsPictures() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 18, 30) }
        val body = StoryRepo.request("00000000-0000-0000-0000-000000000000", Setup(wish = "  공룡 섬에서 보물찾기 "), listOf(Puzzle.BeltMission), now)
        assertEquals("공룡 섬에서 보물찾기", body.getString("wish"))
        assertEquals(true, body.getBoolean("pictures"))
    }

    @Test
    fun requestCarriesOnlyEnumsNumbersAndAnswerKeys() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 18, 30) }
        val puzzles = listOf(fan, Puzzle.BeltMission, Puzzle.Quiz(QuizKind.REASON, Dial.AC, 0))
        val body = StoryRepo.request("00000000-0000-0000-0000-000000000000", Setup(friend = Friend.CASPIN), puzzles, now, lesson = "GRIT")
        assertEquals(setOf("installId", "ages", "minutes", "context", "friend", "puzzles", "lesson"), body.keySet())
        assertEquals("CASPIN", body.getString("friend"))
        assertEquals("GRIT", body.getString("lesson"))
        assertEquals("AUTUMN", body.getJSONObject("context").getString("season"))
        assertEquals("EVENING", body.getJSONObject("context").getString("timeOfDay"))
        // a fan amount and the belt mission go only one way; the air conditioner off also has the story of on
        assertEquals("""[["Q:MATH:FAN:5"],["BELT"],["Q:REASON:AC:0","Q:REASON:AC:1"]]""", body.getJSONArray("puzzles").toString())
        assertEquals("BANTE", StoryRepo.request("00000000-0000-0000-0000-000000000000", Setup(), puzzles, now).getString("friend"))
    }

    private fun slots(setup: Setup, puzzles: List<Puzzle>): List<List<String>> {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 18, 30) }
        val a = StoryRepo.request("00000000-0000-0000-0000-000000000000", setup, puzzles, now).getJSONArray("puzzles")
        return List(a.length()) { i -> a.getJSONArray(i).let { s -> List(s.length()) { s.getString(it) } } }
    }

    @Test
    fun aQuizSlotAlsoNamesTheQuizTheAppMaySwitchTo() {
        val warm = Puzzle.Quiz(QuizKind.REASON, Dial.TEMP_UP, 1)
        val puzzles = listOf(
            cold, Puzzle.Quiz(QuizKind.REASON, Dial.LIGHT_CHILD, 1), Puzzle.BeltMission, warm,
            Puzzle.Quiz(QuizKind.REASON, Dial.SEAT_HEAT_OFF, 0), defrost,
        )
        assertEquals(
            listOf(
                listOf("Q:MATH:TEMP_DOWN:2", "Q:MATH:TEMP_UP:2"),
                listOf("Q:REASON:LIGHT_CHILD:1", "Q:REASON:LIGHTS_OFF:0"),
                listOf("BELT"),
                listOf("Q:REASON:TEMP_UP:1", "Q:REASON:TEMP_DOWN:1"),
                listOf("Q:REASON:SEAT_HEAT_OFF:0", "Q:REASON:SEAT_HEAT_ON:1"),
                // the defroster only goes on
                listOf("Q:REASON:DEFROST:1"),
            ),
            slots(Setup(), puzzles),
        )
        // the youngest (4~6) are never told a "cool the cabin" story, so the app never switches to one for them
        val little = Setup(ages = listOf(Age.CAPTAIN, Age.LITTLE))
        assertEquals(listOf(listOf("Q:REASON:TEMP_UP:1")), slots(little, listOf(warm)))
        // a number on the temperature may go the other way at any age
        assertEquals(listOf(listOf("Q:MATH:TEMP_DOWN:2", "Q:MATH:TEMP_UP:2")), slots(little, listOf(cold)))
    }

    @Test
    fun everyPlannedRequestNamesEachKeyOnceWithThePlannedOneFirst() {
        repeat(200) { seed ->
            for (car in listOf(parked, known)) {
                for (ages in listOf(listOf(Age.LITTLE), listOf(Age.CAPTAIN), listOf(Age.DETECTIVE))) {
                    val setup = Setup(ages = ages, minutes = 15)
                    val puzzles = Planner.plan(setup, car, Random(seed))
                    val slots = slots(setup, puzzles)
                    assertEquals(puzzles.map(StoryKeys::key), slots.map { it.first() })
                    val keys = slots.flatten()
                    // the server takes at most 16 keys and refuses one named twice
                    assertTrue(keys.size <= 16)
                    assertEquals(keys.size, keys.toSet().size)
                    // whatever the app may switch to at the start is a quiz it can play
                    for (k in keys) assertNotNull(k, StoryKeys.puzzle(k))
                }
            }
        }
    }

    @Test
    fun everyEpisodeAsksForOneOfTheTenLessonsAtRandom() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 1, 18, 30) }
        assertEquals(10, StoryRepo.LESSONS.toSet().size)
        val picked = List(400) {
            StoryRepo.request("00000000-0000-0000-0000-000000000000", Setup(), listOf(Puzzle.BeltMission), now).getString("lesson")
        }.toSet()
        // every lesson comes up, and nothing outside the list (the server refuses an unknown one)
        assertEquals(StoryRepo.LESSONS.toSet(), picked)
    }

    @Test
    fun particlesFollowTheLastSyllable() {
        assertEquals("코냥과", Lines.withAnd("코냥"))
        assertEquals("반테와", Lines.withAnd("반테"))
        assertEquals("캐스핀이", Lines.subject("캐스핀"))
        assertEquals("쏠랑이가", Lines.subject("쏠랑이"))
        assertEquals("코냥이야", Lines.isName("코냥"))
        assertEquals("리아야", Lines.isName("리아"))
        assertEquals("토끼면", Lines.ifIs("토끼"))
        assertEquals("별이면", Lines.ifIs("별"))
        assertEquals("눈사람이면", Lines.ifIs("눈사람 "))
    }

    @Test
    fun onlyRecordingsOnTheDeviceArePlayed() {
        val o = JSONObject("""
            {"storyId":"s1","theme":"STAR_TRAIN","title":"t","intro":"i","treasureBadge":"b","scenes":{},
             "voices":{"intro":"/v1/audio/s1/intro.wav","treasureBadge":"/data/story_images/s1/voice/treasureBadge.wav"}}
        """.trimIndent())
        val story = Story.parse(o)
        // a server path is not played; it must be downloaded first
        assertNull(story.voice("intro"))
        assertEquals("/data/story_images/s1/voice/treasureBadge.wav", story.voice("treasureBadge"))
        assertEquals(story, Story.parse(story.toJson()))
        // a story saved before recordings existed still parses
        o.remove("voices")
        assertEquals(emptyMap<String, String>(), Story.parse(o).voices)
    }
}
