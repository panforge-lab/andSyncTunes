package com.panforge.andsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.panforge.andsync.ui.theme.AndSyncAndroidTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

// データ型定義
data class PingResponse(
    val status: String,
    val version: String,
    val trackCount: Int,
    val playlistCount: Int,
    val machineName: String
)

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val port: Int,
    val token: String? = null
)

data class PlaylistItem(
    val playlistId: Int,
    val name: String,
    val trackCount: Int,
    val totalBytes: Long = 0L,
    val persistentId: String? = null,
    val parentPersistentId: String? = null,
    val isFolder: Boolean = false,
    val distinguishedKind: Int? = null
)

data class TrackDetail(
    val trackId: Int,
    val name: String,
    val artist: String?,
    val album: String?,
    val fileName: String?,
    val fileSize: Long?,
    val lastModified: Long?,
    val downloadUrl: String
)

data class PlaylistDetailResponse(
    val playlistId: Int,
    val name: String,
    val tracks: List<TrackDetail>
)

// バイト数フォーマット関数 (例: 1.2 GB, 350 MB)
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return if (digitGroups >= 3) {
        String.format(java.util.Locale.US, "%.2f %s", value, units[digitGroups])
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
    }
}

// 端末ストレージの空き容量取得 (SAF URI または 外部ストレージ)
fun getAvailableStorageBytes(context: Context, targetUri: Uri?): Long {
    try {
        if (targetUri != null) {
            val doc = DocumentFile.fromTreeUri(context, targetUri)
            if (doc != null && doc.canWrite()) {
                // targetUri の DocumentFile またはシステムから StatFs 取得を試みる
                // SAF では直接 File パスが取れない場合があるため、context.contentResolver を経由するか、
                // 最も確実な fallback として端末の外部ストレージディレクトリの空き容量を使用する
                val extDir = context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory()
                val stat = android.os.StatFs(extDir.absolutePath)
                return stat.availableBytes
            }
        }
        val extDir = context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory()
        val stat = android.os.StatFs(extDir.absolutePath)
        return stat.availableBytes
    } catch (e: Exception) {
        return try {
            val extDir = Environment.getExternalStorageDirectory()
            val stat = android.os.StatFs(extDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }
}

// 端末内の既存プレイリスト情報
data class LocalPlaylistInfo(
    val fileName: String, // 例: "アニメソング.m3u8"
    val displayName: String, // 例: "アニメソング"
    val m3u8File: DocumentFile,
    val relativeTrackPaths: List<String>, // 例: ["たま/さんだる/01.mp3", ...]
    val estimatedBytes: Long = 0L
)

// 端末保存先フォルダから既存 M3U8 プレイリストと曲参照を解析走査
suspend fun scanLocalPlaylists(context: Context, targetUri: Uri?): List<LocalPlaylistInfo> = withContext(Dispatchers.IO) {
    if (targetUri == null) return@withContext emptyList()
    val targetDoc = DocumentFile.fromTreeUri(context, targetUri) ?: return@withContext emptyList()
    if (!targetDoc.canRead()) return@withContext emptyList()

    val result = mutableListOf<LocalPlaylistInfo>()
    val files = targetDoc.listFiles()

    for (file in files) {
        val name = file.name ?: continue
        if (name.endsWith(".m3u8", ignoreCase = true)) {
            val trackPaths = mutableListOf<String>()
            try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (rawLine in lines) {
                            val line = rawLine.trim()
                            if (line.isNotEmpty() && !line.startsWith("#")) {
                                trackPaths.add(line.replace('\\', '/'))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            val displayName = name.removeSuffix(".m3u8").removeSuffix(".M3U8")
            result.add(
                LocalPlaylistInfo(
                    fileName = name,
                    displayName = displayName,
                    m3u8File = file,
                    relativeTrackPaths = trackPaths
                )
            )
        }
    }

    result.sortedBy { it.displayName }
}

// 孤立した音楽ファイルのデータクラス
data class OrphanTrackInfo(
    val relativePath: String, // 例: "米津玄師/STRAY SHEEP/01.mp3"
    val fileName: String,     // 例: "01.mp3"
    val artist: String,       // 例: "米津玄師"
    val album: String,        // 例: "STRAY SHEEP"
    val fileSize: Long,       // 例: 8492019
    val documentFile: DocumentFile,
    val albumDoc: DocumentFile? = null,
    val artistDoc: DocumentFile? = null
)

// 端末保存先フォルダから「どのプレイリストにも含まれていない孤立音楽ファイル」を走査検出
suspend fun scanOrphanTracks(
    context: Context,
    targetUri: Uri?,
    onProgress: (suspend (currentFolder: Int, totalFolders: Int, scannedTracks: Int, foundOrphans: Int) -> Unit)? = null
): List<OrphanTrackInfo> = withContext(Dispatchers.IO) {
    if (targetUri == null) return@withContext emptyList()
    val targetDoc = DocumentFile.fromTreeUri(context, targetUri) ?: return@withContext emptyList()
    if (!targetDoc.canRead()) return@withContext emptyList()

    // 1. まず全 M3U8 プレイリストに登録されている楽曲の相対パスを正規化して集合化
    val playlists = scanLocalPlaylists(context, targetUri)
    val referencedTrackPaths = playlists
        .flatMap { it.relativeTrackPaths }
        .map { it.replace('\\', '/').trim().lowercase(java.util.Locale.ROOT) }
        .toSet()

    // 音楽ファイルの拡張子
    val audioExtensions = setOf("mp3", "m4a", "flac", "aac", "wav", "ogg", "wma", "alac", "opus", "aiff")

    val orphanList = mutableListOf<OrphanTrackInfo>()

    // 2. targetDoc 配下のアーティストフォルダ一覧を取得
    val rootFiles = targetDoc.listFiles()
    val artistDirs = rootFiles.filter { it.isDirectory }
    val totalFolders = artistDirs.size
    var currentFolderIdx = 0
    var scannedTracks = 0

    onProgress?.invoke(0, totalFolders, 0, 0)

    for (artistDoc in artistDirs) {
        currentFolderIdx++
        val artistName = artistDoc.name ?: continue
        val albumFiles = artistDoc.listFiles()
        for (albumDoc in albumFiles) {
            val albumName = albumDoc.name ?: continue
            if (albumDoc.isDirectory) {
                val trackFiles = albumDoc.listFiles()
                for (trackDoc in trackFiles) {
                    if (trackDoc.isFile) {
                        val trackName = trackDoc.name ?: continue
                        val ext = trackName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                        if (audioExtensions.contains(ext)) {
                            scannedTracks++
                            val relPath = "$artistName/$albumName/$trackName"
                            val normPath = relPath.replace('\\', '/').trim().lowercase(java.util.Locale.ROOT)
                            if (!referencedTrackPaths.contains(normPath)) {
                                orphanList.add(
                                    OrphanTrackInfo(
                                        relativePath = relPath,
                                        fileName = trackName,
                                        artist = artistName,
                                        album = albumName,
                                        fileSize = trackDoc.length(),
                                        documentFile = trackDoc,
                                        albumDoc = albumDoc,
                                        artistDoc = artistDoc
                                    )
                                )
                            }
                        }
                    }
                }
            } else if (albumDoc.isFile) {
                val trackName = albumName
                val ext = trackName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (audioExtensions.contains(ext)) {
                    scannedTracks++
                    val relPath = "$artistName/$trackName"
                    val normPath = relPath.replace('\\', '/').trim().lowercase(java.util.Locale.ROOT)
                    if (!referencedTrackPaths.contains(normPath)) {
                        orphanList.add(
                            OrphanTrackInfo(
                                relativePath = relPath,
                                fileName = trackName,
                                artist = artistName,
                                album = "",
                                fileSize = albumDoc.length(),
                                documentFile = albumDoc,
                                albumDoc = null,
                                artistDoc = artistDoc
                            )
                        )
                    }
                }
            }
        }
        onProgress?.invoke(currentFolderIdx, totalFolders, scannedTracks, orphanList.size)
    }

    // ルート直下に置かれた音楽ファイルも念のためチェック
    for (rootFile in rootFiles) {
        if (rootFile.isFile) {
            val trackName = rootFile.name ?: continue
            val ext = trackName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (audioExtensions.contains(ext)) {
                scannedTracks++
                val normPath = trackName.replace('\\', '/').trim().lowercase(java.util.Locale.ROOT)
                if (!referencedTrackPaths.contains(normPath)) {
                    orphanList.add(
                        OrphanTrackInfo(
                            relativePath = trackName,
                            fileName = trackName,
                            artist = "",
                            album = "",
                            fileSize = rootFile.length(),
                            documentFile = rootFile
                        )
                    )
                }
            }
        }
    }
    onProgress?.invoke(totalFolders, totalFolders, scannedTracks, orphanList.size)

    orphanList.sortedWith(compareBy({ it.artist }, { it.album }, { it.fileName }))
}

data class TreePlaylistItem(
    val item: PlaylistItem,
    val level: Int,
    val hasChildren: Boolean,
    val descendantLeafItems: List<PlaylistItem>
)

// プレイリストのツリー構造化 ＆ 展開状態に応じたフラット化
fun flattenPlaylistTree(
    items: List<PlaylistItem>,
    expandedIds: Set<String>
): Pair<List<TreePlaylistItem>, Map<String, List<PlaylistItem>>> {
    // persistentId -> 子アイテムリスト のマップ
    val childrenMap = mutableMapOf<String, MutableList<PlaylistItem>>()
    val rootItems = mutableListOf<PlaylistItem>()
    val itemById = items.filter { !it.persistentId.isNullOrBlank() }.associateBy { it.persistentId!! }

    for (item in items) {
        val parentId = item.parentPersistentId
        if (parentId.isNullOrBlank() || !itemById.containsKey(parentId)) {
            rootItems.add(item)
        } else {
            childrenMap.getOrPut(parentId) { mutableListOf() }.add(item)
        }
    }

    // 各フォルダが持つ「配下の全末端通常プレイリスト」を再帰的に計算するキャッシュ
    val descendantLeavesMap = mutableMapOf<String, List<PlaylistItem>>()
    fun getLeaves(item: PlaylistItem): List<PlaylistItem> {
        val pid = item.persistentId ?: return if (!item.isFolder) listOf(item) else emptyList()
        if (descendantLeavesMap.containsKey(pid)) return descendantLeavesMap[pid]!!
        val leaves = if (!item.isFolder) {
            listOf(item)
        } else {
            val children = childrenMap[pid] ?: emptyList()
            children.flatMap { getLeaves(it) }
        }
        descendantLeavesMap[pid] = leaves
        return leaves
    }

    // 全アイテムのleavesを事前計算
    for (item in items) {
        getLeaves(item)
    }

    // 展開状態に従って可視行を再帰的に構築
    val visibleList = mutableListOf<TreePlaylistItem>()
    fun traverse(itemList: List<PlaylistItem>, level: Int) {
        for (item in itemList) {
            val pid = item.persistentId ?: ""
            val children = if (pid.isNotBlank()) childrenMap[pid] ?: emptyList() else emptyList()
            val hasChildren = item.isFolder || children.isNotEmpty()
            val leaves = if (pid.isNotBlank()) descendantLeavesMap[pid] ?: emptyList() else if (!item.isFolder) listOf(item) else emptyList()

            visibleList.add(
                TreePlaylistItem(
                    item = item,
                    level = level,
                    hasChildren = hasChildren,
                    descendantLeafItems = leaves
                )
            )

            if (hasChildren && expandedIds.contains(pid)) {
                traverse(children, level + 1)
            }
        }
    }

    traverse(rootItems, 0)
    return Pair(visibleList, descendantLeavesMap)
}

class NsdDiscoveryManager(
    private val context: Context,
    private val onServerFound: (DiscoveredServer) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val serviceType = "_andsync._tcp."
    private var isDiscovering = false
    private var isResolving = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            isDiscovering = true
            onStatusChanged("探索中...")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("_andsync._tcp")) {
                resolveService(service)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            onStatusChanged("サービスロスト: ${service.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            isDiscovering = false
            onStatusChanged("停止中")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscovering = false
            onStatusChanged("探索開始失敗 (コード $errorCode)")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscovering = false
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (isResolving) return
        isResolving = true
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isResolving = false
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    isResolving = false
                    // 1. TXTレコードに明示された物理LAN IPを最優先
                    var resolvedIp: String? = null
                    try {
                        val txtIpBytes = serviceInfo.attributes?.get("ip")
                        if (txtIpBytes != null) {
                            val txtIp = String(txtIpBytes, Charsets.UTF_8).trim()
                            if (txtIp.isNotBlank() && txtIp != "localhost" && txtIp != "127.0.0.1") {
                                resolvedIp = txtIp
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }

                    // 2. TXTレコードが取れなければ hostAddress から取得
                    if (resolvedIp == null) {
                        val host = serviceInfo.host
                        val rawIp = host?.hostAddress
                        if (rawIp != null) {
                            resolvedIp = if (rawIp.contains("%")) rawIp.substringBefore("%") else rawIp
                        }
                    }

                    var resolvedToken: String? = null
                    try {
                        val tokenBytes = serviceInfo.attributes?.get("token")
                        if (tokenBytes != null) {
                            val t = String(tokenBytes, Charsets.UTF_8).trim()
                            if (t.isNotBlank()) resolvedToken = t
                        }
                    } catch (e: Exception) {
                        // ignore
                    }

                    val finalIp = resolvedIp ?: return
                    val port = serviceInfo.port
                    val name = serviceInfo.serviceName ?: "andSync Server"
                    onServerFound(DiscoveredServer(name, finalIp, port, resolvedToken))
                }
            })
        } catch (e: Exception) {
            isResolving = false
        }
    }

    fun start() {
        if (isDiscovering || nsdManager == null) return
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            onStatusChanged("探索開始エラー: ${e.message}")
        }
    }

    fun stop() {
        if (!isDiscovering || nsdManager == null) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            // ignore
        } finally {
            isDiscovering = false
        }
    }
}

