using System;
using System.IO;
using System.Text.Json;

namespace andSync.Desktop;

public class AppSettings
{
    public ushort Port { get; set; } = 10000;
    public string LibraryPath { get; set; } = "";
    public string AuthToken { get; set; } = "";

    private static string SettingsFilePath =>
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(SettingsFilePath))
            {
                var json = File.ReadAllText(SettingsFilePath);
                var settings = JsonSerializer.Deserialize<AppSettings>(json);
                if (settings != null)
                {
                    if (string.IsNullOrWhiteSpace(settings.AuthToken))
                    {
                        settings.AuthToken = Guid.NewGuid().ToString("N");
                        settings.Save();
                    }
                    return settings;
                }
            }
        }
        catch
        {
            // エラー時はデフォルト値を返す
        }

        var defaultSettings = new AppSettings
        {
            AuthToken = Guid.NewGuid().ToString("N")
        };
        defaultSettings.Save();
        return defaultSettings;
    }

    public void Save()
    {
        try
        {
            var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(SettingsFilePath, json);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"設定ファイルの保存失敗: {ex.Message}");
        }
    }
}
