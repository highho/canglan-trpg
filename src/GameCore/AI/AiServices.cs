using System.Text;
using System.Text.Json;

namespace GameCore.AI;

// ==================== 配置 ====================

/// <summary>推理模式。Direct=直连GGUF模型文件（可迁移，不依赖任何服务）；Ollama=HTTP服务；Auto=优先Direct，缺模型文件时回退Ollama。</summary>
public enum AiInferenceMode { Auto, Direct, Ollama }

/// <summary>
/// AiSettings — 本地AI配置。缺省直连 GGUF 模型文件（LLamaSharp 进程内推理，不依赖任何服务）。
/// 所需模型文件（搜索顺序：ModelsDir → 可执行目录逐级向上的 models/ → C:\GameModels）：
///   生成模型: qwen2-7b-q4.gguf (Qwen2 7B Q4 GGUF, 中文友好)
///   嵌入模型: bge-m3.gguf (1.2GB, 1024维向量)
/// 注意：llama.cpp 不支持非 ASCII 路径，中文目录下需把模型放到 C:\GameModels。
/// </summary>
public sealed record AiSettings(
    string ChatModelFile = "qwen2-7b-q4.gguf",
    string EmbeddingModelFile = "bge-m3.gguf",
    string ModelsDir = null,
    string OllamaBaseUrl = "http://localhost:11434",
    string OllamaChatModel = "qwen2",
    string OllamaEmbeddingModel = "bge-m3",
    int TimeoutSeconds = 300,
    float Temperature = 0.7f,
    int ContextSize = 2048,
    int MaxTokens = 256)
{
    public static AiSettings Default { get; } = new();
    public static AiSettings Disabled { get; } = new() { Enabled = false };

    public bool Enabled { get; init; } = true;
    public AiInferenceMode Mode { get; init; } = AiInferenceMode.Auto;
}

/// <summary>
/// ModelFileLocator — 模型文件定位：支持显式目录、可执行目录 models/、逐级向上搜索（兼容 bin/Debug 运行）。
/// </summary>
public static class ModelFileLocator
{
    /// <summary>解析模型文件绝对路径。找不到返回 null。</summary>
    public static string Resolve(string fileName, string modelsDir = null)
    {
        if (string.IsNullOrEmpty(fileName)) return null;

        // 1. 显式指定的模型目录
        if (!string.IsNullOrEmpty(modelsDir))
        {
            var p = Path.Combine(modelsDir, fileName);
            return File.Exists(p) ? p : null;
        }

        // 2. 绝对路径直接使用
        if (Path.IsPathRooted(fileName)) return File.Exists(fileName) ? fileName : null;

        // 3. 从可执行目录逐级向上搜索 models/ 文件夹
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = Path.Combine(dir.FullName, "models", fileName);
            if (File.Exists(candidate) && IsAsciiPath(candidate)) return candidate;
            dir = dir.Parent;
        }

        // 4. 固定分发目录（纯 ASCII 路径）
        //    铁律：llama.cpp 原生层无法打开非 ASCII 路径（中文目录会乱码），
        //    模型文件必须放在纯英文/数字路径下。
        foreach (var fixedDir in new[] { @"C:\GameModels", @"D:\GameModels" })
        {
            var candidate = Path.Combine(fixedDir, fileName);
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    }

    /// <summary>路径是否为纯 ASCII（llama.cpp 原生层只支持 ANSI 路径）。</summary>
    public static bool IsAsciiPath(string path)
    {
        foreach (var c in path) if (c > 127) return false;
        return true;
    }
}

/// <summary>对话消息（OpenAI 兼容格式）。</summary>
public sealed record ChatMessage(string Role, string Content);

// ==================== 抽象接口 ====================

/// <summary>
/// IAiChatService — 生成模型抽象。Ollama/LLamaSharp/任意本地推理均可实现。
/// 铁律：所有调用方必须先检查 IsAvailable，不可用时回退规则逻辑。
/// </summary>
public interface IAiChatService
{
    bool IsAvailable { get; }
    string ModelName { get; }

    /// <summary>单轮对话。jsonMode=true 时强制 JSON 输出。失败返回 null（不抛异常）。</summary>
    string Chat(string system, string user, bool jsonMode = false);
}

/// <summary>IAiEmbeddingService — 嵌入模型抽象（语义向量化）。</summary>
public interface IAiEmbeddingService
{
    bool IsAvailable { get; }

    /// <summary>批量向量化。失败返回 null。</summary>
    float[][] Embed(string[] texts);
}

// ==================== Ollama HTTP 实现 ====================

/// <summary>
/// OllamaChatService — 对接本机 Ollama /api/chat（stream=false）。
/// 零第三方依赖：HttpClient + System.Text.Json。
/// </summary>
public sealed class OllamaChatService : IAiChatService
{
    private readonly AiSettings _settings;
    private readonly HttpClient _http;
    private bool? _available;

    public string ModelName => _settings.OllamaChatModel;

