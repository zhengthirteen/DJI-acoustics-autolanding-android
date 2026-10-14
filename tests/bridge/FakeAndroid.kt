package android.os

import java.util.PriorityQueue

/** Deterministic stand-in for Android dispatch; never starts a real flight-control thread. */
object TestLoop {
    private data class Job(val at: Long, val order: Long, val looper: Looper, val action: Runnable)
    private val jobs = PriorityQueue<Job>(compareBy<Job> { it.at }.thenBy { it.order })
    var now = 0L
    private var order = 0L
    fun reset() { jobs.clear(); now = 0; order = 0 }
    fun post(looper: Looper, action: Runnable, delay: Long = 0, front: Boolean = false) {
        jobs.add(Job(if (front) Long.MIN_VALUE else now + delay, ++order, looper, action))
    }
    fun advanceTo(time: Long) {
        check(time >= now)
        while (jobs.isNotEmpty() && jobs.peek().at <= time) {
            val job = jobs.remove(); now = maxOf(now, job.at)
            if (job.looper.running) job.action.run()
        }
        now = time
    }
}
object SystemClock { fun elapsedRealtime() = TestLoop.now }
class Looper { var running = true }
class HandlerThread(name: String) {
    val looper = Looper()
    fun start() = Unit
    fun quitSafely() { looper.running = false }
}
class Handler(private val looper: Looper) {
    fun post(action: Runnable) { TestLoop.post(looper, action) }
    fun postDelayed(action: Runnable, delay: Long) { TestLoop.post(looper, action, delay) }
    fun postAtFrontOfQueue(action: Runnable) { TestLoop.post(looper, action, front = true) }
}
