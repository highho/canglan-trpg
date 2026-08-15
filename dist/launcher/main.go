package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	dir, _ := filepath.Abs(filepath.Dir(os.Args[0]))
	java := filepath.Join(dir, "jre", "bin", "javaw.exe")
	jar := filepath.Join(dir, "canglan.jar")
	data := filepath.Join(dir, "data")
	saves := filepath.Join(dir, "saves")
	web := filepath.Join(dir, "web")

	// 1. 启动 Go AI 服务（零依赖，与 Java 后端共享同一 saves 目录：记忆/配置互通）
	aiCmd := exec.Command(filepath.Join(dir, "ai-service.exe"), "-port", "8081", "-saves", saves)
	aiCmd.Dir = dir
	aiCmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	_ = aiCmd.Start() // 启动失败不阻塞：Java 后端自动降级内嵌管线

	// 2. 启动 Java 后端，AI 对话指向 Go 服务（探活失败自动降级内嵌管线）
	cmd := exec.Command(java, "-Dfile.encoding=UTF-8", "-Dcanglan.ai.url=http://127.0.0.1:8081", "-jar", jar, data, saves, "8080", web)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		exec.Command("cmd", "/c", "msg", "%username%", "启动失败："+err.Error()).Run()
		return
	}
	time.Sleep(2 * time.Second)
	exec.Command("rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:8080/").Start()
}
