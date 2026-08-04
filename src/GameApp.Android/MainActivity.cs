using Android.App;
using Android.Content.PM;
using Android.OS;
using Avalonia;
using Avalonia.Android;
using GameApp.ViewModels;

namespace GameApp.Android;

/// <summary>
/// 安卓入口 Activity。启动时把 APK 内的 Assets/Data 配置拷入应用私有目录，
/// 再交给 MainViewModel.DataRoot —— 安卓沙盒不允许读取程序目录，这是必要的落地步骤。
/// </summary>
[Activity(
    Label = "苍岚大陆",
    Theme = "@style/MainTheme",
    MainLauncher = true,
    ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize | ConfigChanges.UiMode)]
public class MainActivity : AvaloniaMainActivity<App>
{
    protected override void OnCreate(Bundle savedInstanceState)
    {
        PrepareGameData();
        base.OnCreate(savedInstanceState);
    }

    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
        => base.CustomizeAppBuilder(builder).WithInterFont();

    /// <summary>把 Assets/Data/*.json 拷到私有目录（每次启动覆盖，保证数据升级同步）。</summary>
    private void PrepareGameData()
    {
        var root = FilesDir.AbsolutePath;
        var dataDir = Path.Combine(root, "Data");
        Directory.CreateDirectory(dataDir);
        foreach (var name in Assets.List("Data") ?? Array.Empty<string>())
        {
            var target = Path.Combine(dataDir, name);
            using var input = Assets.Open($"Data/{name}");
            using var output = File.Create(target);
            input.CopyTo(output);
        }
        MainViewModel.DataRoot = root;
    }
}
