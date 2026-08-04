namespace GameCore.AI;

/// <summary>
/// AiContext — AI 服务上下文。由 GameWorld.Bootstrap 创建。
/// 离线环境自动装配 Null 实现，调用方无需判空对象。
/// </summary>
public sealed class AiContext
{
    public AiSettings Settings { get; }
    public IAiChatService Chat { get; }
    public IAiEmbeddingService Embeddings { get; }

    /// <summary>实际生效的推理后端描述（Direct/Ollama/None）。</summary>
    public string Backend { get; }

    public bool IsChatAvailable => Chat.IsAvailable;
    public bool IsEmbeddingAvailable => Embeddings.IsAvailable;

    private AiContext(AiSettings settings, IAiChatService chat, IAiEmbeddingService embeddings, string backend)
    {
        Settings = settings;
        Chat = chat;
        Embeddings = embeddings;
        Backend = backend;
    }

    /// <summary>
    /// 按配置创建。模式选择：
    ///   Direct — 直连 GGUF 模型文件（LLamaSharp 进程内推理，缺文件降级为 Null）
    ///   Ollama — HTTP 服务（不可达降级为 Null）
    ///   Auto   — 优先 Direct；缺模型文件时回退 Ollama；都不可用则 Null
    /// </summary>
    public static AiContext Create(AiSettings settings = null)
    {
        settings ??= AiSettings.Default;
        if (!settings.Enabled)
            return new AiContext(settings, new NullAiChatService(), new NullAiEmbeddingService(), "None");

        var chatPath = ModelFileLocator.Resolve(settings.ChatModelFile, settings.ModelsDir);
        var embPath = ModelFileLocator.Resolve(settings.EmbeddingModelFile, settings.ModelsDir);
        var directOk = chatPath != null && embPath != null;

        var useDirect = settings.Mode == AiInferenceMode.Direct
                        || (settings.Mode == AiInferenceMode.Auto && directOk);

        if (useDirect && directOk)
            return new AiContext(settings,
                new LlamaSharpChatService(chatPath, settings),
                new LlamaSharpEmbeddingService(embPath),
                "Direct(GGUF直连)");

        if (settings.Mode == AiInferenceMode.Direct)
            // 强制直连但缺模型文件 → Null（不回退服务，遵守"不调用服务"约束）
            return new AiContext(settings, new NullAiChatService(), new NullAiEmbeddingService(), "None(缺模型文件)");

        return new AiContext(settings, new OllamaChatService(settings), new OllamaEmbeddingService(settings), "Ollama(HTTP)");
    }

    /// <summary>环境诊断描述（演示/日志用）。</summary>
    public string Describe()
        => $"后端:{Backend}  生成模型:{Chat.ModelName}({(IsChatAvailable ? "可用" : "不可用")})  " +
           $"嵌入模型:{Settings.OllamaEmbeddingModel}({(IsEmbeddingAvailable ? "可用" : "不可用")})" +
           (Chat is LlamaSharpChatService d ? $"  模型文件:{d.ModelPath}" : "");
}
