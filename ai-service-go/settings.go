package main

// settings.go — AI 供应商接入配置（Java AiProviderSettings 的 Go 移植）。
// 统一为 OpenAI 兼容端点：本地模型服务（Ollama/llama.cpp）与云端模型同构，差异仅在 baseUrl/apiKey/model。
// 配置持久化于 saveDir/ai-config.json（与 Java 版格式一致）；每次对话动态重读文件（mtime 变化时），
// 因此 PC 版前端在 Java 后端保存的配置对 Go 服务即时生效（两进程共享同一 saves 目录）。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// ProviderConfig 供应商配置（enabled=是否启用 LLM 生成；apiKey 本地服务可留空）。
type ProviderConfig struct {
	Enabled bool   `json:"enabled"`
	BaseURL string `json:"baseUrl"`
	APIKey  string `json:"apiKey"`
	Model   string `json:"model"`
}

// ProviderSettings 配置管理：文件路径 + 内存缓存（mtime 感知）。
type ProviderSettings struct {
	mu      sync.Mutex
	file    string
	config  ProviderConfig
	mtime   int64
	loaded  bool
}

// NewProviderSettings 初始化：文件存在则加载，否则默认禁用。
func NewProviderSettings(file string) *ProviderSettings {
	s := &ProviderSettings{file: file}
	if file != "" {
		if data, err := os.ReadFile(file); err == nil {
			var cfg ProviderConfig
			if json.Unmarshal(data, &cfg) == nil {
				s.config = cfg
				s.loaded = true
				if fi, err := os.Stat(file); err == nil {
					s.mtime = fi.ModTime().UnixNano()
				}
			}
		}
	}
	return s
}

// Get 返回当前配置（文件有更新则重读；损坏回退上次缓存/禁用）。
func (s *ProviderSettings) Get() ProviderConfig {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == "" {
		return s.config
	}
	fi, err := os.Stat(s.file)
	if err != nil {
		// 配置文件不存在/不可读 → 视为禁用（发行版中配置由 Java 后端管理，删除即禁用）
		s.config = ProviderConfig{}
		s.loaded = false
		s.mtime = 0
		return s.config
	}
	if s.loaded && fi.ModTime().UnixNano() == s.mtime {
		return s.config
	}
	data, rerr := os.ReadFile(s.file)
	if rerr != nil {
		return s.config
	}
	var cfg ProviderConfig
	if json.Unmarshal(data, &cfg) != nil {
		return s.config // 损坏 → 保持上次
	}
	s.config = cfg
	s.loaded = true
	s.mtime = fi.ModTime().UnixNano()
	return s.config
}

// LLMEnabled 是否已配置并启用供应商（供 /health 展示）。
func (s *ProviderSettings) LLMEnabled() bool {
	cfg := s.Get()
	return cfg.Enabled && cfg.BaseURL != ""
}

func (s *ProviderSettings) FilePath() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == "" {
		return ""
	}
	if dir := filepath.Dir(s.file); dir != "" {
		_ = os.MkdirAll(dir, 0o755)
	}
	return s.file
}
