package main

// llm.go — OpenAI 兼容 /v1/chat/completions 客户端（Java OpenAiCompatClient 的 Go 移植）。
// 本地模型服务（Ollama/llama.cpp）与云端供应商（OpenAI/DeepSeek 等）同构接入；
// apiKey 非空时附 Authorization: Bearer（云端必需，本地可留空）。
// 任何失败（超时/网络/非200/解析错误）返回空串，由调用方降级规则引擎，永不抛异常。

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"
)

// LLMClient 一个供应商 LLM 客户端实例。
type LLMClient struct {
	baseURL  string
	apiKey   string
	model    string
	timeout  time.Duration
	httpCli  *http.Client
}

// NewLLMClient 构造客户端（baseUrl 去尾斜杠；model 空默认 local-model）。
func NewLLMClient(baseURL, apiKey, model string, timeout time.Duration) *LLMClient {
	if baseURL != "" && strings.HasSuffix(baseURL, "/") {
		baseURL = baseURL[:len(baseURL)-1]
	}
	if strings.TrimSpace(model) == "" {
		model = "local-model"
	}
	return &LLMClient{
		baseURL: baseURL,
		apiKey:  strings.TrimSpace(apiKey),
		model:   model,
		timeout: timeout,
		httpCli: &http.Client{Timeout: timeout},
	}
}

type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type chatRequest struct {
	Model       string        `json:"model"`
	Messages    []chatMessage `json:"messages"`
	MaxTokens   int           `json:"max_tokens"`
	Temperature float64       `json:"temperature"`
}

type chatResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
}

// Complete 生成一次补全（maxTokens 默认 128）；失败返回空串。
func (c *LLMClient) Complete(prompt string, maxTokens int) string {
	if c.baseURL == "" || maxTokens <= 0 {
		maxTokens = 128
	}
	body, err := json.Marshal(chatRequest{
		Model:       c.model,
		Messages:    []chatMessage{{Role: "user", Content: prompt}},
		MaxTokens:   maxTokens,
		Temperature: 0.7,
	})
	if err != nil {
		return ""
	}
	req, err := http.NewRequest(http.MethodPost, c.baseURL+"/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		return ""
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	resp, err := c.httpCli.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ""
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return ""
	}
	var cr chatResponse
	if json.Unmarshal(data, &cr) != nil || len(cr.Choices) == 0 {
		return ""
	}
	content := strings.TrimSpace(cr.Choices[0].Message.Content)
	if content == "" {
		return ""
	}
	return content
}
