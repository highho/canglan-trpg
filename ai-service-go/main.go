package main

// main.go — 苍岚大陆 AI 服务（Go 零依赖实现，替代 Python ai-service / Java 内嵌管线）。
// 兼容既有外部服务契约（Java LangGraphHttpClient 直接接入，后端零改动）：
//
//	GET  /health          -> {"status":"ok","llm":bool}
//	POST /api/ai/chat     -> {"npcId","npcName","playerName","utterance","tags","memory"}
//	                        <- {"reply":"...","source":"llm|rule"}
//
// 记忆与供应商配置与 Java 内嵌管线共享同一文件（saveDir/memories.json、saveDir/ai-config.json），
// PC 版由 Go 启动器拉起本服务并把同一 saves 目录传给 Java 后端，配置在设置页保存后即时生效。
//
// 运行：ai-service.exe [-port 8081] [-saves saves]

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"path/filepath"
)

var pipeline *Pipeline

func main() {
	port := flag.Int("port", 8081, "监听端口")
	saves := flag.String("saves", "saves", "存档目录（memories.json / ai-config.json 所在目录）")
	flag.Parse()

	saveDir := *saves
	memory := NewMemoryStore(filepath.Join(saveDir, "memories.json"))
	settings := NewProviderSettings(filepath.Join(saveDir, "ai-config.json"))
	pipeline = NewPipeline(memory, settings)

	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/api/ai/chat", handleChat)

	addr := fmt.Sprintf("127.0.0.1:%d", *port)
	log.Printf("ai-service (Go) listening on http://%s (saves=%s)", addr, saveDir)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"llm":    pipeline.settings.LLMEnabled(),
	})
}

func handleChat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req ChatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad json"})
		return
	}
	// 调用方 Java 侧自身有 3s 超时 + 熔断，本服务最大耗时由 LLM 8s 超时兜底
	reply := pipeline.Chat(req)
	writeJSON(w, http.StatusOK, reply)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
