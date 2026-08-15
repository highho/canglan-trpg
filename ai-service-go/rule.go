package main

// rule.go — AI 不可用时的规则兜底（Java RuleFallbackService 的 Go 移植）。
// 关键词匹配 + 固定文案，永不失败。

import (
	"math/rand"
	"strings"
)

// keywordReplies {关键词(逗号分隔), 回复}。
var keywordReplies = [][2]string{
	{"你好,您好,在吗", "嗯？是你啊。有什么事就说吧，我正忙着呢。"},
	{"天气", "天色说变就变，出门在外记得带伞。"},
	{"任务,委托,讨伐", "公会告示板上贴着新委托，去那儿看看准没错。"},
	{"商店,买,卖,交易", "集市就在村子里头，货比三家不吃亏。"},
	{"危险,怪物,哥布林", "荒野里不太平，夜里千万别走太远。"},
	{"谢谢,感谢", "客气什么，出门在外互相照应是应该的。"},
	{"再见,告辞", "路上小心。愿风指引你的方向。"},
}

var genericReplies = []string{
	"嗯……这事我也说不好，你去村里打听打听吧。",
	"有意思。不过眼下我得先把手头的活干完。",
	"（对方若有所思地点了点头）冒险者，愿你一路平安。",
	"哈，你这人说话还挺有趣的。",
}

// RuleFallback 关键词匹配 + 随机通用文案。
type RuleFallback struct {
	rng *rand.Rand
}

// NewRuleFallback 构造（rng 可空）。
func NewRuleFallback(rng *rand.Rand) *RuleFallback {
	if rng == nil {
		rng = rand.New(rand.NewSource(42))
	}
	return &RuleFallback{rng: rng}
}

// Reply 依据玩家话语做关键词匹配；无命中返回通用文案。
func (r *RuleFallback) Reply(utterance string) string {
	if hit := r.MatchKeyword(utterance); hit != "" {
		return hit
	}
	return r.Generic()
}

// MatchKeyword 关键词匹配：命中返回对应文案，否则空串（供管线叠加记忆召回）。
func (r *RuleFallback) MatchKeyword(utterance string) string {
	for _, kv := range keywordReplies {
		for _, key := range strings.Split(kv[0], ",") {
			if strings.Contains(utterance, key) {
				return kv[1]
			}
		}
	}
	return ""
}

// Generic 随机通用文案。
func (r *RuleFallback) Generic() string {
	return genericReplies[r.rng.Intn(len(genericReplies))]
}
