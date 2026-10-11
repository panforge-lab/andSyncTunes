# ==============================================================================
# andSyncTunes - ProGuard / R8 難読化 ＆ 最適化ルール
# ==============================================================================

# 1. andSync アプリ内全クラスおよびデータモデルの完全保護
# GsonによるリフレクションパースやComposeプレビューの破壊を防ぐ
-keep class com.panforge.andsync.** { *; }
-keepclassmembers class com.panforge.andsync.** { *; }
-keepclassmembernames class com.panforge.andsync.** { *; }
-keepclassmembers enum com.panforge.andsync.** { *; }

# 2. Gson 公式推奨ルール (リフレクション・型シグネチャ保護)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# 3. Google Play Code Scanner & ML Kit 関連の完全保護
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

-keep class com.google.android.datatransport.** { *; }
-dontwarn com.google.android.datatransport.**

# 4. OkHttp / Okio 関連 (公式推奨)
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class okhttp3.** { *; }
