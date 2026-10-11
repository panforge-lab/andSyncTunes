using System;
using System.Diagnostics;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace andSync.Desktop;

public class TrayApplicationContext : ApplicationContext
{
    private readonly NotifyIcon _notifyIcon;
    private readonly SyncServerManager _serverManager;
    private readonly AppSettings _settings;

    public TrayApplicationContext()
    {
        string debugLog = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "debug.log");
        try { File.AppendAllText(debugLog, "TrayApplicationContext constructor start\n"); } catch { }

        _settings = AppSettings.Load();
        try { File.AppendAllText(debugLog, "AppSettings loaded\n"); } catch { }

        _serverManager = new SyncServerManager();
        try { File.AppendAllText(debugLog, "SyncServerManager created\n"); } catch { }

        _notifyIcon = new NotifyIcon
        {
            Icon = CreateAppIcon(),
            Visible = true,
            Text = $"andSyncTunes ({_settings.Port})"
        };
        try { File.AppendAllText(debugLog, "NotifyIcon created\n"); } catch { }

        _notifyIcon.DoubleClick += (s, e) => OpenWebBrowser();
        BuildContextMenu();
        try { File.AppendAllText(debugLog, "ContextMenu built\n"); } catch { }

        // サーバー起動
        Task.Run(async () =>
        {
            string debugLog = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "debug.log");
            string logFile = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "startup.log");
            try
            {
                File.AppendAllText(debugLog, "Task.Run started\n");
                await _serverManager.StartAsync(_settings.Port, _settings.LibraryPath);
                File.AppendAllText(debugLog, "StartAsync completed\n");

                // UIの更新・バルーン通知（念のためUIスレッド上で安全に行う）
                _notifyIcon.Text = $"andSync ({_settings.Port}) - 稼働中";

                // Windows起動直後の通知センター混雑・消音を回避するため、少し待機してから通知を表示
                await Task.Delay(1500);

                try
                {
                    bool isItunesInstalled = SyncServerManager.IsItunesInstalled();
                    bool hasTracks = (_serverManager.Library?.Tracks.Count ?? 0) > 0;

                    if (!isItunesInstalled)
                    {
                        _notifyIcon.ShowBalloonTip(
                            7000,
                            "andSync ⚠️ iTunesが未検出です",
                            $"サーバーはポート {_settings.Port} で待機中ですが、PCに iTunes が見つかりません。\n同期を行うには iTunes (Windows版) のインストールが必要です。",
                            ToolTipIcon.Warning
                        );
                    }
                    else if (!hasTracks)
                    {
                        _notifyIcon.ShowBalloonTip(
                            7000,
                            "andSync ⚠️ iTunes楽曲が未読み込みです",
                            $"サーバーはポート {_settings.Port} で起動しましたが、楽曲が0件です。\niTunesの「環境設定」➔「詳細」で『XML共有』を有効にしてください。",
                            ToolTipIcon.Warning
                        );
                    }
                    else
                    {
                        _notifyIcon.ShowBalloonTip(
                            5000,
                            "andSync 起動完了 (常駐中)",
                            $"Wi-Fi同期サーバーが起動しました！\nポート: {_settings.Port} (IP: {_serverManager.LocalIp})\n解析済み楽曲: {_serverManager.Library?.Tracks.Count:N0} 曲",
                            ToolTipIcon.Info
                        );
                    }
                }
                catch (Exception tipEx)
                {
                    File.AppendAllText(debugLog, $"BalloonTip exception: {tipEx}\n");
                }

                // 起動記録をローカルログファイルに保存（トラブルシューティング用）
                string logMsg = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] andSync 起動完了 (Port: {_settings.Port}, IP: {_serverManager.LocalIp}, Tracks: {_serverManager.Library?.Tracks.Count:N0}){Environment.NewLine}";
                File.AppendAllText(logFile, logMsg);
                File.AppendAllText(debugLog, "Startup logged successfully\n");
            }
            catch (Exception ex)
            {
                File.AppendAllText(debugLog, $"Task.Run exception: {ex}\n");
                File.AppendAllText(logFile, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] 起動エラー: {ex.Message}{Environment.NewLine}");
                MessageBox.Show($"サーバー起動エラー: {ex.Message}", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        });
    }

    private void BuildContextMenu()
    {
        var menu = new ContextMenuStrip();

        // ヘッダー情報
        var headerItem = new ToolStripMenuItem($"🎵 andSyncTunes (ポート: {_settings.Port})") { Enabled = false };
        menu.Items.Add(headerItem);
        menu.Items.Add(new ToolStripSeparator());

        // Web画面・QRコード・ガイドを開く
        var openWebItem = new ToolStripMenuItem("📖 使い方・iTunes設定ガイド (Web/QR)", null, (s, e) => OpenWebBrowser());
        menu.Items.Add(openWebItem);

        // ポート変更
        var changePortItem = new ToolStripMenuItem("⚙️ ポート変更...", null, (s, e) => ShowChangePortDialog());
        menu.Items.Add(changePortItem);

        // ライブラリ再読み込み
        var reloadItem = new ToolStripMenuItem("🔄 ライブラリ再読み込み", null, (s, e) =>
        {
            try
            {
                _serverManager.LoadLibrary(_settings.LibraryPath);
                _notifyIcon.ShowBalloonTip(2000, "andSync", $"ライブラリを再解析しました！\n({_serverManager.Library?.Tracks.Count:N0}曲)", ToolTipIcon.Info);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"再読み込み失敗: {ex.Message}", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        });
        menu.Items.Add(reloadItem);

        // スタートアップ自動実行
        var startupItem = new ToolStripMenuItem("🚀 Windows起動時に自動実行")
        {
            Checked = IsStartupEnabled(),
            CheckOnClick = true
        };
        startupItem.Click += (s, e) =>
        {
            SetStartupEnabled(startupItem.Checked);
            string msg = startupItem.Checked
                ? "Windows起動時の自動実行を有効にしました！"
                : "Windows起動時の自動実行を解除しました。";
            _notifyIcon.ShowBalloonTip(2000, "andSync", msg, ToolTipIcon.Info);
        };
        menu.Items.Add(startupItem);

        menu.Items.Add(new ToolStripSeparator());

        // 転送結果ログを開く
        var transferLogItem = new ToolStripMenuItem("📄 転送結果ログを開く (メモ帳)", null, (s, e) =>
        {
            try
            {
                string logPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "transferred_tracks.log");
                if (!File.Exists(logPath))
                {
                    File.WriteAllText(logPath, $"# andSyncTunes 転送結果ログ - 作成日: {DateTime.Now:yyyy-MM-dd HH:mm:ss}\n", System.Text.Encoding.UTF8);
                }
                Process.Start(new ProcessStartInfo("notepad.exe", logPath) { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                MessageBox.Show($"ログを開けませんでした: {ex.Message}", "andSyncTunes", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        });
        menu.Items.Add(transferLogItem);

        // 同期エラーログを開く
        var errorLogItem = new ToolStripMenuItem("⚠️ 同期エラーログを開く (メモ帳)", null, (s, e) =>
        {
            try
            {
                string logPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sync_errors.log");
                if (File.Exists(logPath))
                {
                    Process.Start(new ProcessStartInfo("notepad.exe", logPath) { UseShellExecute = true });
                }
                else
                {
                    MessageBox.Show("同期エラーログはありません（同期エラーは発生していません）。", "andSyncTunes", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"ログを開けませんでした: {ex.Message}", "andSyncTunes", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        });
        menu.Items.Add(errorLogItem);

        menu.Items.Add(new ToolStripSeparator());

        // 終了
        var exitItem = new ToolStripMenuItem("🚪 終了", null, async (s, e) =>
        {
            _notifyIcon.Visible = false;
            await _serverManager.StopAsync();
            Application.Exit();
        });
        menu.Items.Add(exitItem);

        _notifyIcon.ContextMenuStrip = menu;
    }

    private const string StartupRegKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string StartupAppName = "andSyncTunes";

    private static bool IsStartupEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(StartupRegKey, false);
            return key?.GetValue(StartupAppName) != null;
        }
        catch
        {
            return false;
        }
    }

    private static void SetStartupEnabled(bool enable)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(StartupRegKey, true);
            if (key == null) return;

            if (enable)
            {
                string exePath = Application.ExecutablePath;
                key.SetValue(StartupAppName, $"\"{exePath}\"");
            }
            else
            {
                key.DeleteValue(StartupAppName, false);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show($"スタートアップ設定の変更に失敗しました: {ex.Message}", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void UpdateTrayStatus()
    {
        _notifyIcon.Text = $"andSync ({_settings.Port}) - 稼働中";
        BuildContextMenu();
    }

    private void OpenWebBrowser()
    {
        try
        {
            string url = $"http://localhost:{_settings.Port}";
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show($"ブラウザ起動失敗: {ex.Message}", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ShowChangePortDialog()
    {
        using var form = new Form
        {
            Text = "andSync ポート設定",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            StartPosition = FormStartPosition.CenterScreen,
            Width = 320,
            Height = 180
        };

        var label = new Label
        {
            Text = "待ち受けポート番号 (1024〜65535):",
            Left = 20,
            Top = 20,
            Width = 260
        };

        var numBox = new NumericUpDown
        {
            Left = 20,
            Top = 45,
            Width = 260,
            Minimum = 1024,
            Maximum = 65535,
            Value = _settings.Port
        };

        var btnOk = new Button
        {
            Text = "保存＆再起動",
            Left = 70,
            Top = 90,
            Width = 110,
            DialogResult = DialogResult.OK
        };

        var btnCancel = new Button
        {
            Text = "キャンセル",
            Left = 190,
            Top = 90,
            Width = 90,
            DialogResult = DialogResult.Cancel
        };

        form.Controls.Add(label);
        form.Controls.Add(numBox);
        form.Controls.Add(btnOk);
        form.Controls.Add(btnCancel);
        form.AcceptButton = btnOk;
        form.CancelButton = btnCancel;

        if (form.ShowDialog() == DialogResult.OK)
        {
            ushort newPort = (ushort)numBox.Value;
            if (newPort != _settings.Port)
            {
                _settings.Port = newPort;
                _settings.Save();

                Task.Run(async () =>
                {
                    try
                    {
                        await _serverManager.StartAsync(newPort, _settings.LibraryPath);
                        UpdateTrayStatus();
                        _notifyIcon.ShowBalloonTip(
                            3000,
                            "ポート変更完了",
                            $"サーバーをポート {newPort} で再起動しました！\nmDNSとQRコードも新ポートに自動更新されました。",
                            ToolTipIcon.Info
                        );
                    }
                    catch (Exception ex)
                    {
                        MessageBox.Show($"再起動エラー: {ex.Message}", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    }
                });
            }
        }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll", CharSet = System.Runtime.InteropServices.CharSet.Auto)]
    private static extern bool DestroyIcon(IntPtr handle);

    private static Icon CreateAppIcon()
    {
        try
        {
            string icoPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
            if (File.Exists(icoPath))
            {
                return new Icon(icoPath);
            }

            using var bmp = new Bitmap(32, 32);
            using var g = Graphics.FromImage(bmp);
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            // 背景：鮮やかな青丸
            using var brush = new SolidBrush(Color.FromArgb(33, 150, 243));
            g.FillEllipse(brush, 1, 1, 30, 30);

            // 内側に白で音符記号
            using var font = new Font("Arial", 16, FontStyle.Bold);
            using var textBrush = new SolidBrush(Color.White);
            var sf = new StringFormat
            {
                Alignment = StringAlignment.Center,
                LineAlignment = StringAlignment.Center
            };
            g.DrawString("♪", font, textBrush, new RectangleF(0, 0, 32, 32), sf);

            var hIcon = bmp.GetHicon();
            var icon = (Icon)Icon.FromHandle(hIcon).Clone();
            DestroyIcon(hIcon);
            return icon;
        }
        catch
        {
            return SystemIcons.Information;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _notifyIcon.Dispose();
        }
        base.Dispose(disposing);
    }
}
