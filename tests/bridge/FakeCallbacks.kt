package dji.v5.common.callback
import dji.v5.common.error.IDJIError
class CommonCallbacks {
    interface CompletionCallback { fun onSuccess(); fun onFailure(error: IDJIError) }
    interface CompletionCallbackWithParam<T> { fun onSuccess(value: T?); fun onFailure(error: IDJIError) }
}
