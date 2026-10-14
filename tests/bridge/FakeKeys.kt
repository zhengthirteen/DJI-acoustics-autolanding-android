package dji.sdk.keyvalue.key
import dji.sdk.keyvalue.value.common.*
import dji.sdk.keyvalue.value.flightcontroller.*
class DJIKeyInfo<T>(val name: String)
object KeyTools { fun <T> createKey(key: DJIKeyInfo<T>) = key }
object BatteryKey { val KeyChargeRemainingInPercent = DJIKeyInfo<Int>("battery") }
object FlightControllerKey {
    val KeyConnection = DJIKeyInfo<Boolean>("connected")
    val KeyAircraftAttitude = DJIKeyInfo<Attitude>("attitude")
    val KeyAltitude = DJIKeyInfo<Double>("altitude")
    val KeyAircraftVelocity = DJIKeyInfo<Velocity3D>("velocity")
    val KeyIsFlying = DJIKeyInfo<Boolean>("flying")
    val KeyAreMotorsOn = DJIKeyInfo<Boolean>("motors")
    val KeyGPSSignalLevel = DJIKeyInfo<GPSSignalLevel>("gps")
    val KeyIsVisionSensorUsed = DJIKeyInfo<Boolean>("vision")
    val KeyFlightMode = DJIKeyInfo<FlightMode>("mode")
    val KeyIsLandingConfirmationNeeded = DJIKeyInfo<Boolean>("confirmNeeded")
    val KeyStartAutoLanding = DJIKeyInfo<EmptyMsg>("startLanding")
    val KeyStopAutoLanding = DJIKeyInfo<EmptyMsg>("stopLanding")
    val KeyConfirmLanding = DJIKeyInfo<EmptyMsg>("confirmLanding")
}
