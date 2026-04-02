package com.dptphat.hoopmaster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dptphat.hoopmaster.backend.BackendEvent
import com.dptphat.hoopmaster.backend.HoopBackendClient
import okhttp3.WebSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class HoopUiState(
    val baseUrl: String = "http://10.0.2.2:8000",
    val sessionId: String? = null,
    val isConnecting: Boolean = false,
    val eventsConnected: Boolean = false,
    val videoConnected: Boolean = false,
    val sessionActive: Boolean = false,
    val sessionCompleted: Boolean = false,
    val throwCount: Int = 0,
    val totalPoints: Int = 0,
    val cameraConnected: Boolean = false,
    val lastFeedback: String = "Waiting for throw feedback...",
    val lastTarget: String = "-",
    val statusMessage: String = "Tap Connect to start",
    val errorMessage: String? = null
)

class HoopViewModel : ViewModel() {
    private val backendClient = HoopBackendClient()

    private val _uiState = MutableStateFlow(HoopUiState())
    val uiState: StateFlow<HoopUiState> = _uiState.asStateFlow()

    private val _speechRequests = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val speechRequests: SharedFlow<String> = _speechRequests.asSharedFlow()

    private var eventSocket: WebSocket? = null
    private var videoSocket: WebSocket? = null
    private var userRequestedDisconnect: Boolean = false
    private var reconnectAttempts: Int = 0

    private var lastSpokenFeedback: String = ""
    private var lastSpokenAtMillis: Long = 0L

    fun onBaseUrlChanged(value: String) {
        _uiState.update { it.copy(baseUrl = value.trim(), errorMessage = null) }
    }

