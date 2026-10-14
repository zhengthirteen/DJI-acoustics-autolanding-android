package dji.v5.manager
import dji.sdk.keyvalue.key.*
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.TestError

class KeyManager {
    companion object { private var instance = KeyManager(); fun getInstance() = instance; fun reset() { instance = KeyManager() } }
    val values = mutableMapOf<String, Any?>()
    private val listeners = mutableMapOf<String, (Any?) -> Unit>()
    val actions = mutableListOf<String>()
    val held = mutableSetOf<String>()
    val failed = mutableSetOf<String>()
    private val pending = mutableMapOf<String, MutableList<CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>>>()
    @Suppress("UNCHECKED_CAST")
    fun <T> listen(key: DJIKeyInfo<T>, owner: Any, immediate: Boolean, callback: (T?, T?) -> Unit) {
        listeners[key.name] = { callback(null, it as T?) }
        if (immediate) callback(null, values[key.name] as T?)
    }
    fun <T> emit(key: DJIKeyInfo<T>, value: T?) { values[key.name] = value; listeners[key.name]?.invoke(value) }
    fun cancelListen(owner: Any) { listeners.clear() }
    fun performAction(key: DJIKeyInfo<EmptyMsg>, callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        actions.add(key.name)
        when (key.name) {
            in held -> pending.getOrPut(key.name) { mutableListOf() }.add(callback)
            in failed -> callback.onFailure(TestError)
            else -> callback.onSuccess(EmptyMsg())
        }
    }
    fun complete(action: String) { pending.getValue(action).removeAt(0).onSuccess(EmptyMsg()) }
}
