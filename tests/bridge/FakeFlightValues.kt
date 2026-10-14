package dji.sdk.keyvalue.value.flightcontroller

enum class FlightMode { UNKNOWN, GPS_NORMAL, GPS_TRIPOD, VIRTUAL_STICK, ATTI, AUTO_LANDING, GO_HOME }
enum class FlightControlAuthority { MSDK, RC }
enum class FlightControlAuthorityChangeReason { TEST }
enum class GPSSignalLevel { NONE, LEVEL_4, LEVEL_5, LEVEL_10 }
enum class FlightCoordinateSystem { BODY }
enum class RollPitchControlMode { VELOCITY }
enum class YawControlMode { ANGULAR_VELOCITY }
enum class VerticalControlMode { VELOCITY }
class VirtualStickFlightControlParam {
    lateinit var rollPitchCoordinateSystem: FlightCoordinateSystem
    lateinit var rollPitchControlMode: RollPitchControlMode
    lateinit var yawControlMode: YawControlMode
    lateinit var verticalControlMode: VerticalControlMode
    var roll = 0.0; var pitch = 0.0; var yaw = 0.0; var verticalThrottle = 0.0
}
