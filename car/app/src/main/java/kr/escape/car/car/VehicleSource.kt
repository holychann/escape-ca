package kr.escape.car.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacDualStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.escape.car.engine.CarRestorer
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Snapshot

/**
 * Everything the game reads from the car, as one [CarState]. During the game the app only reads: the child finds the
 * button and the parent presses it. The one write is [restore], at the end, putting back what the parent had set.
 *
 * Measured on the Pleos emulator (검증_리포트 L장): HVAC calls must use the area their check call returns (a single
 * seat throws), fan direction has no listener so it is polled, temperatures throw while the climate power is off,
 * and in gear D the system covers the app without onPause, so driving is read from gear, speed and UX restrictions.
 */
class VehicleSource(private val context: Context) : CarRestorer {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var fanArea: SeatArea? = null
    // the areas each control answered on, kept so [restore] can write to the same ones
    private var recirculationArea: SeatArea? = null
    private var dualArea: SeatArea? = null
    private var tempLeftArea: SeatArea? = null
    private var tempRightArea: SeatArea? = null
    private var frontDefrostArea: WindowArea? = null
    private val doorsOpen = mutableMapOf<DoorValue, Boolean>()

    private val seats = mapOf(
        Seat.DRIVER to SeatValue.ROW_1_LEFT, Seat.PASSENGER to SeatValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to SeatValue.ROW_2_LEFT, Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )

