using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using andSync.PoC;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;

Console.OutputEncoding = System.Text.Encoding.UTF8;
Console.WriteLine("=== andSync Wi-Fi Sync Server PoC ===");

string defaultPath = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
    "Music", "iTunes", "iTunes Music Library.xml"
);

string xmlPath = args.Length > 0 ? args[0] : defaultPath;

if (!File.Exists(xmlPath))
{
    Console.ForegroundColor = ConsoleColor.Red;
    Console.WriteLine($"[エラー] XMLファイルが見つかりません: {xmlPath}");
    Console.ResetColor();
    return;
}

Console.WriteLine("iTunesライブラリを解析中...");
var sw = Stopwatch.StartNew();
var library = ITunesLibraryParser.Parse(xmlPath);
sw.Stop();
Console.WriteLine($"[成功] ライブラリ解析完了 ({sw.ElapsedMilliseconds} ms)");
Console.WriteLine($"トラック数: {library.Tracks.Count:N0} 件 | プレイリスト数: {library.Playlists.Count:N0} 件\n");

// Web サーバー (Kestrel) 構築
var builder = WebApplication.CreateBuilder();
builder.WebHost.UseUrls("http://0.0.0.0:5000");

var app = builder.Build();

// APKファイルのパス
string apkPath = Path.GetFullPath(@"..\andSyncAndroid\app\build\outputs\apk\debug\app-debug.apk");
ushort serverPort = 5000;
string localIp = GetLocalIpAddress();

