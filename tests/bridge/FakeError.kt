package dji.v5.common.error
interface IDJIError { fun description(): String }
object TestError : IDJIError { override fun description() = "injected failure" }
