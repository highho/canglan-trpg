using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using GameApp.ViewModels;
using GameApp.Views;

namespace GameApp;

public partial class App : Avalonia.Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = new MainWindow { DataContext = new MainViewModel() };
        }
        else if (ApplicationLifetime is ISingleViewApplicationLifetime singleView)
        {
            // 安卓单视图生命周期
            singleView.MainView = new MainWindow { DataContext = new MainViewModel() };
        }
        base.OnFrameworkInitializationCompleted();
    }
}