    public OllamaChatService(AiSettings settings = null)
    {
        _settings = settings ?? AiSettings.Default;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(_settings.TimeoutSeconds) };
    }

    public bool IsAvailable
    {
        get
        {
            if (!_settings.Enabled) return false;
            if (_available.HasValue) return _available.Value;
            try
            {
                var resp = _http.GetAsync(_settings.OllamaBaseUrl + "/api/tags").GetAwaiter().GetResult();
                _available = resp.IsSuccessStatusCode &&
                             resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                                 .Contains(_settings.OllamaChatModel.Split(':')[0]);
            }
            catch
            {
                _available = false;
            }
            return _available.Value;
        }
    }

    public string Chat(string system, string user, bool jsonMode = false)
    {
        if (!IsAvailable) return null;
        try
        {
            var payload = new Dictionary<string, object>
            {
                ["model"] = _settings.OllamaChatModel,
                ["stream"] = false,
                ["options"] = new Dictionary<string, object> { ["temperature"] = _settings.Temperature },
                ["messages"] = new[]
                {
                    new Dictionary<string, string> { ["role"] = "system", ["content"] = system },
                    new Dictionary<string, string> { ["role"] = "user", ["content"] = user }
                }
            };
            if (jsonMode) payload["format"] = "json";

            var json = JsonSerializer.Serialize(payload);
            var resp = _http.PostAsync(_settings.OllamaBaseUrl + "/api/chat",
                new StringContent(json, Encoding.UTF8, "application/json")).GetAwaiter().GetResult();
            if (!resp.IsSuccessStatusCode) return null;

            using var doc = JsonDocument.Parse(resp.Content.ReadAsStringAsync().GetAwaiter().GetResult());
            return doc.RootElement.TryGetProperty("message", out var msg)
                   && msg.TryGetProperty("content", out var content)
                ? content.GetString()
                : null;
        }
        catch
        {
            return null;   // AI 失败永不阻塞游戏流程
        }
    }
}

/// <summary>
/// OllamaEmbeddingService — 对接 Ollama /api/embed（bge-m3）。
/// </summary>
public sealed class OllamaEmbeddingService : IAiEmbeddingService
{
    private readonly AiSettings _settings;
    private readonly HttpClient _http;
    private bool? _available;

    public OllamaEmbeddingService(AiSettings settings = null)
    {
        _settings = settings ?? AiSettings.Default;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(_settings.TimeoutSeconds) };
    }

    public bool IsAvailable
    {
        get
        {
            if (!_settings.Enabled) return false;
            if (_available.HasValue) return _available.Value;
            try
            {
                var resp = _http.GetAsync(_settings.OllamaBaseUrl + "/api/tags").GetAwaiter().GetResult();
                _available = resp.IsSuccessStatusCode &&
                             resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                                 .Contains(_settings.OllamaEmbeddingModel.Split(':')[0]);
            }
            catch
            {
                _available = false;
            }
            return _available.Value;
        }
    }

    public float[][] Embed(string[] texts)
    {
        if (!IsAvailable || texts == null || texts.Length == 0) return null;
        try
        {
            var payload = new Dictionary<string, object>
            {
                ["model"] = _settings.OllamaEmbeddingModel,
                ["input"] = texts
            };
            var json = JsonSerializer.Serialize(payload);
            var resp = _http.PostAsync(_settings.OllamaBaseUrl + "/api/embed",
                new StringContent(json, Encoding.UTF8, "application/json")).GetAwaiter().GetResult();
            if (!resp.IsSuccessStatusCode) return null;

            using var doc = JsonDocument.Parse(resp.Content.ReadAsStringAsync().GetAwaiter().GetResult());
            if (!doc.RootElement.TryGetProperty("embeddings", out var arr)) return null;

            var result = new float[arr.GetArrayLength()][];
            var i = 0;
            foreach (var vec in arr.EnumerateArray())
            {
                result[i] = new float[vec.GetArrayLength()];
                var j = 0;
                foreach (var v in vec.EnumerateArray()) result[i][j++] = (float)v.GetDouble();
                i++;
            }
            return result;
        }
        catch
        {
            return null;
        }
    }
}

// ==================== 空实现（离线回退） ====================

/// <summary>NullAiChatService — 无AI环境占位，保证调用方代码无需判空对象。</summary>
public sealed class NullAiChatService : IAiChatService
{
    public bool IsAvailable => false;
    public string ModelName => "none";
    public string Chat(string system, string user, bool jsonMode = false) => null;
}

/// <summary>NullAiEmbeddingService — 无AI环境占位。</summary>
public sealed class NullAiEmbeddingService : IAiEmbeddingService
{
    public bool IsAvailable => false;
    public float[][] Embed(string[] texts) => null;
}

// ==================== 向量工具 ====================

/// <summary>向量数学工具（意图匹配/语义检索用）。</summary>
public static class VectorMath
{
    public static float Cosine(float[] a, float[] b)
    {
        if (a == null || b == null || a.Length != b.Length) return 0f;
        float dot = 0, na = 0, nb = 0;
        for (var i = 0; i < a.Length; i++)
        {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        var denom = (float)(Math.Sqrt(na) * Math.Sqrt(nb));
        return denom <= 0 ? 0f : dot / denom;
    }
}
