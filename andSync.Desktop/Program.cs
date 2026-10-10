namespace andSync.Desktop;

static class Program
{
    private const string MutexName = @"Global\andSync_Desktop_SingleInstance_Mutex";

    /// <summary>
    ///  The main entry point for the application.
    /// </summary>
    [STAThread]
    static void Main()
    {
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool createdNew);
        if (!createdNew)
        {
            MessageBox.Show(
                "andSync は既に起動しています。\n画面右下のタスクトレイ（通知領域）にある 🎵 アイコンをご確認ください。",
                "andSync - 多重起動の防止",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information
            );
            return;
        }

        string logPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "debug.log");
        try { File.WriteAllText(logPath, "Main started\n"); } catch { }

        AppDomain.CurrentDomain.UnhandledException += (s, e) =>
        {
            var ex = e.ExceptionObject as Exception;
            try { File.AppendAllText(logPath, $"Unhandled: {ex?.ToString()}\n"); } catch { }
            MessageBox.Show($"エラーが発生しました: {ex?.Message}\n(詳細は debug.log を確認してください)", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
        };

        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (s, e) =>
        {
            try { File.AppendAllText(logPath, $"ThreadException: {e.Exception}\n"); } catch { }
            MessageBox.Show($"エラーが発生しました: {e.Exception.Message}\n(詳細は debug.log を確認してください)", "andSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
        };

        try
        {
            try { File.AppendAllText(logPath, "Initializing ApplicationConfiguration\n"); } catch { }
            ApplicationConfiguration.Initialize();

            Application.ApplicationExit += (s, e) =>
            {
                try { File.AppendAllText(logPath, "Application.ApplicationExit event fired!\n"); } catch { }
            };

            TrayApplicationContext ctx;
            try
            {
                File.AppendAllText(logPath, "Creating TrayApplicationContext\n");
                ctx = new TrayApplicationContext();
                File.AppendAllText(logPath, "TrayApplicationContext instance created successfully\n");
            }
            catch (Exception ex)
            {
                File.AppendAllText(logPath, $"TrayApplicationContext constructor crashed: {ex}\n");
                throw;
            }
            ctx.ThreadExit += (s, e) =>
            {
                try { File.AppendAllText(logPath, "ApplicationContext.ThreadExit event fired!\n"); } catch { }
            };

            try { File.AppendAllText(logPath, "Running Application.Run\n"); } catch { }
            Application.Run(ctx);
            try { File.AppendAllText(logPath, "Application.Run finished\n"); } catch { }
        }
        catch (Exception ex)
        {
            try { File.AppendAllText(logPath, $"Main catch: {ex}\n"); } catch { }
        }
    }    
}