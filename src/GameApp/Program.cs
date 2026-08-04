using Avalonia;

namespace GameApp;

/// <summary>桌面入口。安卓移植时此文件保持不变，仅需新增 Android Activity 入口。</summary>
internal static class Program
{
    [STAThread]
    public static void Main(string[] args) => BuildAvaloniaApp()
        .StartWithClassicDesktopLifetime(args);

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();
}
