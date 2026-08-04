using System.Globalization;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Data.Converters;
using GameApp.ViewModels;

namespace GameApp.Views;

/// <summary>叙事类别 → 前景色（极简风：黑白灰为底，功能性着色区分语义）。</summary>
public sealed class KindToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        => value is NarrationKind kind ? kind switch
        {
            NarrationKind.Input => new SolidColorBrush(Color.FromRgb(0x59, 0x59, 0x59)),
            NarrationKind.System => new SolidColorBrush(Color.FromRgb(0x73, 0x73, 0x73)),
            NarrationKind.Dialogue => new SolidColorBrush(Color.FromRgb(0x1F, 0x4E, 0x79)),
            NarrationKind.Combat => new SolidColorBrush(Color.FromRgb(0x8B, 0x1A, 0x1A)),
            NarrationKind.Reward => new SolidColorBrush(Color.FromRgb(0x3D, 0x6B, 0x1F)),
            NarrationKind.Error => new SolidColorBrush(Color.FromRgb(0xB0, 0x00, 0x20)),
            _ => new SolidColorBrush(Color.FromRgb(0x1A, 0x1A, 0x1A)),
        }
        : new SolidColorBrush(Color.FromRgb(0x1A, 0x1A, 0x1A));

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        // DataContext 在构造之后才由外部赋值，必须在 DataContextChanged 中订阅自动滚屏
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object sender, EventArgs e)
    {
        if (DataContext is MainViewModel vm)
            vm.NarrationLines.CollectionChanged += (_, _) => NarrationScroll.ScrollToEnd();
    }
}
