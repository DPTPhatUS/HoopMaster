package com.dptphat.hoopmaster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dptphat.hoopmaster.backend.BackendEvent
import com.dptphat.hoopmaster.backend.SessionSnapshot
import com.dptphat.hoopmaster.backend.SessionSummary
import com.dptphat.hoopmaster.backend.ThrowEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SessionLogEntry(
    val throwIndex: Int,
    val mistakeTitle: String,
    val feedback: String,
    val target: String,
    val points: Int
)

data class SessionResultSummary(
    val totalThrows: Int,
    val totalPoints: Int,
    val noMistakeRate: Double
)

data class HoopUiState(
    val defaultBaseUrl: String = "http://10.0.2.2:8000",
    val sessionId: String? = null,
    val isConnecting: Boolean = false,
    val eventsConnected: Boolean = false,
    val videoConnected: Boolean = false,
    val sessionActive: Boolean = false,
    val sessionCompleted: Boolean = false,
    val throwCount: Int = 0,
    val totalPoints: Int = 0,
    val weeklyGoal: Int = 100,
    val weeklyAccuracyPercent: Int = 0,
    val weeklyTotalShots: Int = 0,
    val sessionLog: List<SessionLogEntry> = emptyList(),
    val lastSessionSummary: SessionResultSummary? = null,
    val cameraConnected: Boolean = false,
    val lastFeedback: String = "Waiting for throw feedback...",
    val lastTarget: String = "-",
    val statusMessage: String = "Ready for practice",
    val errorMessage: String? = null
)

class HoopViewModel : ViewModel() {
    private companion object {
        const val DEMO_THROW_INTERVAL_SECONDS = 10.0
        const val MAX_POINTS_PER_THROW = 10
    }

    private data class DemoFeedback(
        val mistakeTitle: String,
        val feedback: String,
        val target: String,
        val points: Int
    )

    private val demoFeedbackScript = listOf(
        DemoFeedback(
            mistakeTitle = "Elbow flare",
            feedback = "Tuck your shooting elbow in and keep it under the ball.",
            target = "Align elbow with rim",
            points = 4
        ),
        DemoFeedback(
            mistakeTitle = "No mistake detected",
            feedback = "Great release. Keep the same follow-through.",
            target = "Hold follow-through",
            points = 9
        ),
        DemoFeedback(
            mistakeTitle = "Off-balance landing",
            feedback = "Land on both feet and keep your chest up.",
            target = "Stable landing",
            points = 5
        ),
        DemoFeedback(
            mistakeTitle = "No mistake detected",
            feedback = "Nice shot rhythm. Repeat this timing.",
            target = "Consistent rhythm",
            points = 10
        ),
        DemoFeedback(
            mistakeTitle = "Late wrist snap",
            feedback = "Snap your wrist a touch earlier at release.",
            target = "Earlier wrist snap",
            points = 6
        ),
        DemoFeedback(
            mistakeTitle = "No mistake detected",
            feedback = "Clean mechanics. Keep this form.",
            target = "Same shooting form",
            points = 9
        )
    )

    private val _uiState = MutableStateFlow(HoopUiState())
    val uiState: StateFlow<HoopUiState> = _uiState.asStateFlow()

    private val _speechRequests = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val speechRequests: SharedFlow<String> = _speechRequests.asSharedFlow()

    private var demoSessionJob: Job? = null
    private var currentDemoThrowIndex: Int = 0

    private var lastSpokenFeedback: String = ""
    private var lastSpokenAtMillis: Long = 0L

    fun ensureConnected() {
        val current = _uiState.value
        if (current.isConnecting || current.sessionId != null) return
        connect()
    }

