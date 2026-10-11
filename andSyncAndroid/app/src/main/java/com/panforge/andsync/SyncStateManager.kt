package com.panforge.andsync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同期処理の進行状態を一元管理するシングルトン状態ホルダー
 * Compose UI と Foreground Service の双方向同期をサポート
 */
object SyncStateManager {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _progressFraction = MutableStateFlow(0f)
    val progressFraction: StateFlow<Float> = _progressFraction.asStateFlow()

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError.asStateFlow()

    private val _failedTracks = MutableStateFlow<List<SyncErrorInfo>>(emptyList())
    val failedTracks: StateFlow<List<SyncErrorInfo>> = _failedTracks.asStateFlow()

    private val _syncResultItems = MutableStateFlow<List<SyncResultItem>>(emptyList())
    val syncResultItems: StateFlow<List<SyncResultItem>> = _syncResultItems.asStateFlow()

    private val _availableStorageBytes = MutableStateFlow<Long?>(null)
    val availableStorageBytes: StateFlow<Long?> = _availableStorageBytes.asStateFlow()

    fun updateStorage(bytes: Long) {
        _availableStorageBytes.value = bytes
    }

    @Volatile
    var isCancelRequested: Boolean = false
        private set

    fun startSync() {
        isCancelRequested = false
        _isSyncing.value = true
        _progressFraction.value = 0f
        _progressText.value = "同期準備中..."
        _statusMessage.value = null
        _isError.value = false
        _failedTracks.value = emptyList()
        _syncResultItems.value = emptyList()
    }

    fun updateProgress(current: Int, total: Int, trackName: String) {
        if (total > 0) {
            _progressFraction.value = current.toFloat() / total.toFloat()
        }
        _progressText.value = "転送中 ($current / $total): $trackName"
    }

    fun updateCustomProgress(current: Int, total: Int, text: String) {
        if (total > 0) {
            _progressFraction.value = current.toFloat() / total.toFloat()
        }
        _progressText.value = text
    }

    fun completeSync(message: String, resultItems: List<SyncResultItem> = emptyList()) {
        _isSyncing.value = false
        _progressFraction.value = 1f
        _progressText.value = message
        _statusMessage.value = message
        _isError.value = resultItems.any { !it.isSuccess }
        _syncResultItems.value = resultItems
        _failedTracks.value = resultItems.filter { !it.isSuccess }.map {
            SyncErrorInfo(it.trackId, it.trackName, it.artist, it.album, it.playlistName, it.errorMessage ?: "")
        }
        isCancelRequested = false
    }

    fun failSync(error: String) {
        _isSyncing.value = false
        _progressText.value = "エラー: $error"
        _statusMessage.value = "エラー: $error"
        _isError.value = true
        isCancelRequested = false
    }

    fun requestCancel() {
        isCancelRequested = true
        _progressText.value = "同期を中止しています..."
    }

    fun finishCancelled() {
        _isSyncing.value = false
        _progressText.value = "同期を中止しました"
        _statusMessage.value = "同期を中止しました"
        _isError.value = false
        isCancelRequested = false
    }

    /**
     * Wi-Fi またはローカル有線LAN (テザリング/Ethernet) に接続されているか検証
     * モバイル通信 (TRANSPORT_CELLULAR) での誤同期によるパケット浪費を防止
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

data class SyncResultItem(
    val trackId: Int,
    val trackName: String,
    val artist: String?,
    val album: String?,
    val playlistName: String?,
    val isSuccess: Boolean, // true = ⭕ 転送成功, false = ❌ 転送失敗
    val errorMessage: String? = null
)

data class SyncErrorInfo(
    val trackId: Int,
    val trackName: String,
    val artist: String?,
    val album: String?,
    val playlistName: String?,
    val errorMessage: String
)
