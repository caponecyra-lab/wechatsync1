package com.wechatsync.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wechatsync.R
import com.wechatsync.data.Message
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 支持图片 / 语音 / 视频 / 文字 多类型消息的 RecyclerView Adapter
 */
class MediaMessageAdapter(
    private val context: Context,
    private val serverUrl: String,
    private val deviceToken: String
) : ListAdapter<Message, RecyclerView.ViewHolder>(MSG_DIFF) {

    companion object {
        const val TYPE_TEXT_RECV  = 0
        const val TYPE_TEXT_SEND  = 1
        const val TYPE_IMAGE_RECV = 2
        const val TYPE_IMAGE_SEND = 3
        const val TYPE_AUDIO_RECV = 4
        const val TYPE_AUDIO_SEND = 5
        const val TYPE_VIDEO_RECV = 6
        const val TYPE_VIDEO_SEND = 7

        private val TIME_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        val MSG_DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.msgId == b.msgId
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }
    }

    private var activePlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return when (msg.type) {
            3, 47 -> if (msg.isSend) TYPE_IMAGE_SEND else TYPE_IMAGE_RECV
            34    -> if (msg.isSend) TYPE_AUDIO_SEND else TYPE_AUDIO_RECV
            43    -> if (msg.isSend) TYPE_VIDEO_SEND else TYPE_VIDEO_RECV
            else  -> if (msg.isSend) TYPE_TEXT_SEND  else TYPE_TEXT_RECV
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        fun inflate(res: Int) = LayoutInflater.from(parent.context).inflate(res, parent, false)
        return when (viewType) {
            TYPE_TEXT_RECV, TYPE_TEXT_SEND ->
                TextVH(inflate(if (viewType == TYPE_TEXT_SEND) R.layout.item_msg_send else R.layout.item_msg_recv))
            TYPE_IMAGE_RECV, TYPE_IMAGE_SEND ->
                ImageVH(inflate(if (viewType == TYPE_IMAGE_SEND) R.layout.item_msg_image_send else R.layout.item_msg_image_recv))
            TYPE_AUDIO_RECV, TYPE_AUDIO_SEND ->
                AudioVH(inflate(if (viewType == TYPE_AUDIO_SEND) R.layout.item_msg_audio_send else R.layout.item_msg_audio_recv))
            else ->
                TextVH(inflate(R.layout.item_msg_recv))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is TextVH  -> holder.bind(msg)
            is ImageVH -> holder.bind(msg)
            is AudioVH -> holder.bind(msg)
        }
    }

    // ─────────────────────────────────────────────
    // 文字消息 ViewHolder
    // ─────────────────────────────────────────────

    inner class TextVH(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.tv_content)
        val time: TextView    = view.findViewById(R.id.tv_time)

        fun bind(msg: Message) {
            content.text = when (msg.type) {
                49   -> "[链接/小程序]"
                else -> msg.content.ifBlank { "[空消息]" }
            }
            time.text = TIME_FMT.format(Date(msg.createTime))
        }
    }

    // ─────────────────────────────────────────────
    // 图片消息 ViewHolder
    // ─────────────────────────────────────────────

    inner class ImageVH(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.iv_image)
        val loading: ProgressBar = view.findViewById(R.id.pb_loading)
        val time: TextView       = view.findViewById(R.id.tv_time)

        fun bind(msg: Message) {
            time.text = TIME_FMT.format(Date(msg.createTime))
            loading.visibility = View.VISIBLE
            imageView.setImageResource(R.drawable.ic_image_placeholder)

            scope.launch {
                val file = resolveMediaFile(msg)
                withContext(Dispatchers.Main) {
                    loading.visibility = View.GONE
                    if (file != null) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) imageView.setImageBitmap(bmp)
                        else imageView.setImageResource(R.drawable.ic_image_broken)
                    } else {
                        imageView.setImageResource(R.drawable.ic_image_broken)
                    }
                }
            }

            imageView.setOnClickListener {
                // 全屏查看（可接入 PhotoView 库）
            }
        }
    }

    // ─────────────────────────────────────────────
    // 语音消息 ViewHolder
    // ─────────────────────────────────────────────

    inner class AudioVH(view: View) : RecyclerView.ViewHolder(view) {
        val playBtn: ImageView  = view.findViewById(R.id.iv_play)
        val duration: TextView  = view.findViewById(R.id.tv_duration)
        val seekBar: SeekBar    = view.findViewById(R.id.seek_bar)
        val time: TextView      = view.findViewById(R.id.tv_time)

        private var mediaPlayer: MediaPlayer? = null
        private val handler = Handler(Looper.getMainLooper())

        private val progressUpdater = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        seekBar.progress = it.currentPosition * 100 / (it.duration.coerceAtLeast(1))
                        handler.postDelayed(this, 200)
                    }
                }
            }
        }

        fun bind(msg: Message) {
            time.text = TIME_FMT.format(Date(msg.createTime))
            duration.text = if (msg.type == 34) formatDuration(msg.createTime) else "?"
            seekBar.progress = 0
            playBtn.setImageResource(R.drawable.ic_play)

            playBtn.setOnClickListener {
                if (mediaPlayer?.isPlaying == true) {
                    stopPlayback()
                } else {
                    startPlayback(msg)
                }
            }
        }

        private fun startPlayback(msg: Message) {
            activePlayer?.stop()
            activePlayer?.release()

            scope.launch {
                val file = resolveMediaFile(msg) ?: return@launch
                withContext(Dispatchers.Main) {
                    try {
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            prepare()
                            start()
                            activePlayer = this
                            playBtn.setImageResource(R.drawable.ic_stop)
                            handler.post(progressUpdater)
                            setOnCompletionListener { stopPlayback() }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        private fun stopPlayback() {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            handler.removeCallbacks(progressUpdater)
            playBtn.setImageResource(R.drawable.ic_play)
            seekBar.progress = 0
        }

        private fun formatDuration(createTime: Long): String {
            // 语音时长存在 duration_sec 字段，createTime 作为后备
            return "00:00"
        }
    }

    // ─────────────────────────────────────────────
    // 媒体文件解析（本地缓存 → 服务器下载）
    // ─────────────────────────────────────────────

    private suspend fun resolveMediaFile(msg: Message): File? = withContext(Dispatchers.IO) {
        // 1. 本地路径（旧手机直接读取）
        if (msg.mediaPath.isNotBlank()) {
            val local = File(msg.mediaPath)
            if (local.exists()) return@withContext local
        }

        // 2. 本地缓存目录
        val subDir = when (msg.type) {
            34   -> "audio"
            43   -> "video"
            else -> "images"
        }
        val ext = when (msg.type) {
            34   -> ".amr"
            43   -> ".mp4"
            else -> ".jpg"
        }
        val cached = File(MediaSyncManager.getMediaDir(context, subDir), "${msg.msgId}$ext")
        if (cached.exists()) return@withContext cached

        // 3. 从服务器下载
        val url = if (serverUrl.isNotBlank() && msg.msgId.isNotBlank()) {
            // 先查询媒体信息
            try {
                val conn = (java.net.URL("$serverUrl/api/media/${msg.msgId}").openConnection()
                    as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    setRequestProperty("X-Device-Token", deviceToken)
                }
                if (conn.responseCode == 200) {
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    json.optString("url", "")
                } else ""
            } catch (e: Exception) { "" }
        } else ""

        if (url.isBlank()) return@withContext null
        MediaSyncManager.downloadMedia(context, url, msg.msgId, msg.type)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scope.cancel()
        activePlayer?.release()
        activePlayer = null
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