    fun connect() {
        val current = _uiState.value
        if (current.isConnecting || current.sessionId != null) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    statusMessage = "Preparing demo session...",
                    errorMessage = null
                )
            }

            delay(250)
            val sessionId = "demo-${System.currentTimeMillis().toString().takeLast(6)}"
            _uiState.update {
                it.copy(
                    isConnecting = false,
                    sessionId = sessionId,
                    eventsConnected = true,
                    videoConnected = true,
                    sessionActive = false,
                    sessionCompleted = false,
                    throwCount = 0,
                    totalPoints = 0,
                    weeklyTotalShots = 0,
                    weeklyAccuracyPercent = 0,
                    cameraConnected = true,
                    sessionLog = emptyList(),
                    lastSessionSummary = null,
                    statusMessage = "Demo session ${sessionId.takeLast(4)} connected",
                    errorMessage = null,
                    lastFeedback = "Waiting for throw feedback...",
                    lastTarget = "-"
                )
            }
        }
    }

    fun disconnect() {
        demoSessionJob?.cancel()
        demoSessionJob = null
        currentDemoThrowIndex = 0

        _uiState.update {
            it.copy(
                sessionId = null,
                eventsConnected = false,
                videoConnected = false,
                sessionActive = false,
                sessionCompleted = false,
                throwCount = 0,
                totalPoints = 0,
                sessionLog = emptyList(),
                cameraConnected = false,
                statusMessage = "Ready for practice",
                errorMessage = null,
                lastFeedback = "Waiting for throw feedback...",
                lastTarget = "-"
            )
        }
    }

    fun startSession() {
        val sessionId = _uiState.value.sessionId ?: return
        if (_uiState.value.sessionActive) return

        demoSessionJob?.cancel()
        demoSessionJob = null
        currentDemoThrowIndex = 0

        _uiState.update {
            it.copy(
                sessionActive = true,
                sessionCompleted = false,
                throwCount = 0,
                totalPoints = 0,
                weeklyTotalShots = 0,
                weeklyAccuracyPercent = 0,
                sessionLog = emptyList(),
                lastSessionSummary = null,
                lastFeedback = "Session started. Demo feedback enabled.",
                lastTarget = "-",
                statusMessage = "Session started",
                errorMessage = null
            )
        }

        demoSessionJob = viewModelScope.launch {
            while (isActive && currentDemoThrowIndex < demoFeedbackScript.size) {
                delay((DEMO_THROW_INTERVAL_SECONDS * 1000).toLong())
                val currentState = _uiState.value
                if (!currentState.sessionActive || currentState.sessionId != sessionId) break

                val nextFeedback = demoFeedbackScript[currentDemoThrowIndex]
                currentDemoThrowIndex += 1
                val throwEvent = ThrowEvent(
                    idx = currentDemoThrowIndex,
                    timestamp = System.currentTimeMillis().toString(),
                    elapsedSeconds = currentDemoThrowIndex * DEMO_THROW_INTERVAL_SECONDS,
                    mistakeId = nextFeedback.mistakeTitle
                        .takeIf { !it.equals("No mistake detected", ignoreCase = true) }
                        ?.let { "demo_$currentDemoThrowIndex" },
                    mistakeTitle = nextFeedback.mistakeTitle,
                    feedback = nextFeedback.feedback,
                    target = nextFeedback.target,
                    points = nextFeedback.points
                )
                handleEvent(BackendEvent.ThrowEventReceived(throwEvent))
            }

            val shouldAutoComplete = isActive && _uiState.value.sessionActive && _uiState.value.sessionId == sessionId
            if (shouldAutoComplete) {
                handleEvent(BackendEvent.SessionCompletedEvent(buildSessionSummary()))
            }
        }
    }

    fun stopSession() {
        if (_uiState.value.sessionId == null) return
        demoSessionJob?.cancel()
        demoSessionJob = null
        handleEvent(BackendEvent.SessionStoppedEvent(buildSessionSummary()))
    }

    fun resetSession() {
        if (_uiState.value.sessionId == null) return
        demoSessionJob?.cancel()
        demoSessionJob = null
        currentDemoThrowIndex = 0

        _uiState.update {
            it.copy(
                sessionActive = false,
                sessionCompleted = false,
                throwCount = 0,
                totalPoints = 0,
                weeklyTotalShots = 0,
                weeklyAccuracyPercent = 0,
                cameraConnected = true,
                sessionLog = emptyList(),
                lastSessionSummary = null,
                statusMessage = "Session reset",
                lastFeedback = "Waiting for throw feedback...",
                lastTarget = "-",
                errorMessage = null
            )
        }
    }

    fun sendFrame(frameBytes: ByteArray) {
        // Demo mode ignores camera frames but keeps camera UI behavior unchanged.
        if (frameBytes.isEmpty()) return
    }

    private fun handleEvent(event: BackendEvent) {
        when (event) {
            is BackendEvent.SessionStateEvent -> applySnapshot(event.snapshot)
            is BackendEvent.SessionStartedEvent -> applySnapshot(event.snapshot)
            is BackendEvent.SessionResetEvent -> applySnapshot(event.snapshot)
            is BackendEvent.ThrowEventReceived -> {
                _uiState.update {
                    val additionalPoints = if (event.event.idx > it.throwCount) event.event.points else 0
                    val updatedThrows = event.event.idx
                    val updatedPoints = it.totalPoints + additionalPoints
                    it.copy(
                        throwCount = updatedThrows,
                        totalPoints = updatedPoints,
                        weeklyTotalShots = updatedThrows,
                        weeklyAccuracyPercent = computeAccuracyPercent(
                            throws = updatedThrows,
                            points = updatedPoints
                        ),
                        sessionLog = it.sessionLog + SessionLogEntry(
                            throwIndex = event.event.idx,
                            mistakeTitle = event.event.mistakeTitle,
                            feedback = event.event.feedback,
                            target = event.event.target,
                            points = event.event.points
                        ),
                        lastFeedback = event.event.feedback,
                        lastTarget = event.event.target,
                        statusMessage = "Throw ${event.event.idx}: ${event.event.mistakeTitle}",
                        errorMessage = null
                    )
                }
                maybeSpeak(event.event.feedback)
            }

            is BackendEvent.SessionStoppedEvent -> {
                _uiState.update {
                    it.copy(
                        sessionActive = false,
                        sessionCompleted = true,
                        weeklyTotalShots = event.summary.totalThrows,
                        weeklyAccuracyPercent = event.summary.noMistakeRate.roundToInt().coerceIn(0, 100),
                        lastSessionSummary = SessionResultSummary(
                            totalThrows = event.summary.totalThrows,
                            totalPoints = event.summary.totalPoints,
                            noMistakeRate = event.summary.noMistakeRate
                        ),
                        statusMessage = "Session stopped",
                        errorMessage = null
                    )
                }
            }

            is BackendEvent.SessionCompletedEvent -> {
                _uiState.update {
                    it.copy(
                        sessionActive = false,
                        sessionCompleted = true,
                        weeklyTotalShots = event.summary.totalThrows,
                        weeklyAccuracyPercent = event.summary.noMistakeRate.roundToInt().coerceIn(0, 100),
                        lastSessionSummary = SessionResultSummary(
                            totalThrows = event.summary.totalThrows,
                            totalPoints = event.summary.totalPoints,
                            noMistakeRate = event.summary.noMistakeRate
                        ),
                        statusMessage = "Session completed",
                        errorMessage = null
                    )
                }
            }

            is BackendEvent.Unknown -> Unit
        }
    }

    private fun buildSessionSummary(): SessionSummary {
        val current = _uiState.value
        return SessionSummary(
            totalThrows = current.throwCount,
            totalPoints = current.totalPoints,
            noMistakeRate = computeAccuracyPercent(
                throws = current.throwCount,
                points = current.totalPoints
            ).toDouble()
        )
    }

    private fun applySnapshot(snapshot: SessionSnapshot) {
        _uiState.update {
            it.copy(
                sessionActive = snapshot.state.sessionActive,
                sessionCompleted = snapshot.state.sessionCompleted,
                throwCount = snapshot.state.throws,
                totalPoints = snapshot.state.totalPoints,
                weeklyTotalShots = snapshot.state.throws,
                weeklyAccuracyPercent = computeAccuracyPercent(
                    throws = snapshot.state.throws,
                    points = snapshot.state.totalPoints
                ),
                cameraConnected = snapshot.camera.connected,
                statusMessage = if (snapshot.state.sessionActive) {
                    "Session running"
                } else {
                    "Session ready"
                }
            )
        }
    }

    private fun computeAccuracyPercent(throws: Int, points: Int): Int {
        if (throws <= 0) return 0
        val maxPossiblePoints = throws * MAX_POINTS_PER_THROW
        val normalizedPoints = points.coerceIn(0, maxPossiblePoints)
        return ((normalizedPoints.toDouble() / maxPossiblePoints.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun maybeSpeak(feedback: String) {
        if (feedback.isBlank()) return
        val now = System.currentTimeMillis()
        val tooSoon = (now - lastSpokenAtMillis) < 2200
        val recentlySpoken = feedback == lastSpokenFeedback && (now - lastSpokenAtMillis) < 3500
        if (tooSoon || recentlySpoken) return

        lastSpokenFeedback = feedback
        lastSpokenAtMillis = now
        _speechRequests.tryEmit(feedback)
    }

    override fun onCleared() {
        super.onCleared()
        demoSessionJob?.cancel()
        disconnect()
    }
}


