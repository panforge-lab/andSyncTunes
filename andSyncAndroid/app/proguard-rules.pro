# ==============================================================================
# andSync - ProGuard / R8 難読化 ＆ 最適化ルール
# ==============================================================================

# 1. Gson / JSON シリアライズ対象データクラスの保護
# フィールド名が難読化されると JSON パースに失敗するため保持
-keepclassmembers class com.panforge.andsync.** {
    <fields>;
}
-keep class com.panforge.andsync.PlaylistItem { *; }
-keep class com.panforge.andsync.TrackItem { *; }
-keep class com.panforge.andsync.SyncErrorInfo { *; }
-keep class com.panforge.andsync.LocalPlaylistInfo { *; }

# 2. OkHttp 関連のルール (公式推奨)
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# 3. Google Play Code Scanner (バーコードスキャン)
-keep class com.google.android.gms.code.scanner.** { *; }

# 4. Compose 関連の属性保持
-keepattributes InnerClasses
