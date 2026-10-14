package dji.v5.manager.aircraft.virtualstick
import android.os.SystemClock
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.TestError

class VirtualStickState(val isVirtualStickEnable: Boolean, val isVirtualStickAdvancedModeEnabled: Boolean,
                        val currentFlightControlAuthorityOwner: FlightControlAuthority)
interface VirtualStickStateListener {
    fun onVirtualStickStateUpdate(state: VirtualStickState)
    fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason)
}
class VirtualStickManager {
    companion object { private var instance = VirtualStickManager(); fun getInstance() = instance; fun reset() { instance = VirtualStickManager() } }
    data class Sent(val at: Long, val forward: Double, val right: Double)
    var listener: VirtualStickStateListener? = null
    var state = VirtualStickState(false, false, FlightControlAuthority.RC)
    var holdEnable = false; var holdDisable = false; var failDisable = false; var failEnable = false
    var enableCalls = 0; var disableCalls = 0
    var enableCallback: CommonCallbacks.CompletionCallback? = null
    var disableCallback: CommonCallbacks.CompletionCallback? = null
    val commands = mutableListOf<Sent>()
    fun setVirtualStickStateListener(value: VirtualStickStateListener) { listener = value; listener?.onVirtualStickStateUpdate(state) }
    fun removeVirtualStickStateListener(value: VirtualStickStateListener) { listener = null }
    fun update(next: VirtualStickState) { state = next; listener?.onVirtualStickStateUpdate(next) }
    fun enableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        enableCalls++; enableCallback = callback
        if (failEnable) { callback.onFailure(TestError); enableCallback = null; return }
        if (!holdEnable) completeEnable()
    }
    fun completeEnable() {
        update(VirtualStickState(true, false, FlightControlAuthority.MSDK))
        enableCallback!!.onSuccess(); enableCallback = null
    }
    fun setVirtualStickAdvancedModeEnabled(value: Boolean) { update(VirtualStickState(true, value, FlightControlAuthority.MSDK)) }
    fun disableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        disableCalls++; disableCallback = callback
        if (failDisable) { callback.onFailure(TestError); disableCallback = null }
        else if (!holdDisable) completeDisable()
    }
    fun completeDisable() {
        update(VirtualStickState(false, false, FlightControlAuthority.RC))
        disableCallback!!.onSuccess(); disableCallback = null
    }
    fun sendVirtualStickAdvancedParam(param: VirtualStickFlightControlParam) {
        check(param.verticalThrottle == 0.0 && param.yaw == 0.0) { "Unexpected vertical/yaw command" }
        commands.add(Sent(SystemClock.elapsedRealtime(), param.roll, param.pitch))
    }
}
