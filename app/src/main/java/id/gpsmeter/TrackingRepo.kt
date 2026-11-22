package id.gpsmeter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Status { IDLE, RUNNING, PAUSED }

data class TrackState(
    val status: Status = Status.IDLE,
    val mode: TrackMode = TrackMode.WALK,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0,
    val movingMs: Long = 0,
    val speedMs: Double = 0.0,
    val maxSpeedMs: Double = 0.0,
    val accuracyM: Float = 0f,
    val hasFix: Boolean = false
)

/** Penghubung state antara service dan UI. */
object TrackingRepo {
    val state = MutableStateFlow(TrackState())
    val flow: StateFlow<TrackState> get() = state
}
