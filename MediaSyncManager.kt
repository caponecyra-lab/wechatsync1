package com.wechatsync.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Environment
import com.wechatsync.data.Message
import com.wechatsync.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 媒体文件同步管理器
 *
 * 支持的消息类型：
 *   type=3   → 图片（.jpg）
 *   type=34  → 语音（.amr / .silk）
 *   type=43  → 视频（.mp4）
 *   type=47  → 表情包（.gif）
 *
 * 媒体文件在微信目录的位置（无Root，通过备份）：
 *   图片:   MicroMsg/<hash>/image2/<2位>/<4位>/<filename>
 *   语音:   MicroMsg/<hash>/voice2/<2位>/<4位>/<filename>
 *   视频:   MicroMsg/<hash>/video/<filename>
 *   表情:   MicroMsg/<hash>/emoji/<filename>
 */
object MediaSyncManager {

    // 媒体文件本地缓存目录
    fun getMediaDir(context: Context, sub: String): File {
        val dir = File(context.filesDir, "media/$sub")
        dir.mkdirs()
        return dir
    }

    // ─────────────────────────────────────────────
    // 1. 从微信备份路径提取媒体文件
    // ─────────────────────────────────────────────

    /**
     * 根据消息信息定位微信备份里的媒体文件
     * @param backupRoot 微信备份根目录（包含 image2 / voice2 / video）
     */
    fun locateMediaFile(backupRoot: String, msg: Message): File? {
        val root = File(backupRoot)
        return when (msg.type) {
            3, 47 -> locateImage(root, msg)
            34    -> locateVoice(root, msg)
            43    -> locateVideo(root, msg)
            else  -> null
        }
    }

    private fun locateImage(root: File, msg: Message): File? {
        // 微信图片路径：image2/<前2位>/<后4位>/<md5>.jpg
        val imgDir = File(root, "image2")
        if (!imgDir.exists()) return null
        // 尝试从 content 字段提取路径（备份中通常是相对路径）
        if (msg.content.contains("/image2/")) {
            val rel = msg.content.substringAfter("image2/").trim()
            val candidate = File(imgDir, rel)
            if (candidate.exists()) return candidate
        }
        // 按消息ID暴力搜索（备份量不大时可行）
        val prefix = msg.msgId.takeLast(8).take(4).lowercase()
        return imgDir.walkTopDown()
            .filter { it.isFile && it.name.contains(prefix) }
            .firstOrNull()
    }