    fun connect() {
        val current = _uiState.value
        if (current.isConnecting || current.sessionId != null) return
        userRequestedDisconnect = false
        reconnectAttempts = 0

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    statusMessage = "Creating backend session...",
                    errorMessage = null
                )
            }

            runCatching {
                val snapshot = backendClient.createSession(current.baseUrl)
                snapshot
            }.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        sessionId = snapshot.sessionId,
                        throwCount = snapshot.state.throws,
                        totalPoints = snapshot.state.totalPoints,
                        sessionActive = snapshot.state.sessionActive,
                        sessionCompleted = snapshot.state.sessionCompleted,
                        cameraConnected = snapshot.camera.connected,
                        statusMessage = "Session ${snapshot.sessionId.take(8)} connected"
                    )
                }
                openSockets(snapshot.sessionId)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        statusMessage = "Connection failed",
                        errorMessage = throwable.message ?: "Unknown connection error"
                    )
                }
            }
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        eventSocket?.close(1000, "user_disconnect")
        videoSocket?.close(1000, "user_disconnect")
        eventSocket = null
        videoSocket = null
        reconnectAttempts = 0

        _uiState.update {
            it.copy(
                sessionId = null,
                eventsConnected = false,
                videoConnected = false,
                sessionActive = false,
                sessionCompleted = false,
                throwCount = 0,
                totalPoints = 0,
                cameraConnected = false,
                statusMessage = "Disconnected",
                errorMessage = null,
                lastFeedback = "Waiting for throw feedback...",
                lastTarget = "-"
            )
        }
    }

    fun startSession() {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = _uiState.value.baseUrl
            runCatching {
                backendClient.startSession(baseUrl, sessionId)
            }.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        sessionActive = snapshot.state.sessionActive,
                        sessionCompleted = snapshot.state.sessionCompleted,
                        throwCount = snapshot.state.throws,
                        totalPoints = snapshot.state.totalPoints,
                        statusMessage = "Session started",
                        errorMessage = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Could not start session")
                }
            }
        }
    }

    fun stopSession() {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = _uiState.value.baseUrl
            runCatching {
                backendClient.stopSession(baseUrl, sessionId)
            }.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        sessionActive = snapshot.state.sessionActive,
                        sessionCompleted = snapshot.state.sessionCompleted,
                        throwCount = snapshot.state.throws,
                        totalPoints = snapshot.state.totalPoints,
                        statusMessage = "Session stopped",
                        errorMessage = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Could not stop session")
                }
            }
        }
    }

    fun resetSession() {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = _uiState.value.baseUrl
            runCatching {
                backendClient.resetSession(baseUrl, sessionId)
            }.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        sessionActive = snapshot.state.sessionActive,
                        sessionCompleted = snapshot.state.sessionCompleted,
                        throwCount = snapshot.state.throws,
                        totalPoints = snapshot.state.totalPoints,
                        cameraConnected = snapshot.camera.connected,
                        statusMessage = "Session reset",
                        lastFeedback = "Waiting for throw feedback...",
                        lastTarget = "-",
                        errorMessage = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Could not reset session")
                }
            }
        }
    }

    fun sendFrame(frameBytes: ByteArray) {
        if (!_uiState.value.videoConnected) return
        if (!backendClient.sendFrame(videoSocket, frameBytes)) {
            _uiState.update { it.copy(videoConnected = false, errorMessage = "Video frame send failed") }
        }
    }

    private fun openSockets(sessionId: String) {
        val baseUrl = _uiState.value.baseUrl

        eventSocket = backendClient.openEventSocket(
            baseUrl = baseUrl,
            sessionId = sessionId,
            onEvent = { event -> handleEvent(event) },
            onOpen = {
                reconnectAttempts = 0
                _uiState.update {
                    it.copy(eventsConnected = true, statusMessage = "Event channel connected")
                }
                refreshSessionSnapshot(sessionId)
            },
            onClosed = {
                _uiState.update {
                    it.copy(eventsConnected = false)
                }
                scheduleEventReconnect(sessionId)
            },
            onFailure = { message ->
                _uiState.update {
                    it.copy(eventsConnected = false, errorMessage = message)
                }
                scheduleEventReconnect(sessionId)
            }
        )

        videoSocket = backendClient.openVideoSocket(
            baseUrl = baseUrl,
            sessionId = sessionId,
            onOpen = {
                _uiState.update {
                    it.copy(videoConnected = true, statusMessage = "Video stream connected")
                }
            },
            onClosed = {
                _uiState.update {
                    it.copy(videoConnected = false)
                }
            },
            onFailure = { message ->
                _uiState.update {
                    it.copy(videoConnected = false, errorMessage = message)
                }
            }
        )
    }

    private fun scheduleEventReconnect(sessionId: String) {
        if (userRequestedDisconnect) return
        if (_uiState.value.sessionId != sessionId) return

        val attempt = reconnectAttempts
        reconnectAttempts += 1
        val delayMillis = (1000L shl minOf(attempt, 4))

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(statusMessage = "Reconnecting event stream (${attempt + 1})...")
            }
            delay(delayMillis)
            if (userRequestedDisconnect || _uiState.value.sessionId != sessionId) return@launch

            eventSocket?.cancel()
            eventSocket = backendClient.openEventSocket(
                baseUrl = _uiState.value.baseUrl,
                sessionId = sessionId,
                onEvent = { event -> handleEvent(event) },
                onOpen = {
                    reconnectAttempts = 0
                    _uiState.update {
                        it.copy(eventsConnected = true, statusMessage = "Event stream reconnected", errorMessage = null)
                    }
                    refreshSessionSnapshot(sessionId)
                },
                onClosed = {
                    _uiState.update { it.copy(eventsConnected = false) }
                    scheduleEventReconnect(sessionId)
                },
                onFailure = { message ->
                    _uiState.update { it.copy(eventsConnected = false, errorMessage = message) }
                    scheduleEventReconnect(sessionId)
                }
            )
        }
    }

    private fun refreshSessionSnapshot(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backendClient.getSession(_uiState.value.baseUrl, sessionId) }
                .onSuccess { snapshot -> applySnapshot(snapshot) }
        }
    }

    private fun handleEvent(event: BackendEvent) {
        when (event) {
            is BackendEvent.SessionStateEvent -> applySnapshot(event.snapshot)
            is BackendEvent.SessionStartedEvent -> applySnapshot(event.snapshot)
            is BackendEvent.SessionResetEvent -> applySnapshot(event.snapshot)
            is BackendEvent.ThrowEventReceived -> {
                _uiState.update {
                    val additionalPoints = if (event.event.idx > it.throwCount) event.event.points else 0
                    it.copy(
                        throwCount = event.event.idx,
                        totalPoints = it.totalPoints + additionalPoints,
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
                        statusMessage = "Stopped. Points: ${event.summary.totalPoints}",
                        errorMessage = null
                    )
                }
            }

            is BackendEvent.SessionCompletedEvent -> {
                _uiState.update {
                    it.copy(
                        sessionActive = false,
                        sessionCompleted = true,
                        statusMessage = "Completed. No-mistake: ${event.summary.noMistakeRate}%",
                        errorMessage = null
                    )
                }
            }

            is BackendEvent.Unknown -> Unit
        }
    }

    private fun applySnapshot(snapshot: com.dptphat.hoopmaster.backend.SessionSnapshot) {
        _uiState.update {
            it.copy(
                sessionActive = snapshot.state.sessionActive,
                sessionCompleted = snapshot.state.sessionCompleted,
                throwCount = snapshot.state.throws,
                totalPoints = snapshot.state.totalPoints,
                cameraConnected = snapshot.camera.connected,
                statusMessage = if (snapshot.state.sessionActive) {
                    "Session running"
                } else {
                    "Session ready"
                }
            )
        }
    }

    private fun maybeSpeak(feedback: String) {
        if (feedback.isBlank()) return
        val now = System.currentTimeMillis()
        val recentlySpoken = feedback == lastSpokenFeedback && (now - lastSpokenAtMillis) < 1500
        if (recentlySpoken) return

        lastSpokenFeedback = feedback
        lastSpokenAtMillis = now
        _speechRequests.tryEmit(feedback)
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}


