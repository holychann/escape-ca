package kr.escape.car.engine

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** Only the directions the puzzles talk about; anything else the car reports is OTHER. */
enum class FanDir { FACE, FLOOR, FACE_AND_FLOOR, DEFROST, OTHER, UNKNOWN }

enum class Seat(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽")
}

/** Car devices a quiz answer can go into. A car that lacks one gets a different puzzle. */
enum class Device { FAN_SPEED, TEMPERATURE, READING_LIGHT, SEAT_HEAT, AC, RECIRCULATION, DEFROST }

/**
 * One snapshot of everything the game reads from the car. Null means "not known yet" (the SDK has not answered,
 * or the car does not have it), which never counts as a solved puzzle or a safe start.
 */
data class CarState(
    val gear: Gear = Gear.UNKNOWN,
    val speedMps: Float = 0f,
    val uxRestricted: Boolean = false,
    val doorOpen: Boolean = false,
    val driverSeated: Boolean? = null,
    val hvacPower: Boolean? = null,
    val recirculation: Boolean? = null,
    val fanSpeed: Int? = null,
    val fanSpeedRange: IntRange = 1..8,
    val fanDirection: FanDir = FanDir.UNKNOWN,
    /** HVAC_DUAL_ON: true couples every zone to the driver's temperature, so the two rooms need it off. */
    val dual: Boolean? = null,
    val tempLeft: Float? = null,
    val tempRight: Float? = null,
    val defrostFront: Boolean? = null,
    val readingLights: Map<Seat, Boolean> = emptyMap(),
    val acOn: Boolean? = null,
    /** The driver seat's heating step (0 is off). */
    val seatHeat: Int? = null,
    val seatHeatMax: Int = 3,
    val devices: Set<Device> = emptySet(),
)

/** Why the game cannot start or has to stop. The parent sees one picture and one sentence for each. */
enum class StopReason(val message: String) {
    DRIVING("기어를 P로 두면 이어서 할 수 있어요"),
    DOOR_OPEN("문이 닫히면 이어서 할 수 있어요"),
    NO_DRIVER("운전석에 보호자가 앉아 있어야 시작할 수 있어요"),
    HVAC_OFF("공조 전원을 켜야 시작할 수 있어요"),
    TIME_UP("20분이 지났어요. 잠깐 쉬어요"),
}

/**
 * The car only watches: the game runs while parked with a parent in the driver seat, and stops the moment
 * anyone opens a door or leaves P. Seat belts and rear seats are not read (left for a later stage).
 */
object Safety {
    const val MAX_PLAY_MS = 20 * 60 * 1000L
    const val RECIRCULATION_LIMIT_MS = 3 * 60 * 1000L
    private const val STILL_MPS = 0.1f

    private fun driving(s: CarState) = s.gear != Gear.PARK || s.speedMps > STILL_MPS || s.uxRestricted

    fun startBlockers(s: CarState): List<StopReason> = buildList {
        if (driving(s)) add(StopReason.DRIVING)
        if (s.doorOpen) add(StopReason.DOOR_OPEN)
        if (s.driverSeated != true) add(StopReason.NO_DRIVER)
        // temperatures cannot be read with the climate off, so the climate puzzles would never count.
        // Unknown (null: the car does not report it) does not block.
        if (s.hvacPower == false) add(StopReason.HVAC_OFF)
    }

    /** First reason to pause right now, or null to keep playing. Driving wins over a door so the parent sees why. */
    fun pauseReason(s: CarState, playedMs: Long): StopReason? = when {
        driving(s) -> StopReason.DRIVING
        s.doorOpen -> StopReason.DOOR_OPEN
        playedMs >= MAX_PLAY_MS -> StopReason.TIME_UP
        else -> null
    }

    /** Recirculation left on for long fogs the windows and raises CO2, so the parent is asked to switch back. */
    fun recirculationTooLong(s: CarState, recirculationSinceMs: Long?, nowMs: Long): Boolean =
        s.recirculation == true && recirculationSinceMs != null && nowMs - recirculationSinceMs >= RECIRCULATION_LIMIT_MS
}

/**
 * Puts the car back as the parent left it: the one time the app changes the car, at 처음 설정으로, while parked.
 * [done] gets the labels of what could not be put back (the parent turns those back by hand).
 */
interface CarRestorer {
    fun restore(target: Snapshot, done: (failed: List<String>) -> Unit)
}

/** Climate and lights as the parent left them before the game, to put them back at the end. */
data class Snapshot(
    val recirculation: Boolean?,
    val fanSpeed: Int?,
    val fanDirection: FanDir,
    val dual: Boolean?,
    val tempLeft: Float?,
    val tempRight: Float?,
    val defrostFront: Boolean?,
    val readingLights: Map<Seat, Boolean>,
    val acOn: Boolean? = null,
    val seatHeat: Int? = null,
) {
    /** What differs now, as short lines for the "처음 설정으로" screen. */
    fun changes(now: CarState): List<String> = buildList {
        if (recirculation != null && now.recirculation != recirculation) add(if (recirculation) "내기 순환" else "외기 유입")
        if (fanSpeed != null && now.fanSpeed != fanSpeed) add("풍량 ${fanSpeed}단")
        if (fanDirection != FanDir.UNKNOWN && now.fanDirection != fanDirection) add("풍향")
        if (dual != null && now.dual != dual) add(if (dual) "좌우 온도 같이" else "좌우 온도 따로")
        if (tempLeft != null && now.tempLeft != tempLeft) add("운전석 온도 ${tempLeft}도")
        if (tempRight != null && now.tempRight != tempRight) add("조수석 온도 ${tempRight}도")
        if (defrostFront != null && now.defrostFront != defrostFront) add(if (defrostFront) "앞 유리 성에 제거 켜기" else "앞 유리 성에 제거 끄기")
        for ((seat, on) in readingLights) if (now.readingLights[seat] != on) add("${seat.label} 독서등 ${if (on) "켜기" else "끄기"}")
        if (acOn != null && now.acOn != acOn) add(if (acOn) "에어컨 켜기" else "에어컨 끄기")
        if (seatHeat != null && now.seatHeat != seatHeat) add(if (seatHeat == 0) "운전석 열선 끄기" else "운전석 열선 ${seatHeat}단")
    }

    companion object {
        fun of(s: CarState) = Snapshot(s.recirculation, s.fanSpeed, s.fanDirection, s.dual, s.tempLeft, s.tempRight,
            s.defrostFront, s.readingLights, s.acOn, s.seatHeat)
    }
}
