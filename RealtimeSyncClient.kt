package com.wechatsync.sync

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 实时同步客户端（WebSocket-like）
 *
 * 由于原生 Android 不内置 WebSocket，采用以下策略：
 *   · 优先使用 OkHttp WebSocket（如添加 okhttp 依赖）
 *   · 降级：Server-Sent Events（SSE）长连接
 *   · 再降级：15 秒轮询
 *
 * 新消息到达时通过 SharedFlow 广播给 UI
 */
class RealtimeSyncClient(
    private val serverUrl: String,
    private val deviceToken: String
) {

    private val TAG = "RealtimeSyncClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 新消息事件流（UI 层 collect）
    private val _newMessages = MutableSharedFlow<List<NewMessageEvent>>(replay = 0)
    val newMessages: SharedFlow<List<NewMessageEvent>> = _newMessages

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState

    private var sseJob: Job? = null

    data class NewMessageEvent(
        val msgId: String,
        val talkerWxId: String,
        val content: String,
        val type: Int,
        val createTime: Long,
        val mediaUrl: String?
    )

    sealed class ConnectionState {
        object Connected    : ConnectionState()
        object Disconnected : ConnectionState()
        data class Error(val msg: String) : ConnectionState()
    }

    // ─────────────────────────────────────────────
    // 启动 SSE 长连接
    // ─────────────────────────────────────────────

    fun startListening() {
        sseJob?.cancel()
        sseJob = scope.launch {
            _connectionState.emit(ConnectionState.Disconnected)
            connectWithRetry()
        }
    }

    private suspend fun connectWithRetry() {
        var retryDelay = 2_000L
        while (true) {
            try {
                listenSse()
                retryDelay = 2_000L
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "SSE 断开，${retryDelay}ms 后重连: ${e.message}")
                _connectionState.emit(ConnectionState.Error(e.message ?: "连接错误"))
                delay(retryDelay)
                retryDelay = (retryDelay * 1.5).toLong().coerceAtMost(30_000L)
            }
        }
    }

    /**
     * Server-Sent Events 连接
     * 服务器需要支持 GET /api/sync/events?token=xxx
     * 返回 Content-Type: text/event-stream
     */
    private suspend fun listenSse() = withContext(Dispatchers.IO) {
        val url = "$serverUrl/api/sync/events?token=${URLEncoder.encode(deviceToken, "UTF-8")}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 0 // 长连接不超时
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("X-Device-Token", deviceToken)
        }

        Log.i(TAG, "SSE 连接: $url")
        if (conn.responseCode != 200) {
            throw Exception("HTTP ${conn.responseCode}")
        }

        _connectionState.emit(ConnectionState.Connected)
        Log.i(TAG, "SSE 已连接")

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val eventData = StringBuilder()

        reader.use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                ensureActive()
                val l = line ?: continue
                when {
                    l.startsWith("data:") -> eventData.append(l.removePrefix("data:").trim())
                    l.isEmpty() && eventData.isNotBlank() -> {
                        // 事件结束，处理数据
                        handleSseEvent(eventData.toString())
                        eventData.clear()
                    }
                }
            }
        }
    }

    private suspend fun handleSseEvent(data: String) {
        try {
            val json = JSONObject(data)
            val type = json.optString("type")
            if (type == "new_messages") {
                val arr = json.optJSONArray("messages") ?: JSONArray()
                val events = (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    NewMessageEvent(
                        msgId      = m.getString("msgId"),
                        talkerWxId = m.getString("talkerWxId"),
                        content    = m.optString("content", ""),
                        type       = m.optInt("type", 1),
                        createTime = m.getLong("createTime"),
                        mediaUrl   = m.optString("mediaUrl").ifBlank { null }
                    )
                }
                if (events.isNotEmpty()) {
                    _newMessages.emit(events)
                    Log.d(TAG, "收到实时消息: ${events.size} 条")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSE 事件解析失败: $data")
        }
    }

    // ─────────────────────────────────────────────
    // 轮询降级方案（当 SSE 不可用时）
    // ─────────────────────────────────────────────

    private var pollJob: Job? = null

    fun startPolling(intervalMs: Long = 15_000L) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                try {
                    pollLatest()
                } catch (e: Exception) {
                    Log.w(TAG, "轮询失败: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private suspend fun pollLatest() {
        val url = "$serverUrl/api/sync/pull?device=$deviceToken&since=${System.currentTimeMillis() - 60_000}&limit=50"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("X-Device-Token", deviceToken)
        }
        if (conn.responseCode != 200) return
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val arr = json.optJSONArray("messages") ?: return
        if (arr.length() == 0) return

        val events = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            NewMessageEvent(
                msgId      = m.getString("msg_id"),
                talkerWxId = m.getString("talker_wx_id"),
                content    = m.optString("content", ""),
                type       = m.optInt("type", 1),
                createTime = m.getLong("create_time"),
                mediaUrl   = m.optString("media_url").ifBlank { null }
            )
        }
        _newMessages.emit(events)
    }

    fun stop() {
        sseJob?.cancel()
        pollJob?.cancel()
        scope.cancel()
    }
}
