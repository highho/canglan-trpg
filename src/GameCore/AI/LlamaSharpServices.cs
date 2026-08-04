using LLama;
using LLama.Common;
using LLama.Native;
using LLama.Sampling;

namespace GameCore.AI;

/// <summary>
/// LlamaSharpChatService — 直连 GGUF 模型文件的进程内生成服务（LLamaSharp / llama.cpp）。
/// 铁律：不依赖任何外部服务，模型文件随项目分发即可迁移到任意机器（仅需CPU）。
/// 懒加载权重（首次 Chat 时加载，4.4GB Q4 模型约需 20~40 秒）；调用串行化保证线程安全。
/// </summary>
public sealed class LlamaSharpChatService : IAiChatService, IDisposable
{
    // ChatML 标记（拼接构造，避免源码中出现控制字符）
    private const string ImStart = "<|" + "im_start|>";
    private const string ImEnd = "<|" + "im_end|>";

    private readonly string _modelPath;
    private readonly AiSettings _settings;
    private readonly object _gate = new();

    private LLamaWeights _weights;
    private bool _loadFailed;

    public string ModelName => Path.GetFileName(_modelPath);
    public string ModelPath => _modelPath;

    /// <summary>可用 = 模型文件存在。权重加载延迟到首次调用。</summary>
    public bool IsAvailable => !_loadFailed && !string.IsNullOrEmpty(_modelPath) && File.Exists(_modelPath);

    public LlamaSharpChatService(string modelPath, AiSettings settings = null)
    {
        _modelPath = modelPath;
        _settings = settings ?? AiSettings.Default;
    }

    public string Chat(string system, string user, bool jsonMode = false)
    {
        if (!IsAvailable) return null;
        lock (_gate)
        {
            try
            {
                EnsureLoaded();
                if (_weights == null) return null;

                // Qwen2 ChatML 模板（模型文件可能未内嵌模板，手工构造保证确定性）
                var prompt =
                    ImStart + "system\n" + system + ImEnd + "\n" +
                    ImStart + "user\n" + user + ImEnd + "\n" +
                    ImStart + "assistant\n";

                var ctxParams = new ModelParams(_modelPath)
                {
                    ContextSize = (uint)_settings.ContextSize,
                    Embeddings = false
                };
                var executor = new StatelessExecutor(_weights, ctxParams);

                var inferenceParams = new InferenceParams
                {
                    MaxTokens = _settings.MaxTokens,
                    AntiPrompts = new List<string> { ImEnd },
                    SamplingPipeline = new DefaultSamplingPipeline
                    {
                        Temperature = jsonMode ? 0.1f : _settings.Temperature,
                        RepeatPenalty = 1.1f
                    }
                };

                var sb = new System.Text.StringBuilder();
                var task = System.Threading.Tasks.Task.Run(async () =>
                {
                    await foreach (var token in executor.InferAsync(prompt, inferenceParams))
                        sb.Append(token);
                });
                if (!task.Wait(TimeSpan.FromSeconds(_settings.TimeoutSeconds))) return null;
                return sb.ToString().Trim();
            }
            catch
            {
                return null;   // AI 失败永不阻塞游戏流程
            }
        }
    }

    private void EnsureLoaded()
    {
        if (_weights != null || _loadFailed) return;
        try
        {
            _weights = LLamaWeights.LoadFromFile(new ModelParams(_modelPath) { UseMemorymap = true });
        }
        catch
        {
            _loadFailed = true;
        }
    }

    public void Dispose() => _weights?.Dispose();
}

/// <summary>
/// LlamaSharpEmbeddingService — 直连 GGUF 嵌入模型（bge-m3）的进程内向量化服务。
/// </summary>
public sealed class LlamaSharpEmbeddingService : IAiEmbeddingService, IDisposable
{
    private readonly string _modelPath;
    private readonly object _gate = new();

    private LLamaWeights _weights;
    private LLamaEmbedder _embedder;
    private bool _loadFailed;

    public string ModelPath => _modelPath;
    public bool IsAvailable => !_loadFailed && !string.IsNullOrEmpty(_modelPath) && File.Exists(_modelPath);

    public LlamaSharpEmbeddingService(string modelPath) => _modelPath = modelPath;

    public float[][] Embed(string[] texts)
    {
        if (!IsAvailable || texts == null || texts.Length == 0) return null;
        lock (_gate)
        {
            try
            {
                EnsureLoaded();
                if (_embedder == null) return null;

                var result = new float[texts.Length][];
                for (var i = 0; i < texts.Length; i++)
                {
                    var vectors = _embedder.GetEmbeddings(texts[i]).GetAwaiter().GetResult();
                    if (vectors == null || vectors.Count == 0) return null;
                    result[i] = vectors[0].ToArray();
                }
                return result;
            }
            catch
            {
                return null;
            }
        }
    }

    private void EnsureLoaded()
    {
        if (_embedder != null || _loadFailed) return;
        try
        {
            _weights = LLamaWeights.LoadFromFile(new ModelParams(_modelPath)
            {
                UseMemorymap = true,
                Embeddings = true
            });
            var ctxParams = new ModelParams(_modelPath)
            {
                ContextSize = 512,
                Embeddings = true,
                PoolingType = LLamaPoolingType.Mean
            };
            _embedder = new LLamaEmbedder(_weights, ctxParams);
        }
        catch
        {
            _loadFailed = true;
        }
    }

    public void Dispose()
    {
        _embedder?.Dispose();
        _weights?.Dispose();
    }
}
