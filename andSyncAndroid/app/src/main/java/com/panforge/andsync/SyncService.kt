package com.panforge.andsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "andsync_sync_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START_SYNC = "com.panforge.andsync.ACTION_START_SYNC"
        const val ACTION_CANCEL_SYNC = "com.panforge.andsync.ACTION_CANCEL_SYNC"

        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_TARGET_FOLDER_URI = "extra_target_folder_uri"
        const val EXTRA_PLAYLISTS_JSON = "extra_playlists_json"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"

        fun startSync(
            context: Context,
            serverHost: String,
            targetFolderUri: Uri?,
            playlists: List<PlaylistItem>,
            authToken: String = ""
        ) {
            val token = if (authToken.isNotBlank()) authToken else {
                context.getSharedPreferences("andsync_prefs", Context.MODE_PRIVATE).getString("server_token", "") ?: ""
            }
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_SERVER_HOST, serverHost)
                putExtra(EXTRA_TARGET_FOLDER_URI, targetFolderUri?.toString())
                putExtra(EXTRA_PLAYLISTS_JSON, Gson().toJson(playlists))
                putExtra(EXTRA_AUTH_TOKEN, token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelSync(context: Context) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_CANCEL_SYNC
            }
            context.startService(intent)
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var syncJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private lateinit var notificationManager: NotificationManager

    private var lastNotificationUpdateTime = 0L

    private fun sanitizeFolderName(name: String, fallback: String): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .trimEnd('.')
            .trim()
        return if (sanitized.isBlank()) fallback else sanitized
    }

    private fun sanitizeFileName(name: String, fallback: String = "track.mp3"): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (sanitized.isBlank()) fallback else sanitized
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val serverHost = intent.getStringExtra(EXTRA_SERVER_HOST) ?: return START_NOT_STICKY
                val uriStr = intent.getStringExtra(EXTRA_TARGET_FOLDER_URI)
                val targetFolderUri = uriStr?.let { Uri.parse(it) }
                val playlistsJson = intent.getStringExtra(EXTRA_PLAYLISTS_JSON) ?: "[]"
                val listType = object : TypeToken<List<PlaylistItem>>() {}.type
                val playlists: List<PlaylistItem> = gson.fromJson(playlistsJson, listType)
                val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: ""

                acquireLocks()
                startForegroundWithNotification("同期準備中...", 0, 0, ongoing = true)
                SyncStateManager.startSync()

                syncJob?.cancel()
                syncJob = serviceScope.launch {
                    try {
                        executeSync(serverHost, playlists, targetFolderUri, authToken)
                    } catch (e: CancellationException) {
                        SyncStateManager.finishCancelled()
                        showFinishedNotification("同期を中止しました", isError = false)
                    } catch (e: Exception) {
                        SyncStateManager.failSync(e.message ?: "不明なエラー")
                        showFinishedNotification("同期失敗: ${e.message}", isError = true)
                    } finally {
                        releaseLocks()
                        stopForegroundSafely()
                        stopSelf()
                    }
                }
            }

            ACTION_CANCEL_SYNC -> {
                SyncStateManager.requestCancel()
                syncJob?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andSync:SyncWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 最大1時間
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wm.createWifiLock(mode, "andSync:WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "andSync 音楽同期",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "iTunesライブラリとAndroidのWi-Fi同期進行状況を表示します"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        content: String,
        current: Int,
        total: Int,
        ongoing: Boolean
    ): NotificationCompat.Builder {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_CANCEL_SYNC
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("andSyncTunes 楽曲同期")
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (ongoing) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "中止", cancelPendingIntent)
            if (total > 0) {
                builder.setProgress(total, current, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setProgress(0, 0, false)
            builder.setAutoCancel(true)
        }

        return builder
    }

    private fun startForegroundWithNotification(content: String, current: Int, total: Int, ongoing: Boolean) {
        val notification = buildNotification(content, current, total, ongoing).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String, current: Int, total: Int, ongoing: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastNotificationUpdateTime >= 400L) {
            lastNotificationUpdateTime = now
            val notification = buildNotification(content, current, total, ongoing).build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun showFinishedNotification(content: String, isError: Boolean) {
        val icon = if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done
        val notification = buildNotification(content, 0, 0, ongoing = false)
            .setSmallIcon(icon)
            .setContentTitle(if (isError) "andSync 同期エラー" else "andSync 同期完了")
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private suspend fun executeSync(
        serverHost: String,
        selectedPlaylists: List<PlaylistItem>,
        targetFolderUri: Uri?,
        authToken: String
    ) = withContext(Dispatchers.IO) {
        if (selectedPlaylists.isEmpty()) {
            throw Exception("同期対象のプレイリストが選択されていません")
        }

        if (!SyncStateManager.isWifiConnected(applicationContext)) {
            throw Exception("Wi-Fiに接続されていません。パケット保護のため同期を中止しました")
        }

        // 1. 各プレイリストの楽曲リストを取得
        val playlistTrackMap = mutableMapOf<PlaylistItem, List<TrackDetail>>()
        val uniqueTracksMap = mutableMapOf<Int, TrackDetail>()

        selectedPlaylists.forEach { pl ->
            ensureActive()
            if (SyncStateManager.isCancelRequested) throw CancellationException()

            val url = "http://$serverHost/api/playlists/${pl.playlistId}"
            val reqBuilder = Request.Builder().url(url)
            if (authToken.isNotBlank()) {
                reqBuilder.header("X-AndSync-Token", authToken)
            }
            val detailJson = httpClient.newCall(reqBuilder.build()).execute().use { res ->
                if (res.code == 401) throw Exception("🔒 認証エラー: PCとのペアリングが一致しません。QRコードを再スキャンしてください。")
                if (res.code == 403) throw Exception("🚫 宅外ネットワークからのアクセスは遮断されています。Wi-Fiまたはテザリングに接続してください。")
                if (!res.isSuccessful) throw Exception("プレイリスト「${pl.name}」の取得失敗: HTTP ${res.code}")
                res.body?.string() ?: ""
            }
            val detail = gson.fromJson(detailJson, PlaylistDetailResponse::class.java)
            playlistTrackMap[pl] = detail.tracks
            detail.tracks.forEach { track ->
                uniqueTracksMap[track.trackId] = track
            }
        }

        // 各楽曲がどのプレイリストに含まれているかをマッピング（エラー詳細用）
        val trackPlaylistsMap = mutableMapOf<Int, MutableList<String>>()
        playlistTrackMap.forEach { (pl, tracks) ->
            tracks.forEach { t ->
                trackPlaylistsMap.getOrPut(t.trackId) { mutableListOf() }.add(pl.name)
            }
        }

        val uniqueTracks = uniqueTracksMap.values.toList()
        val totalUniqueCount = uniqueTracks.size
        val trackRelativePathMap = mutableMapOf<Int, String>()
        var downloadedCount = 0
        var skippedCount = 0
        val failedTracks = mutableListOf<Pair<TrackDetail, String>>()
        val downloadedTracks = mutableListOf<TrackDetail>()

        if (targetFolderUri != null) {
            // SAF (microSD / ストレージ)
            val targetDoc = DocumentFile.fromTreeUri(applicationContext, targetFolderUri)
                ?: throw Exception("指定されたフォルダーへのアクセス権がありません")

            if (!targetDoc.canWrite()) {
                throw Exception("保存先フォルダーの書き込み権限がありません。[設定]タブで再選択してください")
            }

            // 既存のアーティストディレクトリを事前キャッシュ
            val existingArtistMap = targetDoc.listFiles()
                .filter { it.isDirectory && it.name != null }
                .associateBy { it.name!!.lowercase() }
                .toMutableMap()

            val albumDirCache = mutableMapOf<String, DocumentFile>()

            uniqueTracks.forEachIndexed { index, track ->
                ensureActive()
                if (SyncStateManager.isCancelRequested) throw CancellationException()
                if (!SyncStateManager.isWifiConnected(applicationContext)) {
                    throw Exception("Wi-Fi切断を検知しました。パケット保護のため同期を中断しました")
                }

                val artistName = sanitizeFolderName(track.artist ?: "", "Unknown Artist")
                val albumName = sanitizeFolderName(track.album ?: "", "Unknown Album")
                val rawFileName = track.fileName ?: "${track.name}.mp3"
                val safeFileName = sanitizeFileName(rawFileName)

                // アーティストフォルダ取得または作成
                val artistKey = artistName.lowercase()
                val artistDoc = existingArtistMap[artistKey] ?: run {
                    val existing = targetDoc.findFile(artistName)?.takeIf { it.isDirectory }
                    val created = existing ?: targetDoc.createDirectory(artistName)
                        ?: targetDoc.findFile(artistName)?.takeIf { it.isDirectory }
                        ?: throw Exception("アーティストフォルダ作成失敗: $artistName")
                    existingArtistMap[artistKey] = created
                    created
                }

                // アルバムフォルダ取得または作成
                val albumKey = "$artistKey/${albumName.lowercase()}"
                val albumDoc = albumDirCache.getOrPut(albumKey) {
                    val existingAlbum = artistDoc.findFile(albumName)?.takeIf { it.isDirectory }
                        ?: artistDoc.listFiles().firstOrNull { it.isDirectory && it.name?.equals(albumName, ignoreCase = true) == true }
                    existingAlbum ?: (artistDoc.createDirectory(albumName)
                        ?: artistDoc.findFile(albumName)?.takeIf { it.isDirectory }
                        ?: throw Exception("アルバムフォルダ作成失敗: $albumName"))
                }

                val relativePath = "$artistName/$albumName/$safeFileName"
                trackRelativePathMap[track.trackId] = relativePath

                val existingFile = albumDoc.findFile(safeFileName)
                val expectedSize = track.fileSize ?: 0L
                val expectedModified = track.lastModified ?: 0L

                val isUpToDate = if (existingFile != null && existingFile.exists() && expectedSize > 0L) {
                    val localSize = existingFile.length()
                    val localModified = existingFile.lastModified()
                    localSize == expectedSize && localModified >= expectedModified
                } else {
                    false
                }

                if (isUpToDate) {
                    skippedCount++
                    SyncStateManager.updateProgress(index + 1, totalUniqueCount, "スキップ (一致): ${track.name}")
                    updateNotification("スキップ (${index + 1}/$totalUniqueCount): ${track.name}", index + 1, totalUniqueCount, ongoing = true)
                } else {
                    val maxRetries = 3
                    var isDownloaded = false
                    var trackErrorMsg = ""

                    for (attempt in 1..maxRetries) {
                        ensureActive()
                        if (SyncStateManager.isCancelRequested) throw CancellationException("ユーザーによる同期中止")

                        try {
                            if (attempt > 1) {
                                SyncStateManager.updateProgress(index + 1, totalUniqueCount, "再試行中 ($attempt/$maxRetries): ${track.name}")
                                updateNotification("再試行中 ($attempt/$maxRetries): ${track.name}", index + 1, totalUniqueCount, ongoing = true)
                                delay(600L)
                            } else {
                                SyncStateManager.updateProgress(index + 1, totalUniqueCount, "転送中: ${track.name}")
                                updateNotification("転送中 (${index + 1}/$totalUniqueCount): ${track.name}", index + 1, totalUniqueCount, ongoing = true)
                            }

                            val queryJoin = if (track.downloadUrl.contains("?")) "&" else "?"
                            val downloadUrl = if (authToken.isNotBlank()) {
                                "http://$serverHost${track.downloadUrl}${queryJoin}token=$authToken"
                            } else {
                                "http://$serverHost${track.downloadUrl}"
                            }
                            val fileReqBuilder = Request.Builder().url(downloadUrl)
                            if (authToken.isNotBlank()) {
                                fileReqBuilder.header("X-AndSync-Token", authToken)
                            }
                            val fileReq = fileReqBuilder.build()

                            httpClient.newCall(fileReq).execute().use { res ->
                                if (res.code == 401) {
                                    throw Exception("🔒 認証エラー: PCとのペアリングが一致しません。QRコードを再スキャンしてください。")
                                }
                                if (res.code == 403) {
                                    throw Exception("🚫 宅外ネットワークからのアクセスは遮断されています。")
                                }
                                if (!res.isSuccessful) {
                                    var serverErrMsg = ""
                                    try {
                                        val errBody = res.body?.string() ?: ""
                                        if (errBody.contains("\"error\":")) {
                                            val jsonErr = gson.fromJson(errBody, Map::class.java)
                                            serverErrMsg = jsonErr["error"]?.toString() ?: ""
                                        }
                                    } catch (_: Exception) {}
                                    val finalMsg = if (serverErrMsg.isNotBlank()) serverErrMsg else "HTTP ${res.code}"
                                    throw Exception(finalMsg)
                                }

                                val destDoc = existingFile ?: albumDoc.createFile("audio/*", safeFileName)
                                    ?: throw Exception("ファイル作成に失敗しました: $safeFileName")

                                var isWriteSuccess = false
                                try {
                                    contentResolver.openOutputStream(destDoc.uri)?.use { output ->
                                        res.body?.byteStream()?.use { input ->
                                            val buffer = ByteArray(8192)
                                            var readBytes: Int
                                            while (input.read(buffer).also { readBytes = it } != -1) {
                                                if (SyncStateManager.isCancelRequested || !isActive) {
                                                    throw CancellationException("ユーザーによる同期中止")
                                                }
                                                output.write(buffer, 0, readBytes)
                                            }
                                            output.flush()
                                        }
                                    }
                                    isWriteSuccess = true
                                    downloadedCount++
                                    downloadedTracks.add(track)
                                    isDownloaded = true

                                    // 10曲転送ごとにストレージ空き容量を半リアルタイム取得・更新 (StatFsは軽量システムコールのため速度影響ゼロ)
                                    if (downloadedCount % 10 == 0) {
                                        try {
                                            val currentFree = getAvailableStorageBytes(this@SyncService, targetFolderUri)
                                            if (currentFree > 0L) {
                                                SyncStateManager.updateStorage(currentFree)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                } finally {
                                    if (!isWriteSuccess) {
                                        // 中断・失敗時に破損ファイルを削除
                                        try { destDoc.delete() } catch (_: Exception) {}
                                    }
                                }
                            }
                            if (isDownloaded) break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            trackErrorMsg = e.message ?: "エラー"
                            if (attempt == maxRetries) {
                                failedTracks.add(Pair(track, trackErrorMsg))
                            }
                        }
                    }
                }
            }

            // M3U8 生成（事前に既存の.m3u8ファイルをキャッシュして高速化）
            SyncStateManager.updateCustomProgress(0, 0, "プレイリスト更新中...")
            updateNotification("プレイリスト更新中...", 0, 0, ongoing = true)

            val existingM3u8Map = targetDoc.listFiles()
                .filter { it.isFile && it.name != null && it.name!!.endsWith(".m3u8", ignoreCase = true) }
                .associateBy { it.name!!.lowercase() }
                .toMutableMap()

            val totalPlaylists = selectedPlaylists.size
            selectedPlaylists.forEachIndexed { pIndex, pl ->
                ensureActive()
                if (SyncStateManager.isCancelRequested) throw CancellationException()

                val tracks = playlistTrackMap[pl] ?: emptyList()
                val safePlaylistBase = pl.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').trim().ifEmpty { "playlist" }
                val safePlaylistName = "$safePlaylistBase.m3u8"
                val m3u8Key = safePlaylistName.lowercase()
                val m3u8Doc = existingM3u8Map[m3u8Key] ?: targetDoc.createFile("audio/x-mpegurl", safePlaylistName)?.also {
                    existingM3u8Map[m3u8Key] = it
                }
                if (m3u8Doc != null) {
                    contentResolver.openOutputStream(m3u8Doc.uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                            writer.write("#EXTM3U\n")
                            tracks.forEach { t ->
                                val relPath = trackRelativePathMap[t.trackId]
                                if (relPath != null) {
                                    writer.write("$relPath\n")
                                }
                            }
                        }
                    }
                }
            }

            // 同期完了時にも最新の空き容量を確定反映
            try {
                val finalFree = getAvailableStorageBytes(this@SyncService, targetFolderUri)
                if (finalFree > 0L) {
                    SyncStateManager.updateStorage(finalFree)
                }
            } catch (_: Exception) {}

            val resultMsg = if (failedTracks.isEmpty()) {
                "同期完了！ ${selectedPlaylists.size}件のプレイリスト\n(転送: ${downloadedCount}曲, スキップ: ${skippedCount}曲)"
            } else {
                "同期完了 (一部失敗あり): ${selectedPlaylists.size}件のプレイリスト\n(転送: ${downloadedCount}曲, スキップ: ${skippedCount}曲, ⚠️ 失敗: ${failedTracks.size}曲)"
            }
            val resultItems = mutableListOf<SyncResultItem>()
            downloadedTracks.forEach { track ->
                val plNames = trackPlaylistsMap[track.trackId]?.joinToString(", ") ?: "なし"
                resultItems.add(
                    SyncResultItem(
                        trackId = track.trackId,
                        trackName = track.name,
                        artist = track.artist,
                        album = track.album,
                        playlistName = plNames,
                        isSuccess = true
                    )
                )
            }
            failedTracks.forEach { (track, errMsg) ->
                val plNames = trackPlaylistsMap[track.trackId]?.joinToString(", ") ?: "なし"
                resultItems.add(
                    SyncResultItem(
                        trackId = track.trackId,
                        trackName = track.name,
                        artist = track.artist,
                        album = track.album,
                        playlistName = plNames,
                        isSuccess = false,
                        errorMessage = errMsg
                    )
                )
            }
            SyncStateManager.completeSync(resultMsg, resultItems)
            showFinishedNotification(resultMsg, isError = failedTracks.isNotEmpty())
        } else {
            // アプリ専用領域 fallback
            val musicDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "Music")
            val targetDir = File(musicDir, "andSync")
            if (!targetDir.exists()) targetDir.mkdirs()

            uniqueTracks.forEachIndexed { index, track ->
                ensureActive()
                if (SyncStateManager.isCancelRequested) throw CancellationException()
                if (!SyncStateManager.isWifiConnected(applicationContext)) {
                    throw Exception("Wi-Fi切断を検知しました。パケット保護のため同期を中断しました")
                }

                val artistName = sanitizeFolderName(track.artist ?: "", "Unknown Artist")
                val albumName = sanitizeFolderName(track.album ?: "", "Unknown Album")
                val rawFileName = track.fileName ?: "${track.name}.mp3"
                val safeFileName = sanitizeFileName(rawFileName)

                val albumDir = File(File(targetDir, artistName), albumName)
                if (!albumDir.exists()) albumDir.mkdirs()

                val relativePath = "$artistName/$albumName/$safeFileName"
                trackRelativePathMap[track.trackId] = relativePath

                val destFile = File(albumDir, safeFileName)
                val expectedSize = track.fileSize ?: 0L
                val expectedModified = track.lastModified ?: 0L

                val isUpToDate = if (destFile.exists() && expectedSize > 0L) {
                    destFile.length() == expectedSize && destFile.lastModified() >= expectedModified
                } else {
                    false
                }

                if (isUpToDate) {
                    skippedCount++
                    SyncStateManager.updateProgress(index + 1, totalUniqueCount, "スキップ (一致): ${track.name}")
                    updateNotification("スキップ (${index + 1}/$totalUniqueCount): ${track.name}", index + 1, totalUniqueCount, ongoing = true)
                } else {
                    val maxRetries = 3
                    var isDownloaded = false
                    var trackErrorMsg = ""

                    for (attempt in 1..maxRetries) {
                        ensureActive()
                        if (SyncStateManager.isCancelRequested) throw CancellationException("ユーザーによる同期中止")

                        try {
                            if (attempt > 1) {
                                SyncStateManager.updateProgress(index + 1, totalUniqueCount, "再試行中 ($attempt/$maxRetries): ${track.name}")
                                updateNotification("再試行中 ($attempt/$maxRetries): ${track.name}", index + 1, totalUniqueCount, ongoing = true)
                                delay(600L)
                            } else {
                                SyncStateManager.updateProgress(index + 1, totalUniqueCount, "転送中: ${track.name}")
                                updateNotification("転送中 (${index + 1}/$totalUniqueCount): ${track.name}", index + 1, totalUniqueCount, ongoing = true)
                            }

                            val queryJoin = if (track.downloadUrl.contains("?")) "&" else "?"
                            val downloadUrl = if (authToken.isNotBlank()) {
                                "http://$serverHost${track.downloadUrl}${queryJoin}token=$authToken"
                            } else {
                                "http://$serverHost${track.downloadUrl}"
                            }
                            val fileReqBuilder = Request.Builder().url(downloadUrl)
                            if (authToken.isNotBlank()) {
                                fileReqBuilder.header("X-AndSync-Token", authToken)
                            }
                            val fileReq = fileReqBuilder.build()

                            httpClient.newCall(fileReq).execute().use { res ->
                                if (res.code == 401) {
                                    throw Exception("🔒 認証エラー: PCとのペアリングが一致しません。QRコードを再スキャンしてください。")
                                }
                                if (res.code == 403) {
                                    throw Exception("🚫 宅外ネットワークからのアクセスは遮断されています。")
                                }
                                if (!res.isSuccessful) {
                                    var serverErrMsg = ""
                                    try {
                                        val errBody = res.body?.string() ?: ""
                                        if (errBody.contains("\"error\":")) {
                                            val jsonErr = gson.fromJson(errBody, Map::class.java)
                                            serverErrMsg = jsonErr["error"]?.toString() ?: ""
                                        }
                                    } catch (_: Exception) {}
                                    val finalMsg = if (serverErrMsg.isNotBlank()) serverErrMsg else "HTTP ${res.code}"
                                    throw Exception(finalMsg)
                                }

                                var isWriteSuccess = false
                                try {
                                    FileOutputStream(destFile).use { output ->
                                        res.body?.byteStream()?.use { input ->
                                            val buffer = ByteArray(8192)
                                            var readBytes: Int
                                            while (input.read(buffer).also { readBytes = it } != -1) {
                                                if (SyncStateManager.isCancelRequested || !isActive) {
                                                    throw CancellationException("ユーザーによる同期中止")
                                                }
                                                output.write(buffer, 0, readBytes)
                                            }
                                            output.flush()
                                        }
                                    }
                                    isWriteSuccess = true
                                    downloadedCount++
                                    downloadedTracks.add(track)
                                    isDownloaded = true
                                } finally {
                                    if (!isWriteSuccess && destFile.exists()) {
                                        destFile.delete()
                                    }
                                }
                            }
                            if (isDownloaded) break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            trackErrorMsg = e.message ?: "エラー"
                            if (attempt == maxRetries) {
                                failedTracks.add(Pair(track, trackErrorMsg))
                            }
                        }
                    }
                }
            }

            // M3U8 生成
            SyncStateManager.updateCustomProgress(0, 0, "プレイリスト更新中...")
            updateNotification("プレイリスト更新中...", 0, 0, ongoing = true)

            val totalPlaylists = selectedPlaylists.size
            selectedPlaylists.forEachIndexed { pIndex, pl ->
                ensureActive()
                if (SyncStateManager.isCancelRequested) throw CancellationException()

                val tracks = playlistTrackMap[pl] ?: emptyList()
                val safePlaylistBase = pl.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').trim().ifEmpty { "playlist" }
                val safePlaylistName = "$safePlaylistBase.m3u8"
                val m3u8File = File(targetDir, safePlaylistName)
                FileOutputStream(m3u8File).use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        writer.write("#EXTM3U\n")
                        tracks.forEach { t ->
                            val relPath = trackRelativePathMap[t.trackId]
                            if (relPath != null) {
                                writer.write("$relPath\n")
                            }
                        }
                    }
                }
            }

            val resultMsg = if (failedTracks.isEmpty()) {
                "同期完了！ ${selectedPlaylists.size}件のプレイリスト\n(転送: ${downloadedCount}曲, スキップ: ${skippedCount}曲)"
            } else {
                "同期完了 (一部失敗あり): ${selectedPlaylists.size}件のプレイリスト\n(転送: ${downloadedCount}曲, スキップ: ${skippedCount}曲, ⚠️ 失敗: ${failedTracks.size}曲)"
            }
            val resultItems = mutableListOf<SyncResultItem>()
            downloadedTracks.forEach { track ->
                val plNames = trackPlaylistsMap[track.trackId]?.joinToString(", ") ?: "なし"
                resultItems.add(
                    SyncResultItem(
                        trackId = track.trackId,
                        trackName = track.name,
                        artist = track.artist,
                        album = track.album,
                        playlistName = plNames,
                        isSuccess = true
                    )
                )
            }
            failedTracks.forEach { (track, errMsg) ->
                val plNames = trackPlaylistsMap[track.trackId]?.joinToString(", ") ?: "なし"
                resultItems.add(
                    SyncResultItem(
                        trackId = track.trackId,
                        trackName = track.name,
                        artist = track.artist,
                        album = track.album,
                        playlistName = plNames,
                        isSuccess = false,
                        errorMessage = errMsg
                    )
                )
            }
            SyncStateManager.completeSync(resultMsg, resultItems)
            showFinishedNotification(resultMsg, isError = failedTracks.isNotEmpty())
        }
    }
}
