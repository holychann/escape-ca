package kr.escape.car.story

import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Questions
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import kr.escape.car.voice.Subtitles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class QuestionBankTest {
    private val json = """
        {"version": 1, "questions": [
         {"id": "m-l-001", "kind": "MATH", "dial": "FAN", "age": "LITTLE", "text": "조개 3개에 2개를 더 주우면 몇 개일까?", "hint": "손가락으로 세어 봐", "answer": 5, "expr": "3+2"},
         {"id": "m-c-001", "kind": "MATH", "dial": "FAN", "age": "CAPTAIN", "text": "사과 9개 중 4개를 먹으면 몇 개 남을까?", "hint": "하나씩 빼 봐", "answer": 5, "expr": "9-4"},
         {"id": "e-l-001", "kind": "ENGLISH", "dial": "LIGHTS", "age": "LITTLE", "text": "THREE는 우리말로 몇일까?", "hint": "원, 투, 하고 세어 봐", "answer": 3, "word": "THREE"},
         {"id": "p-l-001", "kind": "PATTERN", "dial": "LIGHTS", "age": "LITTLE", "text": "1, 2, 3, 그다음은 몇일까?", "hint": "하나씩 커져", "answer": 4, "terms": [1, 2, 3]},
         {"id": "ac-on-l-r01", "kind": "REASON", "dial": "AC", "age": "LITTLE", "text": "수달 요리사가 매운 걸 먹고 헥헥, 바람은 나오는데 안 시원하대. 알맞은 차량 버튼을 찾아 수달 요리사를 도와줘!", "hint": "시원한 바람을 만드는 버튼을 찾아봐", "answer": 1},
         {"id": "recirc-on-c-r01", "kind": "REASON", "dial": "RECIRC", "age": "CAPTAIN", "text": "옆 버스의 연기 냄새가 솔솔 들어와. 알맞은 차량 버튼을 찾아 펭귄 선장을 도와줘!", "hint": "바깥 공기를 막아 봐", "answer": 1},
         {"id": "r-l-001", "kind": "RIDDLE", "dial": "FAN", "age": "LITTLE", "text": "바람이 불면 빙글빙글 도는 장난감은?", "hint": "막대 끝에 날개가 달려 있어", "right": "바람개비", "wrong": ["팽이", "공", "인형"]},
         {"id": "x-l-001", "kind": "DANCE", "dial": "FAN", "age": "LITTLE", "text": "모르는 종류", "hint": "", "answer": 1}
        ]}
    """.trimIndent()

    private val bank = QuestionBank.parse(json)

    @Test
    fun entriesThisAppCannotUseAreLeftOut() {
        // RIDDLE and DANCE are kinds this app does not ask
        assertEquals(6, bank.size)
        assertEquals(0, QuestionBank.parse("not json").size)
    }

    @Test
    fun aQuestionMatchesTheKindTheAnswerAndTheAge() {
        val five = Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5)
        val (little, hint) = bank.pick(five, Age.LITTLE, Random(1))!!
        assertEquals("3+2", little.expr)
        assertEquals("손가락으로 세어 봐", hint)
        assertEquals("9-4", bank.pick(five, Age.CAPTAIN, Random(1))!!.first.expr)
        // nothing made for the oldest: an easier question is fine
        assertEquals("9-4", bank.pick(five, Age.DETECTIVE, Random(1))!!.first.expr)
        // no question works out to 7, and a question about the fan is never asked for the lights
        assertNull(bank.pick(Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 7), Age.LITTLE, Random(1)))
        assertNull(bank.pick(Puzzle.Quiz(QuizKind.MATH, Dial.LIGHTS, 5), Age.LITTLE, Random(1)))
        assertEquals(listOf(1, 2, 3), bank.pick(Puzzle.Quiz(QuizKind.PATTERN, Dial.LIGHTS, 4), Age.LITTLE, Random(1))!!.first.terms)
    }

    @Test
    fun aSituationIsAskedOnlyForItsOwnWay() {
        val acOn = Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1)
        val (q, hint) = bank.pick(acOn, Age.CAPTAIN, Random(1))!!
        assertEquals(QuizKind.REASON, q.kind)
        assertTrue(q.text.endsWith("알맞은 차량 버튼을 찾아 수달 요리사를 도와줘!"))
        assertEquals("시원한 바람을 만드는 버튼을 찾아봐", hint)
        assertEquals(Question(QuizKind.REASON, q.text), q)
        // written for switching it on, so never asked for switching it off
        assertNull(bank.pick(Puzzle.Quiz(QuizKind.REASON, Dial.AC, 0), Age.DETECTIVE, Random(1)))
        assertFalse(bank.has(Puzzle.Quiz(QuizKind.REASON, Dial.AC, 0), Age.DETECTIVE))
        // an easier age's question is fine, a harder one is not
        assertTrue(bank.has(acOn, Age.DETECTIVE))
        val recirc = Puzzle.Quiz(QuizKind.REASON, Dial.RECIRC, 1)
        assertTrue(bank.has(recirc, Age.CAPTAIN))
        assertFalse(bank.has(recirc, Age.LITTLE))
        assertNull(bank.pick(recirc, Age.LITTLE, Random(1)))
    }

    @Test
    fun asksOnlyItsOwnQuestion() {
        val five = Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5)
        val little = Question(QuizKind.MATH, "조개 3개에 2개를 더 주우면 몇 개일까?", expr = "3+2")
        val captain = Question(QuizKind.MATH, "사과 9개 중 4개를 먹으면 몇 개 남을까?", expr = "9-4")
        // the same words
        assertTrue(bank.asks(five, Age.LITTLE, little, "손가락으로 세어 봐"))
        // other words, or the same words with other parts
        assertFalse(bank.asks(five, Age.LITTLE, little.copy(text = "다른 글"), "손가락으로 세어 봐"))
        assertFalse(bank.asks(five, Age.LITTLE, little.copy(expr = "4+1"), "손가락으로 세어 봐"))
        // the hint is the bank's too: Lumi speaks it
        assertFalse(bank.asks(five, Age.LITTLE, little, "다른 힌트"))
        // a question made for an older child is not asked of a younger one
        assertFalse(bank.asks(five, Age.LITTLE, captain, "하나씩 빼 봐"))
        // a question made for a younger child is fine for an older one
        assertTrue(bank.asks(five, Age.CAPTAIN, little, "손가락으로 세어 봐"))
        assertTrue(bank.asks(five, Age.CAPTAIN, captain, "하나씩 빼 봐"))
        // and not for another answer or device
        assertFalse(bank.asks(Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 6), Age.CAPTAIN, little, "손가락으로 세어 봐"))
        assertFalse(bank.asks(Puzzle.Quiz(QuizKind.MATH, Dial.LIGHTS, 5), Age.CAPTAIN, little, "손가락으로 세어 봐"))
    }

    @Test
    fun anEmptyBankLeavesTheAppsOwnQuestions() {
        assertSame(bank, bank.questions)
        assertSame(Questions.ANY, QuestionBank.parse("not json").questions)
    }

    @Test
    fun theAppsOwnQuestionPlaysWithItsHint() {
        val p = Puzzle.Quiz(QuizKind.ENGLISH, Dial.LIGHTS, 3)
        val own = bank.pick(p, Age.LITTLE, Random(0))
        assertNotNull(own)
        val scene = Lines.scene(p, own)
        assertEquals("원, 투, 하고 세어 봐", scene.hint)
        assertTrue(scene.speech.contains("three는 우리말로 몇일까?"))
        assertEquals("THREE = ?", Lines.card(scene.question!!))
    }

    @Test
    fun theAppsOwnSituationEndsWithItsRequest() {
        val p = Puzzle.Quiz(QuizKind.REASON, Dial.AC, 1)
        val own = bank.pick(p, Age.LITTLE, Random(0))!!
        val scene = Lines.scene(p, own)
        assertEquals("시원한 바람을 만드는 버튼을 찾아봐", scene.hint)
        // no number instruction after it: the situation already asks for help with a car button
        assertTrue(scene.speech.endsWith(own.first.text))
        assertFalse(scene.speech.contains("안전 지킴이에게"))
        assertNull(Lines.card(own.first))
        assertEquals("에어컨 켜기", scene.answer)
    }

    // ------------------------------------------------------------ the bank the app ships

    private val shipped: QuestionBank by lazy {
        val file = File("src/main/assets/${QuestionBank.FILE}")
        assertTrue(file.absolutePath, file.isFile)
        QuestionBank.parse(file.readText())
    }

    @Test
    fun theShippedBankHasAQuestionForEveryQuizTheCarCanPlan() {
        var keys = 0
        for (dial in Dial.entries) for (kind in dial.kinds) for (value in dial.values(kind)) for (age in Age.entries) {
            val q = Puzzle.Quiz(kind, dial, value)
            if (!Planner.allowed(q, age)) continue
            assertTrue("$q $age", shipped.has(q, age))
            assertNotNull("$q $age", shipped.pick(q, age, Random(0)))
            keys++
        }
        assertTrue(keys > 0)
        // the youngest are never told about recirculation or cooling, and the bank has nothing for them there either
        for (q in listOf(Puzzle.Quiz(QuizKind.REASON, Dial.RECIRC, 1), Puzzle.Quiz(QuizKind.REASON, Dial.RECIRC, 0), Puzzle.Quiz(QuizKind.REASON, Dial.TEMP_DOWN, 1))) {
            assertFalse(Planner.allowed(q, Age.LITTLE))
            assertFalse(q.toString(), shipped.has(q, Age.LITTLE))
        }
    }

    @Test
    fun everyKeyThePlannerMakesForAnAgeIsInTheShippedBank() {
        val r = Random(11)
        val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet())
        repeat(1500) { seed ->
            fun <T> maybe(v: T): T? = if (r.nextInt(6) == 0) null else v
            val car = parked.copy(
                fanSpeed = maybe(r.nextInt(0, 9)),
                tempLeft = maybe(16f + r.nextInt(0, 29) * 0.5f),
                acOn = maybe(r.nextBoolean()),
                recirculation = maybe(r.nextBoolean()),
                defrostFront = maybe(r.nextBoolean()),
                seatHeat = maybe(r.nextInt(0, 4)),
                readingLights = Seat.entries.associateWith { r.nextBoolean() },
            )
            val age = Age.entries.random(r)
            val setup = Setup(ages = listOf(age), minutes = 15)
            // planned as if every key had a question: the bank must have one for each all the same
            for (q in Planner.plan(setup, car, Random(seed)).filterIsInstance<Puzzle.Quiz>()) {
                assertTrue("$q $age", shipped.has(q, age))
            }
        }
    }

    private val shippedJson by lazy { JSONObject(File("src/main/assets/${QuestionBank.FILE}").readText()).getJSONArray("questions") }

    @Test
    fun aShippedEnglishSumIsWrittenOnTheCard() {
        val all = (0 until shippedJson.length()).map { shippedJson.getJSONObject(it) }
        val o = all.first { it.getString("id") == "fan-d-088" }
        val q = Question(QuizKind.ENGLISH, o.getString("text"), expr = o.optString("expr"), word = o.optString("word"))
        assertEquals("seven 빼기 five는 얼마일까? 그 수만큼 바람 세기를 맞춰 줘!", q.text)
        assertEquals("SEVEN - FIVE = ?", Lines.card(q))
        assertEquals("풍량 2단 (SEVEN - FIVE = 2)", Lines.answer(Puzzle.Quiz(QuizKind.ENGLISH, Dial.FAN, 2), q))
        assertFalse(Lines.countsLetters(q))
        // every English sum in the bank gets its card
        val sums = all.filter { it.getString("kind") == "ENGLISH" && it.optString("expr").isNotBlank() }
        assertTrue(sums.isNotEmpty())
        for (e in sums) {
            val card = Lines.card(Question(QuizKind.ENGLISH, e.getString("text"), expr = e.getString("expr"), word = e.optString("word")))
            assertEquals(e.getString("id"), "${Lines.pretty(e.getString("expr").uppercase())} = ?", card)
        }
    }

    @Test
    fun theYoungestNeverGetAnEnglishNumberAboveFive() {
        val fan6 = Puzzle.Quiz(QuizKind.ENGLISH, Dial.FAN, 6)
        assertFalse(Planner.allowed(fan6, Age.LITTLE))
        assertTrue(Planner.allowed(Puzzle.Quiz(QuizKind.ENGLISH, Dial.FAN, 5), Age.LITTLE))
        assertTrue(Planner.allowed(fan6, Age.CAPTAIN))
        assertFalse(shipped.has(fan6, Age.LITTLE))
        // planned without the bank too, when the app asks its own questions
        val r = Random(3)
        val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet(), fanSpeed = 1)
        var english = 0
        repeat(800) { seed ->
            val car = parked.copy(readingLights = Seat.entries.associateWith { r.nextBoolean() }, seatHeat = r.nextInt(0, 4))
            val setup = Setup(ages = listOf(Age.LITTLE, Age.DETECTIVE), minutes = 15)
            for (questions in listOf(Questions.ANY, shipped)) {
                for (q in Planner.plan(setup, car, Random(seed), questions).filterIsInstance<Puzzle.Quiz>()) {
                    if (q.kind != QuizKind.ENGLISH) continue
                    english++
                    assertTrue("$q", q.value <= Planner.LITTLE_ENGLISH_MAX)
                }
            }
        }
        assertTrue(english > 0)
    }

    @Test
    fun noShippedLetterCountShowsItsWordToTheChild() {
        val list = shippedJson
        var counted = 0
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("kind") != "ENGLISH") continue
            val q = Question(QuizKind.ENGLISH, o.getString("text"), word = o.optString("word", ""))
            if (!Lines.countsLetters(q)) continue
            counted++
            val p = Puzzle.Quiz(QuizKind.ENGLISH, Dial.valueOf(o.getString("dial")), o.getInt("answer"))
            val scene = Lines.scene(p, q to o.getString("hint"))
            val word = q.word.trim()
            val seen = listOfNotNull(Lines.card(q), scene.bubble) + Subtitles.pieces(scene.speech) +
                Subtitles.pieces(scene.hint)
            for (s in seen) assertFalse("${o.getString("id")}: $s", Regex("(?<![A-Za-z])$word(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(s))
            assertTrue(o.getString("id"), Lines.card(q)!!.endsWith("→ 영어 몇 글자?"))
        }
        assertTrue(counted > 0)
    }

    @Test
    fun everyShippedSituationAsksForHelpWithACarButton() {
        val situations = Dial.entries.filter { QuizKind.REASON in it.kinds }.flatMap { d ->
            d.values(QuizKind.REASON).flatMap { v -> Age.entries.mapNotNull { a -> shipped.pick(Puzzle.Quiz(QuizKind.REASON, d, v), a, Random(0)) } }
        }
        assertTrue(situations.isNotEmpty())
        for ((q, _) in situations) assertTrue(q.text, q.text.contains("차량 버튼을 찾아") && q.text.trimEnd().endsWith("도와줘!"))
    }
}