    fun start() {
        vehicle = runCatching { Vehicle(context.applicationContext).also { it.initialize() } }
            .onFailure { log("차량 SDK 연결 실패: ${it.message}") }.getOrNull()
        // the SDK answers only after its service has bound
        main.postDelayed({ register() }, 1500)
        guarded("주행 상태") { registerDriving() }
        main.postDelayed(pollFanDirection, 2000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
    }

    private fun set(block: (CarState) -> CarState) = _state.update(block)
    private fun addDevice(d: Device) = set { it.copy(devices = it.devices + d) }
    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    /** One device the car lacks (or refuses) must not take the others down. */
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("$what 연결 실패: ${e.message}")
        }
    }

    private fun fail(what: String): (Exception) -> Unit = { e -> log("$what 실패: ${e.message}") }

    private fun register() {
        val v = vehicle ?: return
        guarded("기어") { registerGear(v) }
        guarded("문") { registerDoors(v) }
        guarded("운전석") { registerDriverSeat(v) }
        guarded("공조") { registerHvac(v) }
        guarded("독서등") { registerReadingLights(v) }
    }

    private fun registerGear(v: Vehicle) {
        @Suppress("DEPRECATION")
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = set { it.copy(gear = gear.toGear()) }
            override fun onFailed(e: Exception) = fail("기어")(e)
        })
        @Suppress("DEPRECATION")
        v.getGear().getCurrentGearSelectionState({ g -> set { it.copy(gear = g.toGear()) } }, fail("기어"))
    }

    private fun VehicleGear?.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }

    private fun registerDoors(v: Vehicle) {
        val door = v.getDoor()
        for (value in listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)) {
            val onPos: (Int?) -> Unit = { pos ->
                val anyOpen = synchronized(doorsOpen) {
                    doorsOpen[value] = (pos ?: 0) > 0
                    doorsOpen.values.any { open -> open }
                }
                set { it.copy(doorOpen = anyOpen) }
            }
            door.registerDoorPosition(DoorArea(value), object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = onPos(pos)
                override fun onFailed(e: Exception) = fail("문 $value")(e)
            })
            door.getCurrentDoorPosition(DoorArea(value), onPos, fail("문 $value"))
        }
    }

    /**
     * Vehicle SDK 2.3.2 turns SEAT_OCCUPANCY into `value == 1`, and in VHAL 1 is VACANT (2 is OCCUPIED), so the
     * emulator reports a seated driver as false (measured for FairPool). Only the driver seat is read.
     */
    private fun registerDriverSeat(v: Vehicle) {
        val area = SeatArea(SeatValue.ROW_1_LEFT)
        val onSeat: (Boolean?) -> Unit = { value -> set { it.copy(driverSeated = value?.let { b -> !b }) } }
        v.getSeat().registerSeatOccupancy(area, object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) = onSeat(value)
            override fun onFailed(e: Exception) = fail("운전석")(e)
        })
        v.getSeat().getCurrentSeatOccupancy(area, onSeat, fail("운전석"))
    }

    private fun registerHvac(v: Vehicle) {
        val hvac = v.getHVAC()
        hvac.checkHvacRecirculationCapabilities({ areas ->
            val a = areas.firstOrNull() ?: return@checkHvacRecirculationCapabilities
            recirculationArea = a
            addDevice(Device.RECIRCULATION)
            powerAreas.add(0, a)
            hvac.registerHvacRecirculationState(a, object : HvacRecirculationStateListener {
                override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) = set { it.copy(recirculation = state) }
                override fun onFailed(e: Exception) = fail("내기 순환")(e)
            })
            hvac.getHvacRecirculationState(a, { s -> set { it.copy(recirculation = s) } }, fail("내기 순환"))
        }, fail("내기 순환 check"))

        hvac.checkHvacFanSpeedCapabilities({ map ->
            val (a, bounds) = map.entries.firstOrNull()?.toPair() ?: return@checkHvacFanSpeedCapabilities
            fanArea = a
            addDevice(Device.FAN_SPEED)
            set { it.copy(fanSpeedRange = bounds.minValue.coerceAtLeast(1)..bounds.maxValue) }
            hvac.registerHvacFanSpeed(a, object : HvacFanSpeedListener {
                override fun onHvacFanSpeedUpdated(speed: Int?) = set { it.copy(fanSpeed = speed) }
                override fun onFailed(e: Exception) = fail("풍량")(e)
            })
            hvac.getHvacFanSpeed(a, { s -> set { it.copy(fanSpeed = s) } }, fail("풍량"))
            powerAreas += a
        }, fail("풍량 check"))

        hvac.checkHvacFanDirectionCapabilities({ map ->
            if (map.isNotEmpty()) addDevice(Device.FAN_DIRECTION)
        }, fail("풍향 check"))

        hvac.checkHvacDualCapabilities({ areas ->
            val a = areas.firstOrNull() ?: return@checkHvacDualCapabilities
            dualArea = a
            hvac.registerHvacDualState(a, object : HvacDualStateListener {
                override fun onHvacDualStateUpdated(state: Boolean?) = set { it.copy(dual = state) }
                override fun onFailed(e: Exception) = fail("듀얼")(e)
            })
            hvac.getHvacDualState(a, { s -> set { it.copy(dual = s) } }, fail("듀얼"))
            guarded("온도") { readTemperatures(v) }
        }, fail("듀얼 check"))

        hvac.checkHvacDefrosterCapabilities({ areas ->
            val front = areas.firstOrNull { it.values.contains(WindowValue.FRONT_WINDSHIELD) }
                ?: return@checkHvacDefrosterCapabilities
            frontDefrostArea = front
            addDevice(Device.DEFROSTER)
            hvac.registerHvacDefrosterState(front, object : HvacDefrosterStateListener {
                override fun onHvacDefrosterStateUpdated(state: Boolean?) = set { it.copy(defrostFront = state) }
                override fun onFailed(e: Exception) = fail("성에 제거")(e)
            })
            hvac.getHvacDefrosterState(front, { s -> set { it.copy(defrostFront = s) } }, fail("성에 제거"))
        }, fail("성에 제거 check"))
    }

    private var temperaturesWatched = false

    private fun readTemperatures(v: Vehicle) {
        val hvac = v.getHVAC()
        hvac.checkHvacTemperatureCapabilities({ map ->
            val left = map.keys.firstOrNull { it.values.contains(SeatValue.ROW_1_LEFT) }
            val right = map.keys.firstOrNull { it.values.contains(SeatValue.ROW_1_RIGHT) }
            if (left == null || right == null || left == right) return@checkHvacTemperatureCapabilities
            tempLeftArea = left
            tempRightArea = right
            addDevice(Device.DUAL_TEMPERATURE)
            val onLeft: (Float?) -> Unit = { c -> set { it.copy(tempLeft = c) } }
            val onRight: (Float?) -> Unit = { c -> set { it.copy(tempRight = c) } }
            hvac.getHvacTemperature(left, TemperatureUnit.CELSIUS, onLeft, fail("운전석 온도"))
            hvac.getHvacTemperature(right, TemperatureUnit.CELSIUS, onRight, fail("조수석 온도"))
            if (temperaturesWatched) return@checkHvacTemperatureCapabilities
            temperaturesWatched = true
            hvac.registerHvacTemperature(left, object : HvacTemperatureValueListener {
                override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) = onLeft(celsius)
                override fun onFailed(e: Exception) = fail("운전석 온도")(e)
            })
            hvac.registerHvacTemperature(right, object : HvacTemperatureValueListener {
                override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) = onRight(celsius)
                override fun onFailed(e: Exception) = fail("조수석 온도")(e)
            })
        }, fail("온도 check"))
    }

    private fun registerReadingLights(v: Vehicle) {
        val light = v.getLight()
        light.checkReadingLightSwitchCapabilities({ map ->
            if (map.isNotEmpty()) addDevice(Device.READING_LIGHT)
        }, fail("독서등 check"))
        for ((seat, value) in seats) {
            val area = SeatArea(value)
            val onLight: (LightState?) -> Unit = { s ->
                set { it.copy(readingLights = it.readingLights + (seat to (s == LightState.ON))) }
            }
            light.registerReadingLightState(area, object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = onLight(state)
                override fun onFailed(e: Exception) = fail("독서등 ${seat.label}")(e)
            })
            light.getCurrentReadingLightState(area, onLight, fail("독서등 ${seat.label}"))
        }
    }

    /** Areas to try for the climate power, whole cabin first; one that the car refuses is dropped. */
    private val powerAreas = java.util.concurrent.CopyOnWriteArrayList<SeatArea>()

    /**
     * Neither the fan direction nor the climate power sends change callbacks on the emulator, so both are read once
     * a second. Temperatures throw while the power is off, so they are read again when it comes on.
     */
    private val pollFanDirection = object : Runnable {
        override fun run() {
            val a = fanArea
            val v = vehicle
            if (a != null && v != null) guarded("풍향") {
                v.getHVAC().getHvacFanDirection(a, { d -> set { it.copy(fanDirection = d.toFanDir()) } }, { })
            }
            val p = powerAreas.firstOrNull()
            if (p != null && v != null) guarded("공조 전원") {
                v.getHVAC().getHvacPowerState(p, { on ->
                    val wasOn = _state.value.hvacPower == true
                    set { it.copy(hvacPower = on) }
                    if (on == true && !wasOn) main.post { guarded("온도") { readTemperatures(v) } }
                }, { powerAreas.remove(p) })
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun FanDirectionType?.toFanDir() = when (this) {
        FanDirectionType.FACE -> FanDir.FACE
        FanDirectionType.FLOOR -> FanDir.FLOOR
        FanDirectionType.FACE_AND_FLOOR -> FanDir.FACE_AND_FLOOR
        FanDirectionType.DEFROST -> FanDir.DEFROST
        null, FanDirectionType.UNKNOWN -> FanDir.UNKNOWN
        else -> FanDir.OTHER
    }

    /** One write [restore] makes: its label for the parent, and the SDK call with its success and failure. */
    private class Step(val label: String, val call: (ok: () -> Unit, fail: () -> Unit) -> Unit)

    /**
     * 처음 설정으로: puts back every value the game changed, one after another (the emulator couples the two
     * temperatures, so the coupling is set first, then the driver's side, then the passenger's). [done] gets the labels
     * of what could not be put back; a step that does not answer in 3 s counts as not put back.
     */
    override fun restore(target: Snapshot, done: (failed: List<String>) -> Unit) {
        val v = vehicle ?: return done(listOf("차량 연결"))
        val hvac = v.getHVAC()
        val now = _state.value
        val steps = mutableListOf<Step>()
        fun <A, T> hvacStep(label: String, area: A?, write: (A, (Any?, Any?) -> Unit, (Exception) -> Unit) -> Unit) {
            if (area == null) { steps += Step(label) { _, fail -> fail() }; return }
            steps += Step(label) { ok, fail -> write(area, { _, _ -> ok() }, { e -> log("되돌리기 $label 실패: ${e.message}"); fail() }) }
        }
        target.recirculation?.takeIf { it != now.recirculation }?.let { on ->
            hvacStep<SeatArea, Boolean>(if (on) "내기 순환" else "외기 유입", recirculationArea) { a, ok, fail ->
                hvac.setHvacRecirculation(a, on, { x, y -> ok(x, y) }, fail)
            }
        }
        target.fanSpeed?.takeIf { it != now.fanSpeed }?.let { speed ->
            hvacStep<SeatArea, Int>("풍량 ${speed}단", fanArea) { a, ok, fail -> hvac.setHvacFanSpeed(a, speed, { x, y -> ok(x, y) }, fail) }
        }
        target.fanDirection.toSdk()?.takeIf { target.fanDirection != now.fanDirection }?.let { dir ->
            hvacStep<SeatArea, FanDirectionType>("풍향", fanArea) { a, ok, fail -> hvac.setHvacFanDirection(a, dir, { x, y -> ok(x, y) }, fail) }
        }
        val dual = target.dual
        if (dual == false && now.dual != false) {
            hvacStep<SeatArea, Boolean>("좌우 온도 따로", dualArea) { a, ok, fail -> hvac.setHvacDual(a, false, { x, y -> ok(x, y) }, fail) }
        }
        target.tempLeft?.takeIf { it != now.tempLeft }?.let { c ->
            hvacStep<SeatArea, Float>("운전석 온도 ${c}도", tempLeftArea) { a, ok, fail ->
                hvac.setHvacTemperature(a, c, TemperatureUnit.CELSIUS, { x, y -> ok(x, y) }, fail)
            }
        }
        // with the two sides coupled, the passenger's side follows the driver's
        if (dual != true) target.tempRight?.takeIf { it != now.tempRight }?.let { c ->
            hvacStep<SeatArea, Float>("조수석 온도 ${c}도", tempRightArea) { a, ok, fail ->
                hvac.setHvacTemperature(a, c, TemperatureUnit.CELSIUS, { x, y -> ok(x, y) }, fail)
            }
        }
        if (dual == true && now.dual != true) {
            hvacStep<SeatArea, Boolean>("좌우 온도 같이", dualArea) { a, ok, fail -> hvac.setHvacDual(a, true, { x, y -> ok(x, y) }, fail) }
        }
        target.defrostFront?.takeIf { it != now.defrostFront }?.let { on ->
            hvacStep<WindowArea, Boolean>("앞 유리 성에 제거", frontDefrostArea) { a, ok, fail -> hvac.setHvacDefroster(a, on, { x, y -> ok(x, y) }, fail) }
        }
        for ((seat, on) in target.readingLights) {
            if (now.readingLights[seat] == on) continue
            val area = SeatArea(seats.getValue(seat))
            steps += Step("${seat.label} 독서등") { ok, fail ->
                v.getLight().setReadingLightSwitch(area, if (on) LightSwitch.ON else LightSwitch.OFF, { ok() }, { e ->
                    log("되돌리기 ${seat.label} 독서등 실패: ${e.message}"); fail()
                })
            }
        }
        runSteps(steps, mutableListOf(), done)
    }

    private fun runSteps(steps: List<Step>, failed: MutableList<String>, done: (List<String>) -> Unit) {
        if (steps.isEmpty()) {
            main.post { done(failed.toList()) }
            return
        }
        val step = steps.first()
        var finished = false
        val next = { ok: Boolean ->
            main.post {
                if (!finished) {
                    finished = true
                    if (!ok) failed += step.label
                    // a short gap lets the car settle before the next write (the two temperatures in particular)
                    main.postDelayed({ runSteps(steps.drop(1), failed, done) }, 200)
                }
            }
        }
        try {
            step.call({ next(true) }, { next(false) })
        } catch (e: Exception) {
            log("되돌리기 ${step.label} 실패: ${e.message}")
            next(false)
        }
        main.postDelayed({ next(false) }, 3000)
    }

    private fun FanDir.toSdk(): FanDirectionType? = when (this) {
        FanDir.FACE -> FanDirectionType.FACE
        FanDir.FLOOR -> FanDirectionType.FLOOR
        FanDir.FACE_AND_FLOOR -> FanDirectionType.FACE_AND_FLOOR
        FanDir.DEFROST -> FanDirectionType.DEFROST
        FanDir.OTHER, FanDir.UNKNOWN -> null
    }

    /** Speed and driving restrictions come from AAOS, not from the Pleos SDK. */
    private fun registerDriving() {
        val c = Car.createCar(context.applicationContext).also { car = it }
        val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        cpm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val mps = (value.value as? Float) ?: return
                if (mps != _state.value.speedMps) set { it.copy(speedMps = mps) }
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) = log("속도 오류 $propertyId")
        }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)

        val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        set { it.copy(uxRestricted = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true) }
        ux.registerListener { r -> set { it.copy(uxRestricted = r.isRequiresDistractionOptimization) } }
    }

    companion object {
        const val TAG = "EscapeCar"
    }
}