class MainActivity : ComponentActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val prefs by lazy {
        getSharedPreferences("andsync_prefs", Context.MODE_PRIVATE)
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val isSyncing by SyncStateManager.isSyncing.collectAsState()
            LaunchedEffect(isSyncing) {
                if (isSyncing) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            AndSyncAndroidTheme {
                MainSyncApp(
                    initialTargetUri = getSavedTargetFolderUri(),
                    initialServerIp = prefs.getString("server_ip", "192.168.1.111") ?: "192.168.1.111",
                    initialServerPort = prefs.getString("server_port", "10000") ?: "10000",
                    initialServerToken = prefs.getString("server_token", "") ?: "",
                    onSaveSettings = { ip, port, token ->
                        val editor = prefs.edit().putString("server_ip", ip).putString("server_port", port)
                        if (!token.isNullOrBlank()) {
                            editor.putString("server_token", token)
                        }
                        editor.apply()
                    },
                    onSaveTargetUri = { uri -> saveTargetFolderUri(uri) },
                    onPing = { serverUrl, token -> pingServer(serverUrl, token) },
                    onFetchPlaylists = { serverUrl, token -> fetchPlaylists(serverUrl, token) }
                )
            }
        }
    }

    private fun getSavedTargetFolderUri(): Uri? {
        val uriStr = prefs.getString("target_folder_uri", null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveTargetFolderUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // エラーハンドリング
        }
        prefs.edit().putString("target_folder_uri", uri.toString()).apply()
    }

    private suspend fun pingServer(serverUrl: String, token: String = ""): Result<PingResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$serverUrl/api/ping"
            val reqBuilder = Request.Builder().url(url)
            val effectiveToken = if (token.isNotBlank()) token else (prefs.getString("server_token", "") ?: "")
            if (effectiveToken.isNotBlank()) {
                reqBuilder.header("X-AndSync-Token", effectiveToken)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { res ->
                if (res.code == 401) {
                    return@withContext Result.failure(Exception("🔒 PCとのペアリングが一致しません。PC画面のQRコードを再スキャンしてください。"))
                }
                if (res.code == 403) {
                    return@withContext Result.failure(Exception("🚫 宅外ネットワークからのアクセスは遮断されています。Wi-Fiまたはテザリングに接続してください。"))
                }
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val body = res.body?.string() ?: ""
                val data = gson.fromJson(body, PingResponse::class.java)
                Result.success(data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchPlaylists(serverUrl: String, token: String = ""): Result<List<PlaylistItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$serverUrl/api/playlists"
            val reqBuilder = Request.Builder().url(url)
            val effectiveToken = if (token.isNotBlank()) token else (prefs.getString("server_token", "") ?: "")
            if (effectiveToken.isNotBlank()) {
                reqBuilder.header("X-AndSync-Token", effectiveToken)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { res ->
                if (res.code == 401) {
                    return@withContext Result.failure(Exception("🔒 PCとのペアリングが一致しません。PC画面のQRコードを再スキャンしてください。"))
                }
                if (res.code == 403) {
                    return@withContext Result.failure(Exception("🚫 宅外ネットワークからのアクセスは遮断されています。Wi-Fiまたはテザリングに接続してください。"))
                }
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val body = res.body?.string() ?: ""
                val listType = object : TypeToken<List<PlaylistItem>>() {}.type
                val data: List<PlaylistItem> = gson.fromJson(body, listType)
                Result.success(data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Composable
fun MainSyncApp(
    initialTargetUri: Uri?,
    initialServerIp: String,
    initialServerPort: String,
    initialServerToken: String,
    onSaveSettings: (String, String, String?) -> Unit,
    onSaveTargetUri: (Uri) -> Unit,
    onPing: suspend (String, String) -> Result<PingResponse>,
    onFetchPlaylists: suspend (String, String) -> Result<List<PlaylistItem>>
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0: 同期, 1: 設定

    var serverIp by remember { mutableStateOf(initialServerIp) }
    var serverPort by remember { mutableStateOf(initialServerPort) }
    var serverToken by remember { mutableStateOf(initialServerToken) }
    val serverHost = "$serverIp:$serverPort"

    var targetFolderUri by remember { mutableStateOf(initialTargetUri) }

    var statusText by remember { mutableStateOf("未接続") }
    var isConnected by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var selectedPlaylistIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Foreground Service & SyncStateManager による同期状態監視
    val isSyncing by SyncStateManager.isSyncing.collectAsState()
    val syncProgressFraction by SyncStateManager.progressFraction.collectAsState()
    val syncProgressText by SyncStateManager.progressText.collectAsState()
    val isSyncError by SyncStateManager.isError.collectAsState()
    val failedTracks by SyncStateManager.failedTracks.collectAsState()
    val syncResultItems by SyncStateManager.syncResultItems.collectAsState()

    // mDNS 自動検出状態
    var discoveryStatus by remember { mutableStateOf("🔍 PCを自動探索中...") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 接続処理を共通化
    fun connectAndFetch(targetHost: String = "$serverIp:$serverPort", token: String = serverToken) {
        scope.launch {
            statusText = "接続テスト中..."
            val pingRes = onPing(targetHost, token)
            pingRes.onSuccess { ping ->
                statusText = "接続成功: ${ping.machineName} (${ping.trackCount}曲 / ${ping.playlistCount}プレイリスト)"
                isConnected = true
                val plRes = onFetchPlaylists(targetHost, token)
                plRes.onSuccess { list ->
                    // システム特殊プレイリストをクライアント側でも安全に除外
                    val systemNames = setOf("ミュージック", "ダウンロード済み", "ムービー", "テレビ番組", "ポッドキャスト", "オーディオブック", "Genius")
                    playlists = list.filter { pl ->
                        pl.distinguishedKind == null && !systemNames.contains(pl.name.trim())
                    }
                }.onFailure {
                    statusText = "プレイリスト取得失敗: ${it.message}"
                }
            }.onFailure {
                statusText = "接続失敗: ${it.message}"
                isConnected = false
            }
        }
    }

    // QRコードテキストから IP、ポート、トークン を抽出
    fun parseServerAddress(input: String): Triple<String, String, String?>? {
        try {
            val fullUrl = if (input.contains("://")) input else "http://$input"
            val uri = Uri.parse(fullUrl)
            val host = uri.host
            if (!host.isNullOrBlank()) {
                val port = if (uri.port != -1) uri.port.toString() else "5000"
                val token = uri.getQueryParameter("token")
                return Triple(host, port, token)
            }
        } catch (e: Exception) {
            // fallback
        }
        val clean = input.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("andsync://")
            .substringBefore("/")
        val parts = clean.split(":")
        if (parts.isNotEmpty()) {
            val ip = parts[0].trim()
            val port = if (parts.size > 1) parts[1].trim() else "5000"
            if (ip.isNotBlank()) {
                return Triple(ip, port, null)
            }
        }
        return null
    }

    // Google Code Scanner を使ったQRコードスキャン
    fun startQrScan() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue ?: return@addOnSuccessListener
                    val parsed = parseServerAddress(raw)
                    if (parsed != null) {
                        val (ip, port, token) = parsed
                        serverIp = ip
                        serverPort = port
                        if (!token.isNullOrBlank()) {
                            serverToken = token
                        }
                        onSaveSettings(ip, port, token)
                        Toast.makeText(context, "📷 QR接続先を取得: $ip:$port", Toast.LENGTH_SHORT).show()
                        connectAndFetch("$ip:$port", token ?: serverToken)
                    } else {
                        Toast.makeText(context, "無効なQRコード形式: $raw", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "スキャンキャンセルまたは失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(context, "スキャナー起動エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val nsdHelper = remember {
        NsdDiscoveryManager(
            context = context,
            onServerFound = { server ->
                discoveryStatus = "✅ 発見: ${server.name} (${server.ip}:${server.port})"
                serverIp = server.ip
                serverPort = server.port.toString()
                if (!server.token.isNullOrBlank()) {
                    serverToken = server.token
                }
                onSaveSettings(server.ip, server.port.toString(), server.token)
                scope.launch {
                    Toast.makeText(context, "🎉 PCエージェントを自動検出しました！\n${server.name}", Toast.LENGTH_SHORT).show()
                    connectAndFetch("${server.ip}:${server.port}", server.token ?: serverToken)
                }
            },
            onStatusChanged = { status ->
                discoveryStatus = status
            }
        )
    }

    DisposableEffect(Unit) {
        nsdHelper.start()
        onDispose {
            nsdHelper.stop()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = { Text("🎵", style = MaterialTheme.typography.titleMedium) },
                    label = { Text("同期") }
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = { Text("📋", style = MaterialTheme.typography.titleMedium) },
                    label = { Text("リスト整理") }
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = { Text("🧹", style = MaterialTheme.typography.titleMedium) },
                    label = { Text("孤立掃除") }
                )
                NavigationBarItem(
                    selected = selectedTabIndex == 3,
                    onClick = { selectedTabIndex = 3 },
                    icon = { Text("⚙️", style = MaterialTheme.typography.titleMedium) },
                    label = { Text("設定") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTabIndex) {
            0 -> {
                // === タブ1: 同期画面（広々プレイリスト） ===
                SyncTabScreen(
                    modifier = Modifier.padding(innerPadding),
                    isConnected = isConnected,
                    statusText = statusText,
                    playlists = playlists,
                    selectedPlaylistIds = selectedPlaylistIds,
                    targetFolderUri = targetFolderUri,
                    onTogglePlaylists = { targetIds, makeSelected ->
                        if (!isSyncing) {
                            selectedPlaylistIds = if (makeSelected) {
                                selectedPlaylistIds + targetIds
                            } else {
                                selectedPlaylistIds - targetIds
                            }
                        }
                    },
                    onSelectAll = {
                        selectedPlaylistIds = playlists.filter { !it.isFolder }.map { it.playlistId }.toSet()
                    },
                    onDeselectAll = { selectedPlaylistIds = emptySet() },
                    onRefresh = { connectAndFetch() },
                    onGoToSettings = { selectedTabIndex = 3 },
                    isSyncing = isSyncing,
                    syncProgressFraction = syncProgressFraction,
                    syncProgressText = syncProgressText,
                    isSyncError = isSyncError,
                    failedTracks = failedTracks,
                    syncResultItems = syncResultItems,
                    onStartSync = {
                        if (!SyncStateManager.isWifiConnected(context)) {
                            Toast.makeText(context, "⚠️ Wi-Fiに接続されていません。PCと同じWi-Fiまたはテザリングに接続してください", Toast.LENGTH_LONG).show()
                            return@SyncTabScreen
                        }
                        val currentHost = "$serverIp:$serverPort"
                        // 実際に同期する対象はフォルダ以外の通常プレイリストのみ
                        val selectedLists = playlists.filter { !it.isFolder && selectedPlaylistIds.contains(it.playlistId) }
                        SyncService.startSync(context, currentHost, targetFolderUri, selectedLists, serverToken)
                    },
                    onCancelSync = {
                        SyncService.cancelSync(context)
                    }
                )
            }
            1 -> {
                // === タブ2: リスト整理画面 (M3U8スマート削除) ===
                ManageTabScreen(
                    modifier = Modifier.padding(innerPadding),
                    targetFolderUri = targetFolderUri,
                    onGoToSettings = { selectedTabIndex = 3 }
                )
            }
            2 -> {
                // === タブ3: 孤立ファイル掃除画面 (孤立曲クリーンアップ) ===
                CleanerTabScreen(
                    modifier = Modifier.padding(innerPadding),
                    targetFolderUri = targetFolderUri,
                    onGoToSettings = { selectedTabIndex = 3 }
                )
            }
            else -> {
                // === タブ4: 設定画面 ===
                SettingsTabScreen(
                    modifier = Modifier.padding(innerPadding),
                    discoveryStatus = discoveryStatus,
                    onRestartDiscovery = {
                        nsdHelper.stop()
                        nsdHelper.start()
                    },
                    serverIp = serverIp,
                    onServerIpChange = {
                        serverIp = it
                        onSaveSettings(serverIp, serverPort, serverToken)
                    },
                    serverPort = serverPort,
                    onServerPortChange = {
                        serverPort = it
                        onSaveSettings(serverIp, serverPort, serverToken)
                    },
                    serverToken = serverToken,
                    targetFolderUri = targetFolderUri,
                    onSaveTargetUri = { uri ->
                        targetFolderUri = uri
                        onSaveTargetUri(uri)
                    },
                    statusText = statusText,
                    isConnected = isConnected,
                    onTestConnection = { connectAndFetch() },
                    onStartQrScan = { startQrScan() }
                )
            }
        }
    }
}

// 🎵 同期タブ画面
@Composable
fun SyncTabScreen(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    statusText: String,
    playlists: List<PlaylistItem>,
    selectedPlaylistIds: Set<Int>,
    targetFolderUri: Uri?,
    onTogglePlaylists: (List<Int>, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onRefresh: () -> Unit,
    onGoToSettings: () -> Unit,
    isSyncing: Boolean,
    syncProgressFraction: Float,
    syncProgressText: String,
    isSyncError: Boolean,
    failedTracks: List<SyncErrorInfo> = emptyList(),
    syncResultItems: List<SyncResultItem> = emptyList(),
    onStartSync: () -> Unit,
    onCancelSync: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // ツリーの展開状態 (開いているフォルダの persistentId セット)
    var expandedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 階層ツリー構造のフラット化リストと各フォルダの末端プレイリストマップ
    val (treeVisibleItems, descendantLeavesMap) = remember(playlists, expandedFolderIds) {
        flattenPlaylistTree(playlists, expandedFolderIds)
    }

    // 実際に同期される通常プレイリストのみ（フォルダ除く）
    val nonFolderPlaylists = remember(playlists) {
        playlists.filter { !it.isFolder }
    }

    // 選択された通常プレイリストの集計 (曲数 & 合計バイト数)
    val selectedPlaylists = remember(nonFolderPlaylists, selectedPlaylistIds) {
        nonFolderPlaylists.filter { selectedPlaylistIds.contains(it.playlistId) }
    }
    val totalSelectedTracks = remember(selectedPlaylists) {
        selectedPlaylists.sumOf { it.trackCount }
    }
    val totalSelectedBytes = remember(selectedPlaylists) {
        selectedPlaylists.sumOf { it.totalBytes }
    }

    // 端末の空き容量 (ストレージ: SyncStateManager から半リアルタイム更新を受信)
    val dynamicStorageBytes by SyncStateManager.availableStorageBytes.collectAsState()
    val availableBytes = dynamicStorageBytes ?: remember(targetFolderUri, isSyncing) {
        getAvailableStorageBytes(context, targetFolderUri)
    }

    // 容量不足判定
    val isStorageOverflow = totalSelectedBytes > 0 && availableBytes > 0 && totalSelectedBytes > availableBytes

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // トップステータスバー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🎵 プレイリスト同期", style = MaterialTheme.typography.titleLarge)
                val isWifi = SyncStateManager.isWifiConnected(context)
                Text(
                    text = when {
                        !isWifi -> "⚠️ Wi-Fi未接続 (モバイル通信またはオフライン)"
                        isConnected -> "● PC接続中 (Wi-Fi)"
                        else -> "○ 未接続 (設定タブで接続)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !isWifi -> MaterialTheme.colorScheme.error
                        isConnected -> Color(0xFF2E7D32)
                        else -> Color.Gray
                    }
                )
            }
            FilledTonalButton(onClick = onRefresh, enabled = !isSyncing) {
                Text("再取得")
            }
        }

        HorizontalDivider()

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("プレイリストが読み込まれていません", color = Color.Gray)
                    Button(onClick = onRefresh) {
                        Text("PCエージェントに接続して取得")
                    }
                    TextButton(onClick = onGoToSettings) {
                        Text("IP・ポート設定を確認する ➔")
                    }
                }
            }
        } else {
            // 一括操作バー
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val folderCount = playlists.count { it.isFolder }
                val leafCount = nonFolderPlaylists.size
                Text(
                    text = "$leafCount 件のプレイリスト" + if (folderCount > 0) " ($folderCount フォルダ)" else "",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onSelectAll, enabled = !isSyncing) {
                        Text("全選択")
                    }
                    TextButton(onClick = onDeselectAll, enabled = !isSyncing) {
                        Text("全解除")
                    }
                }
            }

            // 🌳 エクスプローラー風 ツリー階層プレイリスト一覧
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(treeVisibleItems) { treeNode ->
                    val item = treeNode.item
                    val isFolder = item.isFolder
                    val pid = item.persistentId ?: ""
                    val isExpanded = pid.isNotBlank() && expandedFolderIds.contains(pid)

                    if (isFolder) {
                        // 📁 フォルダ行
                        val leaves = treeNode.descendantLeafItems
                        val allLeavesChecked = leaves.isNotEmpty() && leaves.all { selectedPlaylistIds.contains(it.playlistId) }
                        val someLeavesChecked = leaves.any { selectedPlaylistIds.contains(it.playlistId) }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (allLeavesChecked) MaterialTheme.colorScheme.primaryContainer
                                else if (someLeavesChecked) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (treeNode.level * 16).dp)
                                .clickable {
                                    if (pid.isNotBlank()) {
                                        expandedFolderIds = if (isExpanded) {
                                            expandedFolderIds - pid
                                        } else {
                                            expandedFolderIds + pid
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // フォルダ展開矢印
                                Text(
                                    text = if (isExpanded) "▼" else "▶",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 4.dp)
                                )

                                // 配下一括チェックボックス
                                Checkbox(
                                    checked = allLeavesChecked,
                                    onCheckedChange = { check ->
                                        val leafIds = leaves.map { it.playlistId }
                                        onTogglePlaylists(leafIds, check)
                                    },
                                    enabled = !isSyncing && leaves.isNotEmpty()
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "📁 " + item.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                Text(
                                    text = "${leaves.size} 項目",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    } else {
                        // 🎵 通常プレイリスト行
                        val isChecked = selectedPlaylistIds.contains(item.playlistId)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (treeNode.level * 16).dp)
                                .clickable {
                                    onTogglePlaylists(listOf(item.playlistId), !isChecked)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { check ->
                                        onTogglePlaylists(listOf(item.playlistId), check)
                                    },
                                    enabled = !isSyncing
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "♪ " + item.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                val sizeText = if (item.totalBytes > 0) " (${formatFileSize(item.totalBytes)})" else ""
                                Text(
                                    text = "${item.trackCount} 曲$sizeText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // リアルタイム容量メーター & 同期実行エリア（下部固定）
            if (selectedPlaylistIds.isNotEmpty() || isSyncing) {
                val count = selectedPlaylistIds.size

                // 📊 リアルタイム容量メーターカード
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isStorageOverflow) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val selectedSizeStr = if (totalSelectedBytes > 0) formatFileSize(totalSelectedBytes) else "計算中"
                            Text(
                                text = "📦 選択中: $count プレイリスト ($totalSelectedTracks 曲 / 約 $selectedSizeStr)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isStorageOverflow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val availableStr = if (availableBytes > 0) formatFileSize(availableBytes) else "取得中"
                            if (isStorageOverflow) {
                                val overflowStr = formatFileSize(totalSelectedBytes - availableBytes)
                                Text(
                                    text = "⚠️ 空き容量が不足しています (不足: 約 $overflowStr)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = "💾 端末の空き容量: 約 $availableStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }

                if (isSyncing) {
                    Button(
                        onClick = onCancelSync,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("🛑 同期を中止 (キャンセル)", color = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    Button(
                        onClick = onStartSync,
                        enabled = !isStorageOverflow,
                        colors = if (isStorageOverflow) ButtonDefaults.buttonColors(containerColor = Color.Gray) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (isStorageOverflow) {
                            Text("⚠️ 容量不足のため同期できません")
                        } else {
                            Text("選択した $count 件のプレイリストを同期開始")
                        }
                    }
                }
            }

            if (isSyncing || syncProgressFraction > 0f || syncProgressText.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isSyncing || syncProgressFraction > 0f) {
                        LinearProgressIndicator(
                            progress = { syncProgressFraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                    Text(
                        text = syncProgressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isSyncError -> MaterialTheme.colorScheme.error
                            syncProgressText.startsWith("同期完了") -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (failedTracks.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showErrorDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("⚠️ 失敗した ${failedTracks.size} 曲のエラー詳細を確認", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (syncResultItems.isNotEmpty()) {
                        val successCount = syncResultItems.count { it.isSuccess }
                        val failCount = syncResultItems.count { !it.isSuccess }
                        OutlinedButton(
                            onClick = { showResultDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("📊 転送結果の詳細 (${syncResultItems.size}曲: ⭕$successCount / ❌$failCount) を確認", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showErrorDialog && failedTracks.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = {
                Text("⚠️ 同期エラー詳細 (${failedTracks.size}曲)", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "以下の楽曲は、iTunes上でリンクが切れている（Location空）か、PC上にファイルが存在しないため同期できませんでした。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Divider()
                    failedTracks.forEachIndexed { idx, err ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "${idx + 1}. ${err.trackName}",
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "👤 ${err.artist ?: "不明"} / 💿 ${err.album ?: "不明"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.DarkGray
                                )
                                Text(
                                    "📁 プレイリスト: ${err.playlistName ?: "なし"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.DarkGray
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "❗ 原因: ${err.errorMessage}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showErrorDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }

    if (showResultDialog && syncResultItems.isNotEmpty()) {
        val successCount = syncResultItems.count { it.isSuccess }
        val failCount = syncResultItems.count { !it.isSuccess }
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = {
                Text("📊 楽曲転送結果一覧 (${syncResultItems.size}曲)", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "⭕ 転送成功: ${successCount}曲 ｜ ❌ 失敗: ${failCount}曲",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = if (failCount > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                    )
                    Divider()
                    syncResultItems.forEachIndexed { idx, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (item.isSuccess) Color(0xFFF1F8E9) else MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${idx + 1}. ${item.trackName}",
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        if (item.isSuccess) "⭕ 成功" else "❌ 失敗",
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    "👤 ${item.artist ?: "不明"} / 💿 ${item.album ?: "不明"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.DarkGray
                                )
                                if (!item.playlistName.isNullOrBlank()) {
                                    Text(
                                        "📁 プレイリスト: ${item.playlistName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                }
                                if (!item.isSuccess && !item.errorMessage.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "❗ 原因: ${item.errorMessage}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showResultDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }
}

// 🗑️ スマート削除タブ画面 (端末内プレイリスト整理 & 共有曲安全保護)
@Composable
fun ManageTabScreen(
    modifier: Modifier = Modifier,
    targetFolderUri: Uri?,
    onGoToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var localPlaylists by remember { mutableStateOf<List<LocalPlaylistInfo>>(emptyList()) }
    var keptFileNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isScanning by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteStatusText by remember { mutableStateOf("") }

    // 端末内 M3U8 のスキャン関数
    fun refreshLocalList() {
        if (targetFolderUri == null) {
            localPlaylists = emptyList()
            keptFileNames = emptySet()
            return
        }
        scope.launch {
            isScanning = true
            try {
                val list = scanLocalPlaylists(context, targetFolderUri)
                localPlaylists = list
                // 初期状態は全プレイリストを残す（全チェック状態）
                keptFileNames = list.map { it.fileName }.toSet()
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(targetFolderUri) {
        refreshLocalList()
    }

    // 削除対象プレイリスト（チェックが外されたもの）
    val deleteTargetPlaylists = remember(localPlaylists, keptFileNames) {
        localPlaylists.filter { !keptFileNames.contains(it.fileName) }
    }

    // 残すプレイリストが参照している曲の相対パス集合
    val keptTracksSet = remember(localPlaylists, keptFileNames) {
        localPlaylists
            .filter { keptFileNames.contains(it.fileName) }
            .flatMap { it.relativeTrackPaths }
            .toSet()
    }

    // 削除対象プレイリストが参照している全曲のうち、残すプレイリストに含まれていない「完全孤立曲」
    val orphanTracksToDelete = remember(deleteTargetPlaylists, keptTracksSet) {
        deleteTargetPlaylists
            .flatMap { it.relativeTrackPaths }
            .distinct()
            .filter { !keptTracksSet.contains(it) }
    }

    // 削除対象に含まれているが、残すプレイリストにも含まれているため「保護される曲」
    val protectedSharedTracks = remember(deleteTargetPlaylists, keptTracksSet) {
        deleteTargetPlaylists
            .flatMap { it.relativeTrackPaths }
            .distinct()
            .filter { keptTracksSet.contains(it) }
    }

    // 実際の削除実行処理
    fun executeSmartDelete() {
        if (targetFolderUri == null) return
        scope.launch(Dispatchers.IO) {
            isDeleting = true
            deleteStatusText = "削除処理を実行中..."
            try {
                val targetDoc = DocumentFile.fromTreeUri(context, targetFolderUri)
                if (targetDoc != null && targetDoc.canWrite()) {
                    var deletedTrackFiles = 0
                    var deletedM3u8Files = 0

                    val artistDirMap = targetDoc.listFiles()
                        .filter { it.isDirectory && it.name != null }
                        .associateBy { it.name!!.lowercase() }
                        .toMutableMap()
                    val albumDirMap = mutableMapOf<String, DocumentFile?>()
                    val affectedFolders = mutableSetOf<Pair<String, String>>()

                    // 1. 孤立楽曲ファイルの削除
                    orphanTracksToDelete.forEach { relPath ->
                        val parts = relPath.split('/')
                        if (parts.size >= 3) {
                            val artistName = parts[0]
                            val albumName = parts[1]
                            val fileName = parts[2]
                            affectedFolders.add(Pair(artistName, albumName))

                            val artistKey = artistName.lowercase()
                            val artistDoc = artistDirMap[artistKey]
                            val albumDoc = artistDoc?.let { aDoc ->
                                val albumKey = "$artistKey/${albumName.lowercase()}"
                                albumDirMap.getOrPut(albumKey) {
                                    aDoc.listFiles().firstOrNull { it.isDirectory && it.name?.equals(albumName, ignoreCase = true) == true }
                                }
                            }
                            val fileDoc = albumDoc?.findFile(fileName)
                            if (fileDoc != null && fileDoc.delete()) {
                                deletedTrackFiles++
                                // 10曲削除ごとにストレージ空き容量を半リアルタイム取得・更新
                                if (deletedTrackFiles % 10 == 0) {
                                    try {
                                        val currentFree = getAvailableStorageBytes(context, targetFolderUri)
                                        if (currentFree > 0L) {
                                            SyncStateManager.updateStorage(currentFree)
                                        }
                                    } catch (_: Exception) {}
                                    withContext(Dispatchers.Main) {
                                        deleteStatusText = "削除中: $deletedTrackFiles / ${orphanTracksToDelete.size} 曲"
                                    }
                                }
                            }
                        }
                    }

                    // 2. 空になったアルバムフォルダおよびアーティストフォルダのクリーンアップ
                    affectedFolders.forEach { (artistName, albumName) ->
                        val artistKey = artistName.lowercase()
                        val artistDoc = artistDirMap[artistKey]
                        if (artistDoc != null && artistDoc.isDirectory) {
                            val albumKey = "$artistKey/${albumName.lowercase()}"
                            val albumDoc = albumDirMap[albumKey] ?: artistDoc.findFile(albumName)
                            if (albumDoc != null && albumDoc.isDirectory) {
                                // アルバム内のファイルを再確認し、空なら削除
                                if (albumDoc.listFiles().isEmpty()) {
                                    albumDoc.delete()
                                }
                            }
                            // アーティストフォルダ内のアルバムを再確認し、空なら削除
                            if (artistDoc.listFiles().isEmpty()) {
                                artistDoc.delete()
                            }
                        }
                    }

                    // 3. 対象 M3U8 ファイルの削除
                    deleteTargetPlaylists.forEach { plInfo ->
                        if (plInfo.m3u8File.delete()) {
                            deletedM3u8Files++
                        }
                    }

                    // 削除完了時に最新の空き容量を確定反映
                    try {
                        val finalFree = getAvailableStorageBytes(context, targetFolderUri)
                        if (finalFree > 0L) {
                            SyncStateManager.updateStorage(finalFree)
                        }
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "🗑️ 整理完了！ 削除: ${deletedM3u8Files}プレイリスト (${deletedTrackFiles}曲)",
                            Toast.LENGTH_LONG
                        ).show()
                        deleteStatusText = "整理完了！ $deletedM3u8Files 件のプレイリストを安全に削除しました"
                        refreshLocalList()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "削除エラー: ${e.message}", Toast.LENGTH_LONG).show()
                    deleteStatusText = "エラー: ${e.message}"
                }
            } finally {
                isDeleting = false
                showDeleteConfirmDialog = false
            }
        }
    }

    // 削除確認ダイアログ
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirmDialog = false },
            title = { Text("🗑️ プレイリストの削除確認") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "チェックを外した ${deleteTargetPlaylists.size} 件のプレイリストを端末から削除します。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider()
                    Text(
                        text = "• 削除されるプレイリスト: ${deleteTargetPlaylists.size} 件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "• 完全に削除される孤立曲: ${orphanTracksToDelete.size} 曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "• 安全に保護される共有曲: ${protectedSharedTracks.size} 曲\n  （※端末に残す他のプレイリストに含まれているため削除されません）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                    if (isDeleting) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(deleteStatusText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { executeSmartDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isDeleting
                ) {
                    Text("削除を実行", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("キャンセル")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // トップヘッダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🗑️ 端末内プレイリスト整理", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "チェックを外したプレイリストが削除対象になります",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            FilledTonalButton(onClick = { refreshLocalList() }, enabled = !isScanning && !isDeleting) {
                Text("更新")
            }
        }

        HorizontalDivider()

        if (targetFolderUri == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("同期先フォルダが設定されていません", color = Color.Gray)
                    Button(onClick = onGoToSettings) {
                        Text("設定タブで保存先フォルダを選択 ➔")
                    }
                }
            }
        } else if (isScanning) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("端末内のプレイリストを走査中...", color = Color.Gray)
                }
            }
        } else if (localPlaylists.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("端末内に同期済みのプレイリストはありません", color = Color.Gray)
                    Text("「同期」タブから音楽を転送してください 🎵", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        } else {
            // 一括操作バー
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("端末内: ${localPlaylists.size} 件", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = { keptFileNames = localPlaylists.map { it.fileName }.toSet() },
                        enabled = !isDeleting
                    ) {
                        Text("全保持")
                    }
                    TextButton(
                        onClick = { keptFileNames = emptySet() },
                        enabled = !isDeleting
                    ) {
                        Text("全選択解除")
                    }
                }
            }

            // 端末内プレイリスト一覧
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(localPlaylists) { plInfo ->
                    val isKept = keptFileNames.contains(plInfo.fileName)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isKept) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isDeleting) {
                                    keptFileNames = if (isKept) {
                                        keptFileNames - plInfo.fileName
                                    } else {
                                        keptFileNames + plInfo.fileName
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isKept,
                                onCheckedChange = { checked ->
                                    keptFileNames = if (checked) {
                                        keptFileNames + plInfo.fileName
                                    } else {
                                        keptFileNames - plInfo.fileName
                                    }
                                },
                                enabled = !isDeleting
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plInfo.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isKept) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = if (isKept) "${plInfo.relativeTrackPaths.size} 曲 (端末に残す)" else "⚠️ 削除対象 (${plInfo.relativeTrackPaths.size} 曲)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isKept) Color.Gray else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // 削除サマリー ＆ 実行ボタン
            if (deleteTargetPlaylists.isNotEmpty()) {
                val deleteCount = deleteTargetPlaylists.size
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "🗑️ 削除対象: $deleteCount 件のプレイリスト",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "• 完全削除される曲: ${orphanTracksToDelete.size} 曲\n• 他のリストに残る曲（保持）: ${protectedSharedTracks.size} 曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Button(
                    onClick = { showDeleteConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isDeleting
                ) {
                    Text("選択した $deleteCount 件のプレイリストを削除...", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}

// 🧹 孤立ファイル掃除画面 (Orphan Audio Cleaner)
@Composable
fun CleanerTabScreen(
    modifier: Modifier = Modifier,
    targetFolderUri: Uri?,
    onGoToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var orphanTracks by remember { mutableStateOf<List<OrphanTrackInfo>>(emptyList()) }
    var selectedRelPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isScanning by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteStatusText by remember { mutableStateOf("") }

    // 走査進捗状態 (案A: フォルダ数・照合曲数・孤立曲数)
    var scanProgressFolder by remember { mutableIntStateOf(0) }
    var scanProgressTotalFolders by remember { mutableIntStateOf(0) }
    var scanProgressTracks by remember { mutableIntStateOf(0) }
    var scanProgressOrphans by remember { mutableIntStateOf(0) }

    // 孤立曲のスキャン関数
    fun refreshOrphanList() {
        if (targetFolderUri == null) {
            orphanTracks = emptyList()
            selectedRelPaths = emptySet()
            return
        }
        scope.launch {
            isScanning = true
            scanProgressFolder = 0
            scanProgressTotalFolders = 0
            scanProgressTracks = 0
            scanProgressOrphans = 0
            try {
                val list = scanOrphanTracks(context, targetFolderUri) { folder, total, tracks, orphans ->
                    withContext(Dispatchers.Main) {
                        scanProgressFolder = folder
                        scanProgressTotalFolders = total
                        scanProgressTracks = tracks
                        scanProgressOrphans = orphans
                    }
                }
                orphanTracks = list
                // 初期状態は全選択
                selectedRelPaths = list.map { it.relativePath }.toSet()
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(targetFolderUri) {
        refreshOrphanList()
    }

    // 選択された削除対象曲
    val selectedTracks = remember(orphanTracks, selectedRelPaths) {
        orphanTracks.filter { selectedRelPaths.contains(it.relativePath) }
    }
    val selectedTotalBytes = remember(selectedTracks) {
        selectedTracks.sumOf { it.fileSize }
    }
    val allTotalBytes = remember(orphanTracks) {
        orphanTracks.sumOf { it.fileSize }
    }

    // 削除処理の実行
    fun executeOrphanDelete() {
        if (targetFolderUri == null) return
        scope.launch(Dispatchers.IO) {
            isDeleting = true
            deleteStatusText = "孤立ファイルを削除中..."
            try {
                val targetDoc = DocumentFile.fromTreeUri(context, targetFolderUri)
                if (targetDoc != null && targetDoc.canWrite()) {
                    var deletedCount = 0
                    val affectedAlbumDocs = mutableSetOf<DocumentFile>()
                    val affectedArtistDocs = mutableSetOf<DocumentFile>()

                    val totalToDelete = selectedTracks.size
                    selectedTracks.forEachIndexed { index, track ->
                        // 1. 保持しているDocumentFileを直接一発削除（検索オーバーヘッドゼロ）
                        val isDeleted = try {
                            if (track.documentFile.exists()) {
                                track.documentFile.delete()
                            } else {
                                true
                            }
                        } catch (_: Exception) {
                            false
                        }

                        if (isDeleted) {
                            deletedCount++
                            track.albumDoc?.let { affectedAlbumDocs.add(it) }
                            track.artistDoc?.let { affectedArtistDocs.add(it) }

                            if (deletedCount % 10 == 0 || index == totalToDelete - 1) {
                                try {
                                    val currentFree = getAvailableStorageBytes(context, targetFolderUri)
                                    if (currentFree > 0L) {
                                        SyncStateManager.updateStorage(currentFree)
                                    }
                                } catch (_: Exception) {}
                                withContext(Dispatchers.Main) {
                                    deleteStatusText = "削除中: $deletedCount / $totalToDelete 曲"
                                }
                            }
                        }
                    }

                    // 2. 空になったアルバムフォルダを掃除（直接保持した参照をチェック）
                    affectedAlbumDocs.forEach { albumDoc ->
                        try {
                            if (albumDoc.exists() && albumDoc.isDirectory && albumDoc.listFiles().isEmpty()) {
                                albumDoc.delete()
                            }
                        } catch (_: Exception) {}
                    }

                    // 3. 空になったアーティストフォルダを掃除
                    affectedArtistDocs.forEach { artistDoc ->
                        try {
                            if (artistDoc.exists() && artistDoc.isDirectory && artistDoc.listFiles().isEmpty()) {
                                artistDoc.delete()
                            }
                        } catch (_: Exception) {}
                    }

                    // 空き容量確定反映
                    try {
                        val finalFree = getAvailableStorageBytes(context, targetFolderUri)
                        if (finalFree > 0L) {
                            SyncStateManager.updateStorage(finalFree)
                        }
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "🧹 お掃除完了！ ${deletedCount} 曲を削除しました (${formatFileSize(selectedTotalBytes)} 解放)",
                            Toast.LENGTH_LONG
                        ).show()
                        deleteStatusText = "お掃除完了！"
                        refreshOrphanList()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "削除エラー: ${e.message}", Toast.LENGTH_LONG).show()
                    deleteStatusText = "エラー: ${e.message}"
                }
            } finally {
                isDeleting = false
                showDeleteConfirmDialog = false
            }
        }
    }

    // 削除確認ダイアログ
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirmDialog = false },
            title = { Text("🧹 孤立ファイルの削除確認") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "どのプレイリストにも含まれていない ${selectedTracks.size} 曲を端末から完全に削除します。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider()
                    Text(
                        text = "• 削除対象: ${selectedTracks.size} 曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "• 解放されるストレージ容量: ${formatFileSize(selectedTotalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "※一度削除したファイルは復元できません。PCのiTunesから再度同期することは可能です。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    if (isDeleting) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(deleteStatusText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { executeOrphanDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isDeleting
                ) {
                    Text("削除を実行", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("キャンセル")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // トップヘッダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🧹 孤立ファイル掃除", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "どのプレイリストにも含まれていない楽曲を整理します",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            FilledTonalButton(onClick = { refreshOrphanList() }, enabled = !isScanning && !isDeleting) {
                Text("再スキャン")
            }
        }

        HorizontalDivider()

        if (targetFolderUri == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("⚠️ 音楽保存先フォルダーが未設定です", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onGoToSettings) {
                        Text("設定画面でフォルダーを選択")
                    }
                }
            }
        } else if (isScanning) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val progressFraction = if (scanProgressTotalFolders > 0) {
                            (scanProgressFolder.toFloat() / scanProgressTotalFolders.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val percent = (progressFraction * 100).toInt()

                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (scanProgressTotalFolders > 0) "端末内の楽曲とプレイリストを照合中... ($percent%)" else "プレイリストとフォルダを探索中...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "🔍 照合済み: ${String.format(java.util.Locale.JAPAN, "%,d", scanProgressTracks)} 曲 ($scanProgressFolder / $scanProgressTotalFolders フォルダー)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "🧹 孤立候補: ${scanProgressOrphans} 曲 検出",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (scanProgressOrphans > 0) MaterialTheme.colorScheme.error else Color.Gray,
                                fontWeight = if (scanProgressOrphans > 0) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }
            }
        } else if (orphanTracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✨ 孤立ファイルはありません", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "端末内のすべての音楽ファイルは、正常にいずれかのプレイリストに含まれています。ストレージは綺麗に整理されています！",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        } else {
            // スキャンサマリー
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "⚠️ 孤立ファイルを検出: ${orphanTracks.size} 曲",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = "合計: ${formatFileSize(allTotalBytes)} 解放可能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { selectedRelPaths = orphanTracks.map { it.relativePath }.toSet() },
                            enabled = !isDeleting
                        ) {
                            Text("全選択", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = { selectedRelPaths = emptySet() },
                            enabled = !isDeleting
                        ) {
                            Text("全解除", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // 孤立曲リスト
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(orphanTracks, key = { it.relativePath }) { track ->
                    val isChecked = selectedRelPaths.contains(track.relativePath)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isChecked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isDeleting) {
                                    selectedRelPaths = if (isChecked) {
                                        selectedRelPaths - track.relativePath
                                    } else {
                                        selectedRelPaths + track.relativePath
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (!isDeleting) {
                                        selectedRelPaths = if (checked) {
                                            selectedRelPaths + track.relativePath
                                        } else {
                                            selectedRelPaths - track.relativePath
                                        }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (track.album.isNotEmpty()) "${track.artist} • ${track.album}" else track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = formatFileSize(track.fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 削除ボタン
            if (selectedTracks.isNotEmpty()) {
                Button(
                    onClick = { showDeleteConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !isDeleting
                ) {
                    Text(
                        text = "選択した ${selectedTracks.size} 曲を削除 (${formatFileSize(selectedTotalBytes)} 解放)",
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

// ⚙️ 設定タブ画面
@Composable
fun SettingsTabScreen(
    modifier: Modifier = Modifier,
    discoveryStatus: String,
    onRestartDiscovery: () -> Unit,
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    serverPort: String,
    onServerPortChange: (String) -> Unit,
    serverToken: String,
    targetFolderUri: Uri?,
    onSaveTargetUri: (Uri) -> Unit,
    statusText: String,
    isConnected: Boolean,
    onTestConnection: () -> Unit,
    onStartQrScan: () -> Unit
) {
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            onSaveTargetUri(uri)
        }
    }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("⚙️ 設定", style = MaterialTheme.typography.headlineMedium)

        // 🔍 PC自動検出 (mDNS) ＆ QRコード接続カード
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔍 PC接続 (自動探索 ＆ QRコード)", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = discoveryStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (discoveryStatus.startsWith("✅")) Color(0xFF2E7D32) else Color.DarkGray
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRestartDiscovery,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄 再検索")
                    }
                    FilledTonalButton(
                        onClick = onStartQrScan,
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Text("📷 QRスキャン")
                    }
                }
                Text(
                    text = if (serverToken.isNotBlank()) "🔐 ペアリング認証: 設定済 ✅" else "🔓 ペアリング認証: 未設定 ⚠️ (QRコードをスキャンしてください)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (serverToken.isNotBlank()) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            }
        }

        // PCエージェント接続カード（手動設定）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🖥️ PCエージェント接続 (手動設定)", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = serverIp,
                        onValueChange = onServerIpChange,
                        label = { Text("IPアドレス") },
                        modifier = Modifier.weight(0.68f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = onServerPortChange,
                        label = { Text("ポート") },
                        modifier = Modifier.weight(0.32f),
                        singleLine = true
                    )
                }

                Button(
                    onClick = onTestConnection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("接続テスト ＆ プレイリスト更新")
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) Color(0xFF2E7D32) else Color.Red
                )
            }
        }

        // 保存先ストレージカード
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📁 保存先フォルダー (本体/microSD)", style = MaterialTheme.typography.titleMedium)
                val folderDisplay = if (targetFolderUri != null) {
                    targetFolderUri.path ?: "設定済みフォルダー"
                } else {
                    "(未設定: アプリ専用Music領域に保存)"
                }
                Text(folderDisplay, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (targetFolderUri == null) "保存先フォルダーを選択する" else "保存先フォルダーを変更する")
                }
            }
        }

        // ❓ 接続できない時のチェックリストカード
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("❓ 接続できない時のチェックリスト", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "PCが見つからない・曲が取得できない場合は以下をご確認ください：",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
                Text(
                    text = "① iTunes「XML共有」設定 (最重要)\n" +
                            "PCのiTunesで「編集」➔「環境設定」➔「詳細」➔『iTunes ライブラリ XML を他のアプリケーションと共有』にチェックが入っているか確認してください。\n\n" +
                            "② Wi-Fi接続 / テザリングP2P直接接続 (重要)\n" +
                            "スマホとPCが同じWi-Fiルーターに接続されているか確認してください（モバイル通信不可）。\n" +
                            "※自宅にルーターがない場合：スマホの「Wi-Fiテザリング」をONにし、PCを接続すればパケット消費ゼロのP2P直接同期が可能です！\n\n" +
                            "③ PCエージェントの常駐\n" +
                            "PC側で「andSync.Desktop」がタスクトレイで起動しているか確認してください（右クリックでWebガイドが開きます）。\n\n" +
                            "④ Windowsファイアウォール\n" +
                            "PCのファイアウォールでandSyncの通信が許可されているか確認してください。\n\n" +
                            "⑤ 【安心機能】曲の自動救済 ＆ エラー時の確認\n" +
                            "iTunesの仕様でファイルパスが欠落した楽曲も、andSyncがPC上でiTunesと連携して自動補完・救済します（同期中はPCでiTunesを起動しておくと安心です）。\n" +
                            "万が一転送に失敗した曲がある場合、同期完了画面の「⚠️ エラー詳細を確認」から原因が日本語で確認できます（PCタスクトレイの「📄 同期エラーログを開く」からも確認可能）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // アプリ情報カード
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ℹ️ アプリ情報 ＆ 動作環境", style = MaterialTheme.typography.titleMedium)
                Text("andSyncTunes for Android v0.9.0 (Beta)", style = MaterialTheme.typography.bodyMedium)
                Text("軽量高速な国産iTunesプレイリストWi-Fi同期ツール", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "📱 Android対応: Android 8.0 以上 推奨 (7.0〜15/16対応)\n💻 PC側対応: Windows 10 (64-bit) / Windows 11",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
            }
        }

        // ☕ 開発者を応援する (投げ銭カード)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("☕ 開発者を応援する (投げ銭)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = "andSyncTunes は完全無料で快適にお使いいただける個人開発ツールです。もし気に入っていただけましたら、開発継続の応援（コーヒー1杯の差し入れ）をいただけると大変励みになります！✨",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ofuse.me/panforge"))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("💌 開発者を応援する (OFUSEを開く)", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}