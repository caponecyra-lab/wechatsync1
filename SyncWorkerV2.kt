package com.wechatsync.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.wechatsync.WeChatSyncApp
import com.wechatsync.data.SyncLog
import com.wechatsync.media.MediaSyncManager
import com.wechatsync.network.SyncApiClient
import com.wechatsync.utils.PrefsHelper
import com.wechatsync.utils.WeChatBackupReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

private const val TAG = "SyncWorkerV2"

/**
 * 完整同步流程（V2）
 *
 * 步骤：
 *  1. 增量读取联系人（只更新有变化的）
 *  2. 按会话增量读取文字消息（sinceTime 游标）
 *  3. 扫描新增媒体文件并批量上传
 *  4. 推送到服务器（分批，每批 200 条）
 *  5. 写入同步日志
 *  6. 如果是「新手机」角色，从服务器增量拉取
 */
class SyncWorkerV2(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs  = PrefsHelper(context)
        val db     = WeChatSyncApp.instance.database
        val client = SyncApiClient(prefs.serverUrl, prefs.deviceToken)

        if (prefs.backupDbPath.isBlank() || prefs.serverUrl.isBlank()) {
            return@withContext Result.failure(workDataOf("error" to "未配置备份路径或服务器"))
        }

        return@withContext try {
            var totalNewMsgs = 0
            var totalMediaUp = 0

            // ── 1. 读取联系人 ──────────────────────
            val contacts = WeChatBackupReader.readContacts(prefs.backupDbPath)
            if (contacts.isEmpty()) {
                Log.w(TAG, "未读取到任何联系人，请检查备份路径")
            }
            db.contactDao().insertAll(contacts)

            // ── 2. 按会话增量读取消息 ──────────────
            val newMsgsAll = mutableListOf<com.wechatsync.data.Message>()

            contacts.forEach { contact ->
                val since = db.messageDao().getLatestTime(contact.wxId) ?: 0L
                val msgs  = WeChatBackupReader.readMessages(
                    prefs.backupDbPath, contact.wxId, since
                )
                if (msgs.isNotEmpty()) {
                    db.messageDao().insertAll(msgs)
                    newMsgsAll.addAll(msgs)
                    msgs.lastOrNull()?.let { last ->
                        db.contactDao().updateLastMsg(contact.wxId, last.createTime, last.content)
                    }
                }
            }
            totalNewMsgs = newMsgsAll.size
            Log.i(TAG, "新消息：$totalNewMsgs 条")

            // ── 3. 上传媒体文件 ────────────────────
            val mediaMessages = newMsgsAll.filter { it.type in listOf(3, 34, 43, 47) }
            val backupRoot = deriveBackupRoot(prefs.backupDbPath)

            mediaMessages.forEach { msg ->
                val mediaFile = MediaSyncManager.locateMediaFile(backupRoot, msg) ?: return@forEach
                val mediaKey  = md5short("${msg.msgId}_${mediaFile.name}")

                val result = MediaSyncManager.uploadMedia(
                    context      = context,
                    serverUrl    = prefs.serverUrl,
                    deviceToken  = prefs.deviceToken,
                    mediaFile    = mediaFile,
                    msgId        = msg.msgId,
                    mediaKey     = mediaKey,
                    type         = msg.type
                )
                if (result.success) {
                    totalMediaUp++
                    Log.d(TAG, "上传成功: ${msg.type} key=${result.mediaKey}")
                } else {
                    Log.w(TAG, "上传失败: ${msg.msgId} → ${result.errorMsg}")
                }
            }

            // ── 4. 推送文字消息到服务器（分批200条）──
            newMsgsAll.chunked(200).forEach { batch ->
                try {
                    client.pushMessages(contacts, batch)
                } catch (e: Exception) {
                    Log.e(TAG, "推送失败: ${e.message}")
                }
            }

            // ── 5. 如果是新手机，增量拉取 ──────────
            var pulled = 0
            if (prefs.deviceRole == PrefsHelper.ROLE_TARGET) {
                val since = db.messageDao().getLatestTime("") ?: 0L
                val result = client.pullMessages(since)
                if (result.success && result.messages.isNotEmpty()) {
                    db.messageDao().insertAll(result.messages)
                    pulled = result.messages.size

                    // 下载新消息的媒体文件
                    result.messages
                        .filter { it.type in listOf(3, 34, 43, 47) && !it.mediaPath.isNullOrBlank() }
                        .forEach { msg ->
                            MediaSyncManager.downloadMedia(context, msg.mediaPath!!, msg.msgId, msg.type)
                        }
                }
            }

            // ── 6. 写日志 ──────────────────────────
            val detail = "新消息=${totalNewMsgs} 媒体上传=${totalMediaUp} 拉取=${pulled}"
            db.syncLogDao().insert(SyncLog(newMessages = totalNewMsgs + pulled, detail = detail))
            Log.i(TAG, "同步完成: $detail")

            Result.success(workDataOf(
                "newMessages"   to totalNewMsgs,
                "mediaUploaded" to totalMediaUp,
                "pulled"        to pulled
            ))

        } catch (e: Exception) {
            Log.e(TAG, "同步异常", e)
            db.syncLogDao().insert(SyncLog(status = "failed", detail = e.message ?: "unknown"))
            Result.retry()
        }
    }

    /** 从 db 路径推导媒体备份根目录 */
    private fun deriveBackupRoot(dbPath: String): String {
        // dbPath 通常在 filesDir/wechat_backup.db
        // 媒体文件可能在同级目录或 MicroMsg/<hash>/ 下
        val dbFile = java.io.File(dbPath)
        // 尝试同级的 image2 / voice2 目录
        val sibling = dbFile.parentFile
        return if (sibling?.resolve("image2")?.exists() == true)
            sibling.absolutePath
        else
            dbFile.parent ?: dbFile.absolutePath
    }

    private fun md5short(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }
}

// ─────────────────────────────────────────────────
// WorkManager 注册（在 WeChatSyncApp 中调用）
// ─────────────────────────────────────────────────

fun scheduleSyncWork(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // 周期任务：每 15 分钟
    val periodic = PeriodicWorkRequestBuilder<SyncWorkerV2>(
        15, java.util.concurrent.TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .addTag("wechat_sync_v2")
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "wechat_sync_v2",
        ExistingPeriodicWorkPolicy.KEEP,
        periodic
    )

    // 立即触发一次
    val immediate = OneTimeWorkRequestBuilder<SyncWorkerV2>()
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueue(immediate)
}
