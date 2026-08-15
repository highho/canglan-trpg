package main

// memory.go — 二层记忆库（Java NpcMemoryStore 的 Go 移植）。
// 个体记忆以 npcId 为 scope；群体记忆以 group:village / group:guild 为 scope。
// JSON 文件持久化（与 Java 版格式完全一致，PC/Android 存档互通），线程安全，全局上限 500 条（FIFO 裁剪）。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

const globalCap = 500

// Entry 一条记忆：scope 为个体 npcId 或群体 group:xxx。
type Entry struct {
	ID         int    `json:"id"`
	Scope      string `json:"scope"`
	Content    string `json:"content"`
	Importance int    `json:"importance"`
}

type memoryFile struct {
	NextID   int     `json:"nextId"`
	Memories []Entry `json:"memories"`
}

// MemoryStore 线程安全的二层记忆库。
type MemoryStore struct {
	mu       sync.Mutex
	file     string
	memories []Entry
	nextID   int
}

// NewMemoryStore 加载（文件不存在/损坏 → 空库重建，不影响服务启动）。
func NewMemoryStore(file string) *MemoryStore {
	s := &MemoryStore{file: file, nextID: 1}
	s.load()
	return s
}

// Recall 按 id 倒序（最新在前）召回某 scope 的最近 limit 条内容。
func (s *MemoryStore) Recall(scope string, limit int) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []string
	if scope == "" || limit <= 0 {
		return out
	}
	for i := len(s.memories) - 1; i >= 0 && len(out) < limit; i-- {
		if s.memories[i].Scope == scope {
			out = append(out, s.memories[i].Content)
		}
	}
	return out
}

// Append 追加一条记忆并立即落盘；importance 收敛到 1~4。
func (s *MemoryStore) Append(scope, content string, importance int) {
	if scope == "" || content == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if importance < 1 {
		importance = 1
	} else if importance > 4 {
		importance = 4
	}
	s.memories = append(s.memories, Entry{ID: s.nextID, Scope: scope, Content: content, Importance: importance})
	s.nextID++
	for len(s.memories) > globalCap {
		s.memories = s.memories[1:]
	}
	s.saveLocked()
}

func (s *MemoryStore) Size() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.memories)
}

func (s *MemoryStore) load() {
	data, err := os.ReadFile(s.file)
	if err != nil {
		return
	}
	var mf memoryFile
	if json.Unmarshal(data, &mf) != nil {
		return // 文件损坏 → 空库重建
	}
	s.nextID = mf.NextID
	if s.nextID < 1 {
		s.nextID = 1
	}
	s.memories = mf.Memories
}

func (s *MemoryStore) saveLocked() {
	if s.file == "" {
		return
	}
	_ = os.MkdirAll(filepath.Dir(s.file), 0o755)
	mf := memoryFile{NextID: s.nextID, Memories: s.memories}
	data, err := json.MarshalIndent(mf, "", "    ")
	if err != nil {
		return
	}
	_ = os.WriteFile(s.file, data, 0o644) // 落盘失败仅内存保留（AI 铁律：不抛异常到调用方）
}