// 1. ルート：簡易Webビュー（ブラウザで動作確認・APKダウンロード用）
app.MapGet("/", () => Results.Content($@"
<!DOCTYPE html>
<html lang='ja'>
<head>
    <meta charset='utf-8'>
    <meta name='viewport' content='width=device-width, initial-scale=1'>
    <title>andSync PoC Server</title>
    <style>
        body {{ font-family: sans-serif; margin: 20px; background: #f4f6f8; color: #333; }}
        h1 {{ color: #2c3e50; }}
        .card {{ background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 15px; }}
        a {{ color: #3498db; text-decoration: none; font-weight: bold; }}
        .btn {{ display: inline-block; background: #27ae60; color: white; padding: 12px 24px; border-radius: 6px; text-decoration: none; font-size: 16px; margin: 10px 0; }}
        ul {{ line-height: 1.8; }}
    </style>
</head>
<body>
    <h1>🎵 andSync Wi-Fi Sync PoC</h1>
    
    <div class='card' style='text-align: center;'>
        <h3>📷 スマホアプリ接続用 QRコード</h3>
        <p>スマホの「andSync」アプリを開き、<b>「設定」タブ ➔「📷 QRスキャン」</b>でこのコードを読み取ると一発で接続できます。</p>
        <img src='https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=http://{localIp}:{serverPort}' alt='接続QRコード' style='margin: 12px auto; display: block; border: 1px solid #ccc; padding: 8px; border-radius: 8px; background: white;' />
        <p style='color: #666; font-size: 14px;'>接続先: <b>http://{localIp}:{serverPort}</b></p>
    </div>

    <div class='card'>
        <h3>📱 Android アプリのダウンロード</h3>
        <p>スマホのブラウザでこのボタンをタップしてAPKをダウンロード・インストールしてください。</p>
        <a href='/download-apk' class='btn'>📥 andSync Androidアプリ (APK) をDL</a>
    </div>

    <div class='card'>
        <h3>API エンドポイント</h3>
        <ul>
            <li><a href='/api/ping' target='_blank'>/api/ping</a> (接続テスト)</li>
            <li><a href='/api/playlists' target='_blank'>/api/playlists</a> (プレイリスト一覧 JSON)</li>
        </ul>
    </div>

    <div class='card'>
        <h3>プレイリスト簡易プレビュー (先頭15件)</h3>
        <div id='list'>読み込み中...</div>
    </div>
    <script>
        fetch('/api/playlists')
            .then(res => res.json())
            .then(data => {{
                const html = data.slice(0, 15).map(pl => 
                    `<div><b>[${{pl.playlistId}}] ${{pl.name}}</b> (${{pl.trackCount}}曲) <a href='/api/playlists/${{pl.playlistId}}' target='_blank'>[詳細]</a></div>`
                ).join('');
                document.getElementById('list').innerHTML = html;
            }});
    </script>
</body>
</html>
", "text/html; charset=utf-8"));

// 2. APKダウンロード配信
app.MapGet("/download-apk", () =>
{
    if (!File.Exists(apkPath))
    {
        return Results.NotFound(new { error = "APK file not found on server", expectedPath = apkPath });
    }
    return Results.File(apkPath, "application/vnd.android.package-archive", "andSync-debug.apk");
});

// 3. 疎通確認
app.MapGet("/api/ping", () => Results.Ok(new
{
    status = "ok",
    version = "1.0.0-poc",
    trackCount = library.Tracks.Count,
    playlistCount = library.Playlists.Count,
    machineName = Environment.MachineName
}));

// 4. プレイリスト一覧
app.MapGet("/api/playlists", () =>
{
    var list = library.Playlists
        .Where(pl => pl.TrackIds.Count > 0)
        .Select(pl => new
        {
            playlistId = pl.PlaylistId,
            name = pl.Name,
            trackCount = pl.TrackIds.Count
        });
    return Results.Ok(list);
});

// 5. 特定プレイリストの詳細（楽曲一覧）
app.MapGet("/api/playlists/{id:int}", (int id) =>
{
    var pl = library.Playlists.FirstOrDefault(p => p.PlaylistId == id);
    if (pl == null) return Results.NotFound(new { error = "Playlist not found" });

    var tracks = pl.TrackIds
        .Where(tid => library.Tracks.ContainsKey(tid))
        .Select(tid =>
        {
            var t = library.Tracks[tid];
            var fileName = !string.IsNullOrEmpty(t.Location) ? Path.GetFileName(t.Location) : $"{t.Name}.mp3";
            long fileSize = 0;
            long lastModified = 0;
            if (!string.IsNullOrEmpty(t.Location) && File.Exists(t.Location))
            {
                var fi = new FileInfo(t.Location);
                fileSize = fi.Length;
                lastModified = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeMilliseconds();
            }

            return new
            {
                trackId = t.TrackId,
                name = t.Name,
                artist = t.Artist,
                album = t.Album,
                fileName = fileName,
                fileSize = fileSize,
                lastModified = lastModified,
                downloadUrl = $"/api/track/{t.TrackId}/download"
            };
        });

    return Results.Ok(new
    {
        playlistId = pl.PlaylistId,
        name = pl.Name,
        tracks = tracks
    });
});

// 6. 楽曲ダウンロード（バイナリストリーミング）
app.MapGet("/api/track/{id:int}/download", (int id) =>
{
    if (!library.Tracks.TryGetValue(id, out var track) || string.IsNullOrEmpty(track.Location))
    {
        return Results.NotFound(new { error = "Track not found" });
    }

    if (!File.Exists(track.Location))
    {
        return Results.NotFound(new { error = "File not found on disk", path = track.Location });
    }

    var fileName = Path.GetFileName(track.Location);
    return Results.File(track.Location, enableRangeProcessing: true, fileDownloadName: fileName);
});

// mDNS (Bonjour非依存) サービス広告の開始（物理LANのIPアドレスのみを明示して広告）
var mdns = new Makaretu.Dns.MulticastService();
var addresses = new List<IPAddress>();
if (IPAddress.TryParse(localIp, out var validIp))
{
    addresses.Add(validIp);
}

var sd = new Makaretu.Dns.ServiceDiscovery(mdns);
var serviceProfile = new Makaretu.Dns.ServiceProfile("andSync on " + Environment.MachineName, "_andsync._tcp", serverPort, addresses);
serviceProfile.AddProperty("ip", localIp);
serviceProfile.AddProperty("port", serverPort.ToString());

sd.Advertise(serviceProfile);
mdns.Start();

Console.ForegroundColor = ConsoleColor.Cyan;
Console.WriteLine("==================================================");
Console.WriteLine($"PCエージェントが起動しました！");
Console.WriteLine($"mDNSサービス広告中: _andsync._tcp (ポート: {serverPort})");
Console.WriteLine($"PC自身のブラウザ:  http://localhost:{serverPort}");
Console.WriteLine($"スマホのブラウザ:  http://{localIp}:{serverPort}");
Console.WriteLine("==================================================");
Console.ResetColor();
Console.WriteLine("Ctrl + C で終了できます。\n");

app.Run();

static string GetLocalIpAddress()
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
