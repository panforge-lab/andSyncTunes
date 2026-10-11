using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;

namespace andSync.Desktop;

public class SyncServerManager
{
    private WebApplication? _app;
    private CancellationTokenSource? _cts;
    private Makaretu.Dns.MulticastService? _mdns;
    private Task? _serverTask;
    private FileSystemWatcher? _xmlWatcher;
    public const string GitHubLatestApkUrl = "https://github.com/panforge-lab/andSyncTunes/releases/latest/download/andSyncTunes.apk";
    public const string GitHubReleasesPageUrl = "https://github.com/panforge-lab/andSyncTunes/releases";

    private DateTime _lastXmlLoadTime = DateTime.MinValue;
    private string _currentXmlPath = "";

    public ushort CurrentPort { get; private set; } = 10000;
    public string LocalIp { get; private set; } = "localhost";
    public string AuthToken { get; private set; } = "";
    public ITunesLibrary? Library { get; private set; }
    public bool IsRunning { get; private set; }
    public string StatusMessage { get; private set; } = "停止中";

    public event Action<string>? OnLog;

    public static bool IsItunesInstalled()
    {
        try
        {
            // 1. COM ProgID チェック
            var comType = Type.GetTypeFromProgID("iTunes.Application");
            if (comType != null) return true;

            // 2. レジストリ（64bit / 32bit）チェック
            string[] registryKeys = new[]
            {
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
                @"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
            };

            foreach (var keyPath in registryKeys)
            {
                using var hklm = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(keyPath);
                if (hklm != null)
                {
                    foreach (var subKeyName in hklm.GetSubKeyNames())
                    {
                        using var subKey = hklm.OpenSubKey(subKeyName);
                        var displayName = subKey?.GetValue("DisplayName")?.ToString() ?? "";
                        if (displayName.Contains("iTunes", StringComparison.OrdinalIgnoreCase))
                        {
                            return true;
                        }
                    }
                }
            }

            // 3. 一般的なインストール先フォルダーチェック
            string p1 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "iTunes", "iTunes.exe");
            string p2 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "iTunes", "iTunes.exe");
            if (File.Exists(p1) || File.Exists(p2)) return true;
        }
        catch { }
        return false;
    }

    public void LoadLibrary(string customPath = "")
    {
        string defaultPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Music", "iTunes", "iTunes Music Library.xml"
        );
        string xmlPath = !string.IsNullOrWhiteSpace(customPath) ? customPath : defaultPath;

        if (!File.Exists(xmlPath))
        {
            throw new FileNotFoundException("iTunes Library XMLが見つかりません。", xmlPath);
        }

        var sw = Stopwatch.StartNew();
        Library = ITunesLibraryParser.Parse(xmlPath);
        sw.Stop();
        _lastXmlLoadTime = File.GetLastWriteTimeUtc(xmlPath);
        _currentXmlPath = xmlPath;

        SetupXmlWatcher(xmlPath);

        OnLog?.Invoke($"[成功] ライブラリ解析完了 ({sw.ElapsedMilliseconds} ms) / {Library.Tracks.Count:N0}曲, {Library.Playlists.Count:N0}プレイリスト");
    }

    public void CheckAndReloadIfXmlChanged()
    {
        if (string.IsNullOrEmpty(_currentXmlPath) || !File.Exists(_currentXmlPath)) return;
        try
        {
            var lastWrite = File.GetLastWriteTimeUtc(_currentXmlPath);
            if (lastWrite > _lastXmlLoadTime)
            {
                OnLog?.Invoke($"[自動更新] iTunes XMLの更新を検知したため、ライブラリを自動再読み込みします...");
                LoadLibrary(_currentXmlPath);
            }
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"[自動更新エラー] {ex.Message}");
        }
    }

    private void SetupXmlWatcher(string xmlPath)
    {
        try
        {
            _xmlWatcher?.Dispose();
            _xmlWatcher = null;

            var dir = Path.GetDirectoryName(xmlPath);
            var file = Path.GetFileName(xmlPath);
            if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
            {
                _xmlWatcher = new FileSystemWatcher(dir, file)
                {
                    NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.Size | NotifyFilters.FileName,
                    EnableRaisingEvents = true
                };

                var debounceTimer = new System.Timers.Timer(1500) { AutoReset = false };
                debounceTimer.Elapsed += (s, e) =>
                {
                    CheckAndReloadIfXmlChanged();
                };

                _xmlWatcher.Changed += (s, e) => debounceTimer.Stop();
                _xmlWatcher.Changed += (s, e) => debounceTimer.Start();
                _xmlWatcher.Created += (s, e) => debounceTimer.Stop();
                _xmlWatcher.Created += (s, e) => debounceTimer.Start();
            }
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"[監視設定エラー] {ex.Message}");
        }
    }

    private readonly ConcurrentDictionary<int, DateTime> _recentLoggedErrors = new();

    public void LogSyncError(int trackId, string reason, string detail, string solution)
    {
        try
        {
            // 同一曲のリトライ連打による重複ログを防止 (直近30秒以内に記録したTrack IDはスキップ)
            var now = DateTime.UtcNow;
            if (_recentLoggedErrors.TryGetValue(trackId, out var lastLogged) && (now - lastLogged).TotalSeconds < 30)
            {
                return;
            }
            _recentLoggedErrors[trackId] = now;

            string trackName = "不明な曲";
            string artistName = "不明なアーティスト";
            string location = "なし";

            if (Library != null && Library.Tracks.TryGetValue(trackId, out var track))
            {
                trackName = track.Name;
                artistName = track.Artist ?? "不明なアーティスト";
                location = track.Location ?? "なし";
            }

            string logPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sync_errors.log");
            string entry = $@"================================================================================
[同期エラー] {DateTime.Now:yyyy-MM-dd HH:mm:ss}
曲名: {trackName}
アーティスト: {artistName}
Track ID: {trackId}
ファイルパス: {location}
エラー原因: 【{reason}】
詳細: {detail}
対処方法: {solution}
================================================================================
";
            File.AppendAllText(logPath, entry, System.Text.Encoding.UTF8);
            OnLog?.Invoke($"[同期エラー記録] {trackName} - {reason}");
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"[エラーログ書き込み失敗] {ex.Message}");
        }
    }

    public string? TryResolveTrackLocationViaCom(Track track)
    {
        try
        {
            var type = Type.GetTypeFromProgID("iTunes.Application");
            if (type == null) return null;

            dynamic itunes = Activator.CreateInstance(type)!;
            if (itunes == null) return null;

            dynamic tracks = itunes.LibraryPlaylist?.Tracks;
            if (tracks == null) return null;

            dynamic itTrack = tracks.ItemByName(track.Name);
            if (itTrack != null)
            {
                string? loc = itTrack.Location;
                if (!string.IsNullOrEmpty(loc) && File.Exists(loc))
                {
                    OnLog?.Invoke($"[COM救済成功] '{track.Name}' の実体パスを取得: {loc}");
                    return loc;
                }
            }
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"[COM解決例外] {track.Name}: {ex.Message}");
        }
        return null;
    }

    public async Task StartAsync(ushort port, string libraryPath = "")
    {
        if (IsRunning) await StopAsync();

        CurrentPort = port;
        LocalIp = GetLocalIpAddress();

        var settings = AppSettings.Load();
        AuthToken = settings.AuthToken;

        if (Library == null)
        {
            LoadLibrary(libraryPath);
        }

        _cts = new CancellationTokenSource();

        // Kestrel Web サーバーの構築
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls($"http://0.0.0.0:{CurrentPort}");

        var app = builder.Build();
        _app = app;

        // セキュリティミドルウェア：二重の盾 (IPガード ＆ トークン認証)
        app.Use(async (context, next) =>
        {
            var path = context.Request.Path.Value ?? "";
            var remoteIp = context.Connection.RemoteIpAddress;

            bool isPrivateOrLocal = IsPrivateOrLocalIp(remoteIp);

            string? tokenHeader = context.Request.Headers["X-AndSync-Token"].FirstOrDefault();
            string? tokenQuery = context.Request.Query["token"].FirstOrDefault();
            bool hasValidToken = (!string.IsNullOrEmpty(AuthToken)) &&
                (string.Equals(tokenHeader, AuthToken, StringComparison.Ordinal) ||
                 string.Equals(tokenQuery, AuthToken, StringComparison.Ordinal));

            // 1. 第1の盾：IPガード (宅外グローバルIP遮断)
            if (!isPrivateOrLocal && !hasValidToken)
            {
                OnLog?.Invoke($"[セキュリティ遮断] 宅外グローバルIPアクセスをブロック: {remoteIp} -> {path}");
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                context.Response.ContentType = "application/json; charset=utf-8";
                await context.Response.WriteAsync("{\"error\":\"Forbidden\",\"message\":\"ローカルネットワーク（宅内LAN）外からのアクセスは遮断されています。\"}");
                return;
            }

            // 2. 第2の盾：API認証ガード (/api/*)
            bool isLoopback = remoteIp != null && IPAddress.IsLoopback(remoteIp);
            if (path.StartsWith("/api/", StringComparison.OrdinalIgnoreCase))
            {
                if (!isLoopback && !hasValidToken)
                {
                    OnLog?.Invoke($"[セキュリティ遮断] 未認証APIアクセスをブロック: {remoteIp} -> {path}");
                    context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                    context.Response.ContentType = "application/json; charset=utf-8";
                    await context.Response.WriteAsync("{\"error\":\"Unauthorized\",\"message\":\"andSync ペアリングトークンが無効または未設定です。PC画面のQRコードをスキャンして再接続してください。\"}");
                    return;
                }
            }

            await next();
        });

        // APK探索（Release版最優先）
        static string? ResolveApkPath()
        {
            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            var candidates = new[]
            {
                // 1. Release APK (プロジェクトビルド出力)
                Path.GetFullPath(Path.Combine(baseDir, @"..\..\..\..\andSyncAndroid\app\build\outputs\apk\release\app-release.apk")),
                // 2. releases/ 配下の正式APK
                Path.GetFullPath(Path.Combine(baseDir, @"..\..\..\..\releases\v0.9.0\andSyncTunes-v0.9.0.apk")),
                // 3. ポータブル配布ディレクトリ内
                Path.Combine(baseDir, "andSyncTunes.apk"),
                Path.Combine(baseDir, "andSyncTunes-v0.9.0.apk"),
                Path.Combine(baseDir, "app-release.apk"),
                Path.Combine(baseDir, "apk", "andSyncTunes.apk"),
                // 4. フォールバック: Debug APK
                Path.GetFullPath(Path.Combine(baseDir, @"..\..\..\..\andSyncAndroid\app\build\outputs\apk\debug\app-debug.apk")),
                Path.Combine(baseDir, "app-debug.apk")
            };
            return candidates.FirstOrDefault(File.Exists);
        }

        // 1. ルート：Webビュー ＆ チュートリアル ＆ QRコード
        app.MapGet("/", (HttpContext context) =>
        {
            context.Response.Headers.CacheControl = "no-cache, no-store, must-revalidate";
            context.Response.Headers.Pragma = "no-cache";
            context.Response.Headers.Expires = "0";

            int trackCount = Library?.Tracks.Count ?? 0;
            int playlistCount = Library?.Playlists.Count ?? 0;
            bool hasLibraryData = trackCount > 0;

            string statusBadge = hasLibraryData
                ? "<span style='background:#e8f5e9; color:#2e7d32; padding:4px 10px; border-radius:12px; font-weight:bold; font-size:14px;'>✅ 正常稼働中</span>"
                : "<span style='background:#ffebee; color:#c62828; padding:4px 10px; border-radius:12px; font-weight:bold; font-size:14px;'>⚠️ 楽曲未検出</span>";

            bool isInstalled = IsItunesInstalled();
            string qrData = Uri.EscapeDataString($"http://{LocalIp}:{CurrentPort}?token={AuthToken}");
            string libraryWarning = "";
            if (!hasLibraryData)
            {
                if (!isInstalled)
                {
                    libraryWarning = @"<div class='alert-warning' style='background:#fef2f2; border-left:5px solid #ef4444; color:#991b1b;'>
                        <b>⚠️ PCに iTunes がインストールされていない可能性があります！</b><br>
                        andSync で楽曲・プレイリストを同期するには、Windows版 <b>iTunes</b> が必要です。<br>
                        Apple公式サイト（またはMicrosoft Store）から iTunes をインストールし、楽曲を読み込んでください。
                    </div>";
                }
                else
                {
                    libraryWarning = @"<div class='alert-warning'>
                        <b>⚠️ iTunesの楽曲が読み込まれていません！</b><br>
                        下の「STEP 1: iTunes設定」を確認し、iTunesの環境設定で<b>「iTunes ライブラリ XML を他のアプリケーションと共有」</b>にチェックを入れてから、タスクトレイメニューの<b>「🔄 ライブラリ再読み込み」</b>を実行してください。
                    </div>";
                }
            }

            return Results.Content($@"
<!DOCTYPE html>
<html lang='ja'>
<head>
    <meta charset='utf-8'>
    <meta name='viewport' content='width=device-width, initial-scale=1'>
    <title>andSyncTunes Desktop Server - 使い方＆設定ガイド</title>
    <link rel='icon' type='image/x-icon' href='/favicon.ico'>
    <link rel='shortcut icon' type='image/x-icon' href='/favicon.ico'>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; margin: 0; background: #f0f2f5; color: #333; line-height: 1.6; }}
        .header {{ background: #2c3e50; color: white; padding: 25px 20px; text-align: center; }}
        .header h1 {{ margin: 0 0 8px 0; font-size: 26px; }}
        .header p {{ margin: 0; font-size: 14px; opacity: 0.85; }}
        .container {{ max-width: 800px; margin: 20px auto; padding: 0 15px 40px 15px; }}
        .card {{ background: white; padding: 20px; border-radius: 10px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); margin-bottom: 20px; }}
        h2 {{ color: #2c3e50; font-size: 18px; margin-top: 0; border-bottom: 2px solid #ecf0f1; padding-bottom: 8px; }}
        h3 {{ color: #34495e; font-size: 16px; margin: 15px 0 8px 0; }}
        .btn {{ display: inline-block; background: #27ae60; color: white; padding: 12px 24px; border-radius: 6px; text-decoration: none; font-size: 15px; font-weight: bold; margin: 8px 0; text-align: center; }}
        .btn:hover {{ background: #219653; }}
        .alert-warning {{ background: #fff8e1; border-left: 5px solid #ffb300; padding: 14px; border-radius: 6px; margin-bottom: 15px; color: #856404; font-size: 14px; }}
        .step-box {{ background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 15px; margin-bottom: 14px; }}
        .step-title {{ font-weight: bold; color: #1e293b; font-size: 15px; margin-bottom: 6px; display: flex; align-items: center; gap: 8px; }}
        .step-badge {{ background: #3b82f6; color: white; border-radius: 50%; width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; }}
        ol, ul {{ margin: 6px 0 6px 20px; padding: 0; }}
        li {{ margin-bottom: 4px; font-size: 14px; }}
        .highlight {{ color: #d97706; font-weight: bold; }}
        .stats-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-top: 10px; }}
        .stat-item {{ background: #f8fafc; padding: 12px; border-radius: 6px; text-align: center; border: 1px solid #e2e8f0; }}
        .stat-val {{ font-size: 22px; font-weight: bold; color: #2563eb; }}
        .stat-lbl {{ font-size: 12px; color: #64748b; }}
    </style>
</head>
<body>
    <div class='header'>
        <h1>🎵 andSyncTunes Desktop エージェント</h1>
        <p>Windows iTunes と Android の超軽量・高速 Wi-Fi 同期サーバー</p>
    </div>

    <div class='container'>
        <!-- ステータス概要 -->
        <div class='card'>
            <div style='display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;'>
                <h2 style='margin: 0; border: none; padding: 0;'>📊 サーバー ＆ ライブラリ状態</h2>
                {statusBadge}
            </div>
            {libraryWarning}
            <div class='stats-grid'>
                <div class='stat-item'>
                    <div class='stat-val'>{trackCount:N0}</div>
                    <div class='stat-lbl'>解析済み楽曲数</div>
                </div>
                <div class='stat-item'>
                    <div class='stat-val'>{playlistCount}</div>
                    <div class='stat-lbl'>解析済みプレイリスト</div>
                </div>
                <div class='stat-item'>
                    <div class='stat-val'>{CurrentPort}</div>
                    <div class='stat-lbl'>待受ポート番号</div>
                </div>
                <div class='stat-item'>
                    <div class='stat-val'>{Environment.MachineName}</div>
                    <div class='stat-lbl'>PC名</div>
                </div>
            </div>
            <p style='color: #64748b; font-size: 13px; margin: 12px 0 0 0;'>
                PCのローカルIP: <b>{LocalIp}</b> ｜ 接続アドレス: <b>http://{LocalIp}:{CurrentPort}</b>
            </p>
        </div>

        <!-- 🔰 はじめての準備・iTunes設定ガイド -->
        <div class='card' id='guide'>
            <h2>🔰 はじめての準備 ＆ iTunes設定ガイド</h2>
            <div style='display:flex; flex-wrap:wrap; gap:8px; margin:8px 0 12px 0;'>
                <span style='background:#eff6ff; color:#1d4ed8; border:1px solid #bfdbfe; padding:4px 12px; border-radius:12px; font-size:12.5px; font-weight:bold;'>💻 PC対応: Windows 10 (64bit) / Windows 11</span>
                <span style='background:#f0fdf4; color:#15803d; border:1px solid #bbf7d0; padding:4px 12px; border-radius:12px; font-size:12.5px; font-weight:bold;'>📱 スマホ対応: Android 8.0 以上 推奨 (7.0〜15/16対応)</span>
            </div>
            <p style='font-size: 14px; color: #475569;'>
                andSyncで同期を行う前に、以下の3つのステップをご確認ください。
            </p>

            <div class='step-box'>
                <div class='step-title'>
                    <span class='step-badge'>1</span>
                    <span>iTunes の「XML共有」を有効化する（必須！）</span>
                </div>
                <p style='font-size: 13.5px; margin: 4px 0 8px 0; color: #334155;'>
                    iTunesが最新のプレイリスト情報を外部アプリへ書き出すよう設定します。
                </p>
                <ol>
                    <li>PCで <b>iTunes</b> を起動します。</li>
                    <li>メニューバーの <b>「編集」 ➔ 「環境設定」</b> を開きます（Macでは「iTunes」➔「環境設定」）。</li>
                    <li>上部の <b>「詳細」</b> タブをクリックします。</li>
                    <li><b><span class='highlight'>「iTunes ライブラリ XML を他のアプリケーションと共有」</span> にチェックを入れます。</b></li>
                    <li><b>「OK」</b> をクリックして設定を保存します。</li>
                    <li>タスクトレイの andSync アイコンを右クリックし、<b>「🔄 ライブラリ再読み込み」</b> を実行します。</li>
                </ol>
                <div style='background: #f1f5f9; border-left: 4px solid #64748b; padding: 10px 12px; margin-top: 10px; border-radius: 4px; font-size: 13px; color: #475569;'>
                    <b>⏳ iTunesの更新タイムラグについて:</b><br>
                    iTunes側で楽曲を追加したりプレイリストを編集した場合、iTunesがディスク上のXMLファイルへ最新データを書き出すまでに<b>数秒〜数十秒程度のタイムラグ</b>が発生することがあります。<br>
                    直前の編集がスマホ側に反映されない場合は、<b>少し待ってから（またはiTunesを一度終了してXML書き出しを促した後に）</b>、タスクトレイの <b>「🔄 ライブラリ再読み込み」</b> を実行し、スマホ側で「再取得」ボタンを押してください。
                </div>
            </div>

            <div class='step-box'>
                <div class='step-title'>
                    <span class='step-badge'>2</span>
                    <span>PC と スマホ を同一 Wi-Fi に接続する (またはテザリングP2P直接接続)</span>
                </div>
                <ul>
                    <li><b><span class='highlight'>Wi-Fi接続必須</span></b>: PCとAndroidスマホが<b>同じWi-Fiルーター（同一ネットワーク/SSID）</b>に接続されている必要があります（※モバイル通信/4G/5Gでは接続できません）。</li>
                    <li>※公衆Wi-Fiや一部のルーターで<b>「プライバシーセパレーター」「AP分離」</b>が有効になっている場合、端末同士の通信が遮断されるためOFFに設定してください。</li>
                    <li><b>💡 自宅にWi-Fiルーターがない場合 (P2P直接同期)</b>: スマホの「Wi-Fiテザリング（ポータブルアクセスポイント）」をONにし、PCをそのWi-Fiに接続すれば、インターネットのギガ（通信量）を一切消費せずにPC-スマホ間の直接P2P通信で高速同期できます！</li>
                </ul>
            </div>

            <div class='step-box'>
                <div class='step-title'>
                    <span class='step-badge'>3</span>
                    <span>Windows ファイアウォールの許可</span>
                </div>
                <ul>
                    <li>初回起動時にWindowsセキュリティの警告ダイアログが出た場合、<b>「プライベート ネットワーク」にチェックを入れて「アクセスを許可」</b>してください。</li>
                </ul>
            </div>

            <div class='step-box'>
                <div class='step-title'>
                    <span class='step-badge'>4</span>
                    <span>【安心機能】iTunesの特殊記号・長文パス自動救済 ＆ エラー時の確認方法</span>
                </div>
                <ul>
                    <li><b>💡 自動救済ハイブリッド同期</b>:<br>
                        iTunesの仕様上、長いファイルパスや全角記号（《》や〜など）を含む一部の楽曲は、XMLファイルにファイルパスが書き出されない場合があります。<br>
                        <b>andSync は同期時に自動で iTunes と直接連携（COM接続）し、欠落したパスを1曲ずつ高速に自動補完して確実に転送します。</b><br>
                        ※この自動救済をスムーズに行うため、同期中はPC上の iTunes を起動したままにしておくことを推奨します（未起動でも自動起動を試みます）。
                    </li>
                    <li><b>⚠️ 万が一同期エラーが出た場合の確認方法</b>:<br>
                        ・<b>スマホ側</b>: 同期完了画面に表示される <b>「⚠️ 失敗した○曲のエラー詳細を確認」</b> ボタンをタップすると、曲名・所属プレイリスト・エラー原因がその場で確認できます。<br>
                        ・<b>PC側</b>: タスクトレイの andSync アイコンを右クリック ➔ <b>「📄 同期エラーログを開く」</b> で、失敗した楽曲の詳細パスや対処方法をいつでも確認できます。
                    </li>
                </ul>
            </div>
        </div>

        <!-- 📷 スマホ接続用 QRコード -->
        <div class='card' style='text-align: center;'>
            <h2>📷 スマホアプリ接続用 QRコード</h2>
            <p style='font-size: 14px; color: #475569;'>
                Androidの「andSync」アプリで <b>「設定」タブ ➔「📷 QRスキャン」</b> をタップして、このコードを読み取るだけで瞬時にペアリング完了します。
            </p>
            <img src='https://api.qrserver.com/v1/create-qr-code/?size=200x200&data={qrData}' alt='接続QRコード' style='margin: 10px auto; display: block; border: 1px solid #cbd5e1; padding: 10px; border-radius: 8px; background: white;' />
            <p style='color: #64748b; font-size: 13.5px;'>
                手動設定用URL: <b>http://{LocalIp}:{CurrentPort}</b><br>
                ペアリングトークン: <code style='background:#f1f5f9; padding:2px 8px; border-radius:4px; font-weight:bold; color:#0f172a;'>{AuthToken}</code>
            </p>
        </div>

        <!-- 📱 Android APK ダウンロード -->
        <div class='card'>
            <h2>📱 Android アプリのダウンロード</h2>
            <p style='font-size: 14px; color: #475569;'>
                スマホのブラウザでこのボタンをタップすると、最新の andSyncTunes アプリ（APK）を直接ダウンロードしてインストールできます。
            </p>
            <div style='text-align: center; margin: 15px 0;'>
                <a href='/download-apk' class='btn'>📥 andSyncTunes Androidアプリ (最新版APK) をダウンロード</a>
                <div style='margin-top: 10px;'>
                    <a href='{GitHubReleasesPageUrl}' target='_blank' style='font-size: 13px; color: #2563eb; text-decoration: none; font-weight: 500;'>📦 GitHub Releases で全バージョン・更新履歴を見る ↗</a>
                </div>
                <div style='margin-top: 8px; font-size: 12px; color: #64748b;'>※ 以前の旧版/Debug版をインストール済みの場合は、署名競合を防ぐため端末側でアンインストール後にインストールしてください。</div>
            </div>
        </div>

        <!-- ℹ️ アプリ情報 ＆ 動作環境 -->
        <div class='card'>
            <h2>ℹ️ アプリ情報 ＆ 動作環境</h2>
            <div style='font-size: 15px; font-weight: bold; color: #0f172a; margin-bottom: 2px;'>
                andSyncTunes エージェント for Windows v0.9.0 (Beta)
            </div>
            <div style='font-size: 13px; color: #64748b; margin-bottom: 10px;'>
                軽量高速な国産iTunesプレイリストWi-Fi同期ツール
            </div>
            <hr style='border: none; border-top: 1px solid #e2e8f0; margin: 10px 0;' />
            <div style='font-size: 13.5px; color: #475569; line-height: 1.6; margin-bottom: 12px;'>
                💻 <b>PC側対応</b>: Windows 10 (64-bit) / Windows 11<br>
                📱 <b>Android対応</b>: Android 8.0 以上 推奨 (7.0〜15/16対応)
            </div>
            <div style='background: #f0fdf4; border: 1px solid #bbf7d0; padding: 12px; border-radius: 8px; text-align: center;'>
                <div style='font-weight: bold; color: #166534; font-size: 14px; margin-bottom: 4px;'>☕ 開発者を応援する (投げ銭)</div>
                <div style='font-size: 13px; color: #374151; margin-bottom: 8px;'>andSyncTunes は完全無料の個人開発ツールです。気に入っていただけましたら開発継続の応援をいただけると大変励みになります！✨</div>
                <a href='https://ofuse.me/panforge' target='_blank' style='display: inline-block; background: #16a34a; color: white; padding: 8px 18px; border-radius: 6px; text-decoration: none; font-size: 13.5px; font-weight: bold;'>💌 開発者を応援する (OFUSEを開く)</a>
            </div>
        </div>
    </div>
</body>
</html>
", "text/html; charset=utf-8");
        });

        // 1.5. ファビコン配信 (/favicon.ico)
        string icoPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
        app.MapGet("/favicon.ico", () =>
        {
            if (File.Exists(icoPath))
            {
                return Results.File(icoPath, "image/x-icon");
            }
            return Results.NotFound();
        });

        // 2. APKダウンロード（ローカル優先 ＆ GitHub Releases 最新版へ自動転送）
        app.MapGet("/download-apk", (HttpContext context) =>
        {
            context.Response.Headers.CacheControl = "no-cache, no-store, must-revalidate";
            context.Response.Headers.Pragma = "no-cache";
            context.Response.Headers.Expires = "0";

            string? effectiveApk = ResolveApkPath();
            if (!string.IsNullOrEmpty(effectiveApk) && File.Exists(effectiveApk))
            {
                return Results.File(effectiveApk, "application/vnd.android.package-archive", "andSyncTunes.apk");
            }
            return Results.Redirect(GitHubLatestApkUrl);
        });

        // 3. 疎通確認
        app.MapGet("/api/ping", () =>
        {
            CheckAndReloadIfXmlChanged();
            return Results.Ok(new
            {
                status = "ok",
                version = "0.9.0-desktop",
                trackCount = Library?.Tracks.Count ?? 0,
                playlistCount = Library?.Playlists.Count ?? 0,
                machineName = Environment.MachineName
            });
        });

        // 4. プレイリスト一覧 (フォルダ階層情報含む)
        app.MapGet("/api/playlists", () =>
        {
            CheckAndReloadIfXmlChanged();
            if (Library == null) return Results.NotFound();
            var list = Library.Playlists
                .Where(pl => pl.IsFolder || pl.TrackIds.Count > 0)
                .Select(pl =>
                {
                    long totalBytes = 0;
                    foreach (var tid in pl.TrackIds)
                    {
                        if (Library.Tracks.TryGetValue(tid, out var t))
                        {
                            totalBytes += t.Size;
                        }
                    }
                    return new
                    {
                        playlistId = pl.PlaylistId,
                        name = pl.Name,
                        trackCount = pl.TrackIds.Count,
                        totalBytes = totalBytes,
                        persistentId = pl.PersistentId,
                        parentPersistentId = pl.ParentPersistentId,
                        isFolder = pl.IsFolder,
                        distinguishedKind = pl.DistinguishedKind
                    };
                });
            return Results.Ok(list);
        });

        // 5. 特定プレイリストの詳細
        app.MapGet("/api/playlists/{id:int}", (int id) =>
        {
            if (Library == null) return Results.NotFound();
            var pl = Library.Playlists.FirstOrDefault(p => p.PlaylistId == id);
            if (pl == null) return Results.NotFound(new { error = "Playlist not found" });

            var tracks = pl.TrackIds
                .Where(tid => Library.Tracks.ContainsKey(tid))
                .Select(tid =>
                {
                    var t = Library.Tracks[tid];
                    string? loc = t.Location;
                    if (string.IsNullOrEmpty(loc))
                    {
                        loc = TryResolveTrackLocationViaCom(t);
                        if (!string.IsNullOrEmpty(loc))
                        {
                            t = t with { Location = loc };
                            Library.Tracks[tid] = t;
                        }
                    }

                    var fileName = !string.IsNullOrEmpty(loc) ? Path.GetFileName(loc) : $"{t.Name}.mp3";
                    long size = 0;
                    long modified = 0;
                    if (!string.IsNullOrEmpty(loc) && File.Exists(loc))
                    {
                        var fi = new FileInfo(loc);
                        size = fi.Length;
                        modified = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeMilliseconds();
                    }
                    return new
                    {
                        trackId = t.TrackId,
                        name = t.Name,
                        artist = t.Artist,
                        album = t.Album,
                        fileName = fileName,
                        fileSize = size,
                        lastModified = modified,
                        downloadUrl = $"/api/track/{t.TrackId}/download"
                    };
                });

            return Results.Ok(new
            {
                playlistId = pl.PlaylistId,
                name = pl.Name,
                tracks
            });
        });

        // 6. 楽曲ダウンロード
        app.MapGet("/api/track/{id:int}/download", (int id) =>
        {
            if (Library == null || !Library.Tracks.TryGetValue(id, out var track))
            {
                LogSyncError(id, "Track not found", "ライブラリ内に指定されたTrack IDが存在しません。", "ライブラリを再読み込みしてください。");
                return Results.NotFound(new { error = "Track not found" });
            }

            string? effectiveLocation = track.Location;
            if (string.IsNullOrEmpty(effectiveLocation))
            {
                // オンデマンドCOM救済: XMLにLocationがない場合、iTunesから直接取得を試みる
                effectiveLocation = TryResolveTrackLocationViaCom(track);
                if (!string.IsNullOrEmpty(effectiveLocation))
                {
                    // メモリ上のTrackオブジェクトも更新して次回以降キャッシュ
                    var updatedTrack = track with { Location = effectiveLocation };
                    Library.Tracks[id] = updatedTrack;
                }
            }

            if (string.IsNullOrEmpty(effectiveLocation))
            {
                LogSyncError(id, "iTunes上にファイルパスがありません", "iTunesライブラリ上でファイルリンクが切れているか、XML上にLocation情報が存在しません。", "iTunes上で該当曲に「！」マークが付いていないか確認し、再インポートまたは再設定してください。");
                return Results.NotFound(new { error = "iTunes上にファイルパスがありません (Location空)" });
            }

            if (!File.Exists(effectiveLocation))
            {
                LogSyncError(id, "PC上に音源ファイルが見つかりません", $"指定されたパス '{effectiveLocation}' に実ファイルが存在しません。", "外付けHDDや保存先ドライブが正しく接続されているか、ファイルが移動・削除されていないかご確認ください。");
                return Results.NotFound(new { error = $"PC上に音源ファイルが見つかりません: {Path.GetFileName(effectiveLocation)}" });
            }

            var fileName = Path.GetFileName(effectiveLocation);
            return Results.File(effectiveLocation, enableRangeProcessing: true, fileDownloadName: fileName);
        });

        // mDNS 広告開始
        StartMdns(port, LocalIp);

        _serverTask = app.RunAsync(_cts.Token);
        IsRunning = true;
        StatusMessage = $"稼働中: http://{LocalIp}:{CurrentPort}";
        OnLog?.Invoke($"サーバー起動: http://{LocalIp}:{CurrentPort}");
    }

    private void StartMdns(ushort port, string ipStr)
    {
        try
        {
            _mdns = new Makaretu.Dns.MulticastService();
            var addresses = new List<IPAddress>();
            if (IPAddress.TryParse(ipStr, out var validIp))
            {
                addresses.Add(validIp);
            }

            var sd = new Makaretu.Dns.ServiceDiscovery(_mdns);
            var serviceProfile = new Makaretu.Dns.ServiceProfile("andSync on " + Environment.MachineName, "_andsync._tcp", port, addresses);
            serviceProfile.AddProperty("ip", ipStr);
            serviceProfile.AddProperty("port", port.ToString());
            serviceProfile.AddProperty("token", AuthToken);

            sd.Advertise(serviceProfile);
            _mdns.Start();
            OnLog?.Invoke($"mDNSサービス広告開始: _andsync._tcp (ポート: {port})");
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"mDNS開始エラー: {ex.Message}");
        }
    }

    public async Task StopAsync()
    {
        try
        {
            _mdns?.Dispose();
            _mdns = null;

            if (_cts != null)
            {
                _cts.Cancel();
                if (_serverTask != null) await _serverTask;
                _cts.Dispose();
                _cts = null;
            }

            if (_app != null)
            {
                await _app.StopAsync();
                await _app.DisposeAsync();
                _app = null;
            }
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"サーバー停止エラー: {ex.Message}");
        }
        finally
        {
            IsRunning = false;
            StatusMessage = "停止中";
            OnLog?.Invoke("サーバー停止完了");
        }
    }

    private static bool IsPrivateOrLocalIp(IPAddress? ip)
    {
        if (ip == null) return false;
        if (IPAddress.IsLoopback(ip)) return true;

        if (ip.IsIPv4MappedToIPv6)
        {
            ip = ip.MapToIPv4();
        }

        if (ip.AddressFamily == AddressFamily.InterNetwork)
        {
            byte[] bytes = ip.GetAddressBytes();
            if (bytes[0] == 10) return true;
            if (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) return true;
            if (bytes[0] == 192 && bytes[1] == 168) return true;
            if (bytes[0] == 169 && bytes[1] == 254) return true;
            if (bytes[0] == 100 && (bytes[1] & 0xc0) == 64) return true;
            return false;
        }

        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
        {
            if (ip.IsIPv6LinkLocal) return true;
            byte[] bytes = ip.GetAddressBytes();
            if ((bytes[0] & 0xfe) == 0xfc) return true;
            return false;
        }

        return false;
    }

    private static string GetLocalIpAddress()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
            socket.Connect("8.8.8.8", 65530);
            var endPoint = socket.LocalEndPoint as IPEndPoint;
            return endPoint?.Address.ToString() ?? "localhost";
        }
        catch
        {
            return "localhost";
        }
    }
}
