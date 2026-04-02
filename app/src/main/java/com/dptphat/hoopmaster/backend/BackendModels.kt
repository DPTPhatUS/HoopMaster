package com.dptphat.hoopmaster.backend

data class ThrowEvent(
    val idx: Int,
    val timestamp: String,
    val elapsedSeconds: Double,
    val mistakeId: String?,
    val mistakeTitle: String,
    val feedback: String,
    val target: String,
    val points: Int
)

data class SessionState(
    val sessionActive: Boolean,
    val sessionCompleted: Boolean,
    val remainingSeconds: Double,
    val totalPoints: Int,
    val throws: Int
)

data class CameraState(
    val connected: Boolean
)

data class SessionSnapshot(
    val sessionId: String,
    val state: SessionState,
    val camera: CameraState
)

data class SessionSummary(
    val totalThrows: Int,
    val totalPoints: Int,
    val noMistakeRate: Double
)

sealed interface BackendEvent {
    data class SessionStateEvent(val snapshot: SessionSnapshot) : BackendEvent
    data class SessionStartedEvent(val snapshot: SessionSnapshot) : BackendEvent
    data class SessionResetEvent(val snapshot: SessionSnapshot) : BackendEvent
    data class ThrowEventReceived(val event: ThrowEvent) : BackendEvent
    data class SessionStoppedEvent(val summary: SessionSummary) : BackendEvent
    data class SessionCompletedEvent(val summary: SessionSummary) : BackendEvent
    data class Unknown(val rawType: String) : BackendEvent
}

