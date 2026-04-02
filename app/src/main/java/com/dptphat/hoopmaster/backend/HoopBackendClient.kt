package com.dptphat.hoopmaster.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.toByteString

class HoopBackendClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun createSession(baseUrl: String): SessionSnapshot {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/sessions")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            parseSessionSnapshot(response)
        }
    }

    fun startSession(baseUrl: String, sessionId: String): SessionSnapshot {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/sessions/$sessionId/start")
            .post("".toRequestBody(null))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            parseSessionSnapshot(response)
        }
    }

    fun getSession(baseUrl: String, sessionId: String): SessionSnapshot {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/sessions/$sessionId")
            .get()
            .build()

        return httpClient.newCall(request).execute().use { response ->
            parseSessionSnapshot(response)
        }
    }

    fun stopSession(baseUrl: String, sessionId: String): SessionSnapshot {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/sessions/$sessionId/stop")
            .post("".toRequestBody(null))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            parseSessionSnapshot(response)
        }
    }

    fun resetSession(baseUrl: String, sessionId: String): SessionSnapshot {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/sessions/$sessionId/reset")
            .post("".toRequestBody(null))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            parseSessionSnapshot(response)
        }
    }

    fun openEventSocket(
        baseUrl: String,
        sessionId: String,
        onEvent: (BackendEvent) -> Unit,
        onOpen: () -> Unit,
        onClosed: () -> Unit,
        onFailure: (String) -> Unit
    ): WebSocket {
        val wsUrl = toWsBase(baseUrl) + "/ws/sessions/$sessionId/events"
        val request = Request.Builder().url(wsUrl).build()

        return wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parseEvent(text) }
                    .onSuccess(onEvent)
                    .onFailure { onFailure("Event parse error: ${it.message}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure("Event socket failure: ${t.message ?: "unknown"}")
            }
        })
    }

    fun openVideoSocket(
        baseUrl: String,
        sessionId: String,
        onOpen: () -> Unit,
        onClosed: () -> Unit,
        onFailure: (String) -> Unit
    ): WebSocket {
        val wsUrl = toWsBase(baseUrl) + "/ws/sessions/$sessionId/video"
        val request = Request.Builder().url(wsUrl).build()

        return wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure("Video socket failure: ${t.message ?: "unknown"}")
            }
        })
    }

    fun sendFrame(videoSocket: WebSocket?, frameBytes: ByteArray): Boolean {
        if (videoSocket == null) return false
        return videoSocket.send(frameBytes.toByteString())
    }

    private fun parseSessionSnapshot(response: Response): SessionSnapshot {
        val bodyString = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}: $bodyString")
        }

        val root = JSONObject(bodyString)
        val state = root.getJSONObject("state")
        val camera = root.getJSONObject("camera")
        return SessionSnapshot(
            sessionId = root.getString("session_id"),
            state = SessionState(
                sessionActive = state.optBoolean("session_active", false),
                sessionCompleted = state.optBoolean("session_completed", false),
                remainingSeconds = state.optDouble("remaining_seconds", 0.0),
                totalPoints = state.optInt("total_points", 0),
                throws = state.optInt("throws", 0)
            ),
            camera = CameraState(
                connected = camera.optBoolean("connected", false)
            )
        )
    }

    private fun parseSummary(summaryJson: JSONObject): SessionSummary {
        return SessionSummary(
            totalThrows = summaryJson.optInt("total_throws", 0),
            totalPoints = summaryJson.optInt("total_points", 0),
            noMistakeRate = summaryJson.optDouble("no_mistake_rate", 0.0)
        )
    }

    private fun parseThrowEvent(throwJson: JSONObject): ThrowEvent {
        return ThrowEvent(
            idx = throwJson.optInt("idx", 0),
            timestamp = throwJson.optString("timestamp", ""),
            elapsedSeconds = throwJson.optDouble("elapsed_s", 0.0),
            mistakeId = throwJson.opt("mistake_id")?.toString(),
            mistakeTitle = throwJson.optString("mistake_title", "No mistake detected"),
            feedback = throwJson.optString("feedback", ""),
            target = throwJson.optString("target", ""),
            points = throwJson.optInt("points", 0)
        )
    }

    private fun parseEvent(rawText: String): BackendEvent {
        val root = JSONObject(rawText)
        val type = root.optString("type", "unknown")

        return when (type) {
            "session_state" -> BackendEvent.SessionStateEvent(parseSnapshotFromEvent(root.getJSONObject("data")))
            "session_started" -> BackendEvent.SessionStartedEvent(parseSnapshotFromEvent(root.getJSONObject("data")))
            "session_reset" -> BackendEvent.SessionResetEvent(parseSnapshotFromEvent(root.getJSONObject("data")))
            "throw_event" -> BackendEvent.ThrowEventReceived(parseThrowEvent(root.getJSONObject("data")))
            "session_stopped" -> BackendEvent.SessionStoppedEvent(parseSummary(root.getJSONObject("summary")))
            "session_completed" -> BackendEvent.SessionCompletedEvent(parseSummary(root.getJSONObject("summary")))
            else -> BackendEvent.Unknown(type)
        }
    }

    private fun parseSnapshotFromEvent(snapshotJson: JSONObject): SessionSnapshot {
        val state = snapshotJson.getJSONObject("state")
        val camera = snapshotJson.getJSONObject("camera")
        return SessionSnapshot(
            sessionId = snapshotJson.getString("session_id"),
            state = SessionState(
                sessionActive = state.optBoolean("session_active", false),
                sessionCompleted = state.optBoolean("session_completed", false),
                remainingSeconds = state.optDouble("remaining_seconds", 0.0),
                totalPoints = state.optInt("total_points", 0),
                throws = state.optInt("throws", 0)
            ),
            camera = CameraState(
                connected = camera.optBoolean("connected", false)
            )
        )
    }

    private fun toWsBase(httpBase: String): String {
        return httpBase.trimEnd('/')
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}