    private fun locateVoice(root: File, msg: Message): File? {
        val voiceDir = File(root, "voice2")
        if (!voiceDir.exists()) return null
        val prefix = msg.msgId.takeLast(8).take(4).lowercase()
        return voiceDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".amr") || it.name.endsWith(".silk")) }
            .firstOrNull { it.name.contains(prefix) }
    }

    private fun locateVideo(root: File, msg: Message): File? {
        val videoDir = File(root, "video")
        return videoDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".mp4") }
            .firstOrNull { it.name.contains(msg.msgId.takeLast(6)) }
    }

    // ─────────────────────────────────────────────
    // 2. 上传媒体文件到服务器
    // ─────────────────────────────────────────────

    data class UploadResult(
        val success: Boolean,
        val mediaKey: String = "",
        val urlPath: String = "",
        val thumbPath: String = "",
        val errorMsg: String = ""
    )

    suspend fun uploadMedia(
        context: Context,
        serverUrl: String,
        deviceToken: String,
        mediaFile: File,
        msgId: String,
        mediaKey: String,
        type: Int          // 消息类型
    ): UploadResult = withContext(Dispatchers.IO) {
        if (!mediaFile.exists()) return@withContext UploadResult(false, errorMsg = "文件不存在")

        return@withContext try {
            val fieldName = when (type) {
                34   -> "audio"
                43   -> "video"
                else -> "image"
            }

            // 生成缩略图（图片和视频）
            val thumbFile: File? = when (type) {
                3    -> makeThumbnail(context, mediaFile)
                43   -> makeVideoThumbnail(context, mediaFile)
                else -> null
            }

            val boundary = "----WeChatSyncBoundary${System.currentTimeMillis()}"
            val conn = (URL("$serverUrl/api/media/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("X-Device-Token", deviceToken)
            }

            conn.outputStream.use { out ->
                // msgId 字段
                writeFormField(out, boundary, "msgId", msgId)
                writeFormField(out, boundary, "mediaKey", mediaKey)
                // 媒体文件
                writeFilePart(out, boundary, fieldName, mediaFile)
                // 缩略图
                thumbFile?.let { writeFilePart(out, boundary, "thumb", it) }
                // 结束
                out.write("--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val fileObj = json.optJSONObject("files")?.optJSONObject(fieldName)
                UploadResult(
                    success  = true,
                    mediaKey = mediaKey,
                    urlPath  = fileObj?.optString("urlPath", "") ?: "",
                    thumbPath = fileObj?.optString("thumbPath", "") ?: ""
                )
            } else {
                UploadResult(false, errorMsg = "HTTP $code")
            }
        } catch (e: Exception) {
            UploadResult(false, errorMsg = e.message ?: "上传失败")
        }
    }

    private fun writeFormField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
    }

    private fun writeFilePart(out: java.io.OutputStream, boundary: String, fieldName: String, file: File) {
        val mime = guessMime(file)
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        file.inputStream().use { it.copyTo(out) }
        out.write("\r\n".toByteArray())
    }

    // ─────────────────────────────────────────────
    // 3. 下载媒体文件（新手机接收）
    // ─────────────────────────────────────────────

    suspend fun downloadMedia(
        context: Context,
        url: String,
        msgId: String,
        type: Int
    ): File? = withContext(Dispatchers.IO) {
        return@withContext try {
            val ext = when (type) {
                3, 47 -> ".jpg"
                34    -> ".amr"
                43    -> ".mp4"
                else  -> ".bin"
            }
            val subDir = when (type) {
                34   -> "audio"
                43   -> "video"
                else -> "images"
            }
            val outFile = File(getMediaDir(context, subDir), "${msgId}$ext")
            if (outFile.exists()) return@withContext outFile  // 已缓存

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                outFile
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ─────────────────────────────────────────────
    // 4. 缩略图生成
    // ─────────────────────────────────────────────

    private fun makeThumbnail(context: Context, imageFile: File): File? {
        return try {
            val bmp = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
            val thumb = ThumbnailUtils.extractThumbnail(bmp, 200, 200)
            val out = File(getMediaDir(context, "thumb"), "th_${imageFile.name}")
            FileOutputStream(out).use { thumb.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            bmp.recycle(); thumb.recycle()
            out
        } catch (e: Exception) { null }
    }

    private fun makeVideoThumbnail(context: Context, videoFile: File): File? {
        return try {
            @Suppress("DEPRECATION")
            val bmp = ThumbnailUtils.createVideoThumbnail(
                videoFile.absolutePath,
                android.provider.MediaStore.Video.Thumbnails.MINI_KIND
            ) ?: return null
            val out = File(getMediaDir(context, "thumb"), "th_${videoFile.nameWithoutExtension}.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            bmp.recycle()
            out
        } catch (e: Exception) { null }
    }

    private fun guessMime(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "amr"         -> "audio/amr"
            "silk"        -> "audio/silk"
            "m4a"         -> "audio/mp4"
            "mp3"         -> "audio/mpeg"
            "mp4"         -> "video/mp4"
            else          -> "application/octet-stream"
        }
    }

    // ─────────────────────────────────────────────
    // 5. 批量扫描备份目录中所有媒体文件
    // ─────────────────────────────────────────────

    data class MediaStats(
        val images: Int, val audio: Int, val video: Int, val totalSizeBytes: Long
    )

    fun scanMediaStats(backupRoot: String): MediaStats {
        val root = File(backupRoot)
        var images = 0; var audio = 0; var video = 0; var totalSize = 0L

        root.walkTopDown().filter { it.isFile }.forEach { f ->
            totalSize += f.length()
            when {
                f.extension.matches(Regex("jpg|jpeg|png|gif|webp")) -> images++
                f.extension.matches(Regex("amr|silk|m4a|mp3"))      -> audio++
                f.extension.matches(Regex("mp4|mov"))                -> video++
            }
        }
        return MediaStats(images, audio, video, totalSize)
    }
}
