package kr.escape.car.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightState
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
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.FanDir
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Seat

/**
 * Everything the game reads from the car, as one [CarState]. Read only: the app never sets a value.
 *
 * Measured on the Pleos emulator (검증_리포트 L장): HVAC calls must use the area their check call returns (a single
 * seat throws), fan direction has no listener so it is polled, temperatures throw while the climate power is off,
 * and in gear D the system covers the app without onPause, so driving is read from gear, speed and UX restrictions.
 */
class VehicleSource(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var fanArea: SeatArea? = null
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
