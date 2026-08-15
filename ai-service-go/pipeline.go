package main

// pipeline.go — NPC 自由对话管线（Java EmbeddedAiClient 的 Go 移植）。
// 管线：召回记忆 → 构建 prompt → LLM 生成（可选，失败降级）→ 安全过滤 → 写入记忆。
// 铁律：永不失败；无 LLM 或 LLM 失败时走带记忆召回的规则回复。

import (
	"strings"
	"sync"
	"time"
)

// groupScopes 群体记忆传播域（与 Java/Python 版一致）。
var groupScopes = []string{"group:village", "group:guild"}

const replyLimit = 200

// ChatRequest 对话请求（对应 POST /api/ai/chat 载荷）。
type ChatRequest struct {
	NpcID      string   `json:"npcId"`
	NpcName    string   `json:"npcName"`
	PlayerName string   `json:"playerName"`
	Utterance  string   `json:"utterance"`
	Tags       []string `json:"tags"`
	Memory     []string `json:"memory"`
}

// ChatReply 对话响应。
type ChatReply struct {
	Reply  string `json:"reply"`
	Source string `json:"source"` // llm | rule
}

// CircuitBreaker 连续 3 次失败熔断 30s（Java AiCircuitBreaker 移植）。
type CircuitBreaker struct {
	mu          sync.Mutex
	failures    int
	openUntil   time.Time
	maxFailures int
	cooldown    time.Duration
}

func newCircuitBreaker() *CircuitBreaker {
	return &CircuitBreaker{maxFailures: 3, cooldown: 30 * time.Second}
}

func (b *CircuitBreaker) allowRequest() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.openUntil.After(time.Now()) {
		return false
	}
	if b.failures >= b.maxFailures {
		b.openUntil = time.Now().Add(b.cooldown)
		b.failures = 0
	}
	return true
}

func (b *CircuitBreaker) recordSuccess() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.failures = 0
	b.openUntil = time.Time{}
}

func (b *CircuitBreaker) recordFailure() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.failures++
}

// Pipeline 对话管线（记忆库 + 熔断 + 规则兜底 + 供应商配置）。
type Pipeline struct {
	memory   *MemoryStore
	settings *ProviderSettings
	breaker  *CircuitBreaker
	fallback *RuleFallback
}

// NewPipeline 构造管线。
func NewPipeline(memory *MemoryStore, settings *ProviderSettings) *Pipeline {
	return &Pipeline{
		memory:   memory,
		settings: settings,
		breaker:  newCircuitBreaker(),
		fallback: NewRuleFallback(nil),
	}
}

// Chat 执行一次完整对话；永不返回错误。
func (p *Pipeline) Chat(req ChatRequest) ChatReply {
	mem := p.recallMemory(req)
	prompt := buildPrompt(req, mem)
	text := ""
	fromLLM := false

	cfg := p.settings.Get()
	if cfg.Enabled && cfg.BaseURL != "" && p.breaker.allowRequest() {
		client := NewLLMClient(cfg.BaseURL, cfg.APIKey, cfg.Model, 8*time.Second)
		text = client.Complete(prompt, 128)
		if text != "" {
			p.breaker.recordSuccess()
			fromLLM = true
		} else {
			p.breaker.recordFailure()
		}
	}
	if !fromLLM {
		text = p.ruleReply(req, mem)
	}
	text = safetyFilter(text)
	p.writeMemory(req)
	if fromLLM {
		return ChatReply{Reply: text, Source: "llm"}
	}
	return ChatReply{Reply: text, Source: "rule"}
}

// ==================== 管线节点（对应 Java EmbeddedAiClient 同名函数） ====================

// recallMemory 召回二层记忆：个体（npcId）+ 群体（village/guild），各取最近 3 条，合计至多 6 条。
func (p *Pipeline) recallMemory(req ChatRequest) []string {
	var mem []string
	if req.NpcID != "" {
		mem = append(mem, p.memory.Recall(req.NpcID, 3)...)
	}
	for _, scope := range groupScopes {
		mem = append(mem, p.memory.Recall(scope, 3)...)
	}
	if len(mem) > 6 {
		mem = mem[:6]
	}
	return mem
}

// buildPrompt 构建 LLM prompt。
func buildPrompt(req ChatRequest, mem []string) string {
	var sb strings.Builder
	sb.WriteString("你是 TRPG 世界中的 NPC「")
	sb.WriteString(orDefault(req.NpcName, "村民"))
	sb.WriteString("」。请依据设定与记忆，用不超过两句话、符合世界观的口吻回应玩家。\n")
	sb.WriteString("玩家标签：")
	if len(req.Tags) == 0 {
		sb.WriteString("无")
	} else {
		sb.WriteString(strings.Join(req.Tags, ", "))
	}
	sb.WriteString("\n你的记忆：\n")
	if len(mem) == 0 {
		sb.WriteString("- （暂无记忆）\n")
	} else {
		for _, m := range mem {
			sb.WriteString("- ")
			sb.WriteString(m)
			sb.WriteString("\n")
		}
	}
	sb.WriteString("玩家「")
	sb.WriteString(orDefault(req.PlayerName, "旅人"))
	sb.WriteString("」说：")
	sb.WriteString(orDefault(req.Utterance, ""))
	return sb.String()
}

// ruleReply 规则回复（带记忆召回）：关键词命中 → 固定文案；否则有记忆 → 回忆模板；否则通用文案。
func (p *Pipeline) ruleReply(req ChatRequest, mem []string) string {
	if hit := p.fallback.MatchKeyword(req.Utterance); hit != "" {
		return hit
	}
	if len(mem) > 0 {
		return "（" + orDefault(req.NpcName, "村民") + "回忆道）说起来……" + mem[0] + "至于你问的事，我也不太清楚。"
	}
	return p.fallback.Generic()
}

// safetyFilter 安全过滤：剥离控制字符、截断过长内容。
func safetyFilter(text string) string {
	cleaned := strings.Map(func(r rune) rune {
		if r < 0x20 && r != '\n' && r != '\t' {
			return -1
		}
		return r
	}, text)
	if len([]rune(cleaned)) > replyLimit {
		cleaned = string([]rune(cleaned)[:replyLimit])
	}
	cleaned = strings.TrimSpace(cleaned)
	if cleaned == "" {
		return "……（对方沉默不语）"
	}
	return cleaned
}

// writeMemory 对话后写入个体记忆（重要性按话语长度粗估 1~2，与 Java 版一致）。
func (p *Pipeline) writeMemory(req ChatRequest) {
	utterance := strings.TrimSpace(req.Utterance)
	if req.NpcID == "" || utterance == "" {
		return
	}
	importance := 1
	if len([]rune(utterance)) > 20 {
		importance = 2
	}
	content := utterance
	if len([]rune(content)) > 80 {
		content = string([]rune(content)[:80])
	}
	p.memory.Append(req.NpcID, "玩家说："+content, importance)
}

func orDefault(s, dft string) string {
	if strings.TrimSpace(s) == "" {
		return dft
	}
	return s
}
