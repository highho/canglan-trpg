# -*- coding: utf-8 -*-
"""苍岚大陆 AI 服务（MIGRATION_PLAN §4）。

LangGraph 图：召回记忆 → 构建 prompt → LLM 生成 → 安全过滤（→ 写入记忆）。
- 安装了 langgraph/openai 兼容端点时用真模型；否则规则引擎兜底，服务依旧可用。
- 零强制依赖：仅 stdlib（http.server + sqlite3 + urllib）。

端点：
  GET  /health            -> {"status":"ok","llm":true/false}
  POST /api/ai/chat       -> {"npcId","npcName","playerName","utterance","tags","memory"}
                             <- {"reply":"...","source":"llm|rule"}
  POST /api/ai/behavior   -> {"npcId","weights":[...]}（P7 仅占位，返回 weightDelta=0）

运行：python main.py [port=8000]
"""
import json
import os
import re
import sqlite3
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "memories.db")
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "")      # OpenAI 兼容端点（可选）
LLM_MODEL = os.environ.get("LLM_MODEL", "local-model")
LLM_TIMEOUT = 8                                          # 服务端超时（Java 侧另有 3s 客户端超时）

_local = threading.local()


def db() -> sqlite3.Connection:
    """每线程一个连接（sqlite3 连接不可跨线程）。"""
    if not hasattr(_local, "conn"):
        _local.conn = sqlite3.connect(DB_PATH)
        _local.conn.execute(
            "CREATE TABLE IF NOT EXISTS memories ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " npc_id TEXT NOT NULL,"          # 个体记忆归属；群体记忆用 group:village / group:guild
            " content TEXT NOT NULL,"
            " importance INTEGER DEFAULT 1,"  # 1~4 分级
            " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)")
    return _local.conn


# ==================== 图节点（LangGraph 风格线性管线，可替换为 StateGraph） ====================

def node_recall_memory(state: dict) -> dict:
    """召回二层记忆：个体（该 NPC 亲身经历）+ 群体（village/guild 传播），各取最近 3 条。"""
    cur = db().cursor()
    rows = []
    for scope in (state.get("npcId", ""), "group:village", "group:guild"):
        if not scope:
            continue
        cur.execute(
            "SELECT content FROM memories WHERE npc_id=? ORDER BY id DESC LIMIT 3", (scope,))
        rows += [r[0] for r in cur.fetchall()]
    state["memory"] = rows[:6]
    return state


def node_build_prompt(state: dict) -> dict:
    mem = "\n".join("- " + m for m in state.get("memory", [])) or "- （暂无记忆）"
    state["prompt"] = (
        f"你是 TRPG 世界中的 NPC「{state.get('npcName', '村民')}」。请依据设定与记忆，"
        f"用不超过两句话、符合世界观的口吻回应玩家。\n"
        f"玩家标签：{', '.join(state.get('tags', [])) or '无'}\n"
        f"你的记忆：\n{mem}\n"
        f"玩家「{state.get('playerName', '旅人')}」说：{state.get('utterance', '')}")
    return state


def node_generate(state: dict) -> dict:
    """LLM 生成；无 LLM 端点时走规则引擎（保证服务可用）。"""
    if LLM_BASE_URL:
        try:
            req = urllib.request.Request(
                LLM_BASE_URL.rstrip("/") + "/v1/chat/completions",
                data=json.dumps({
                    "model": LLM_MODEL,
                    "messages": [{"role": "user", "content": state["prompt"]}],
                    "max_tokens": 128,
                    "temperature": 0.7,
                }).encode("utf-8"),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=LLM_TIMEOUT) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            text = body["choices"][0]["message"]["content"].strip()
            if text:
                state["reply"], state["source"] = text, "llm"
                return state
        except Exception:
            pass  # 降级到规则引擎
    state["reply"], state["source"] = rule_reply(state), "rule"
    return state


def node_safety_filter(state: dict) -> dict:
    """安全过滤：截断过长内容、剥离控制字符。"""
    text = re.sub(r"[\x00-\x08\x0b-\x1f]", "", state.get("reply", ""))
    state["reply"] = text[:200].strip() or "……（对方沉默不语）"
    return state


def node_write_memory(state: dict) -> dict:
    """对话后写入个体记忆（重要性按话语长度粗估 1~2；P9 前不做 LLM 评分）。"""
    utterance = (state.get("utterance") or "").strip()
    npc_id = state.get("npcId") or ""
    if npc_id and utterance:
        importance = 2 if len(utterance) > 20 else 1
        db().execute(
            "INSERT INTO memories (npc_id, content, importance) VALUES (?,?,?)",
            (npc_id, f"玩家说：{utterance[:80]}", importance))
        db().commit()
    return state


def run_chat_graph(payload: dict) -> dict:
    """召回记忆 → 构建 prompt → LLM 生成 → 安全过滤 → 写入记忆。"""
    state = dict(payload)
    for node in (node_recall_memory, node_build_prompt, node_generate,
                 node_safety_filter, node_write_memory):
        state = node(state)
    return {"reply": state["reply"], "source": state["source"]}


# ==================== 规则引擎（无 LLM 时的兜底生成） ====================

RULES = [
    (r"天气", "天色说变就变，出门在外记得带伞。"),
    (r"任务|委托|讨伐", "公会告示板上贴着新委托，去那儿看看准没错。"),
    (r"商店|买|卖", "集市就在村子里头，货比三家不吃亏。"),
    (r"怪物|危险|哥布林", "荒野里不太平，夜里千万别走太远。"),
    (r"你好|您好", "嗯？是你啊。有什么事就说吧。"),
    (r"谢谢|感谢", "客气什么，出门在外互相照应是应该的。"),
    (r"再见|告辞", "路上小心。愿风指引你的方向。"),
]


def rule_reply(state: dict) -> str:
    utterance = state.get("utterance", "")
    npc = state.get("npcName", "村民")
    for pattern, reply in RULES:
        if re.search(pattern, utterance):
            return reply
    mem = state.get("memory") or []
    if mem:
        return f"（{npc}回忆道）说起来……{mem[0]}至于你问的事，我也不太清楚。"
    return f"{npc}思索了片刻：这事我也说不好，你去村里打听打听吧。"


# ==================== HTTP 服务（stdlib，零依赖） ====================

class Handler(BaseHTTPRequestHandler):

    def _send(self, code: int, obj: dict):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "llm": bool(LLM_BASE_URL)})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
        except Exception:
            self._send(400, {"error": "bad json"})
            return
        if self.path == "/api/ai/chat":
            self._send(200, run_chat_graph(payload))
        elif self.path == "/api/ai/behavior":
            weights = [0] * len(payload.get("behaviorIds", [0]))   # weightDelta=0（§4.3）
            self._send(200, {"weights": weights})
        else:
            self._send(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        pass   # 静默访问日志


def main():
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    db()   # 初始化表
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"ai-service listening on http://127.0.0.1:{port} (llm={'on' if LLM_BASE_URL else 'off'})")
    server.serve_forever()


if __name__ == "__main__":
    main()
