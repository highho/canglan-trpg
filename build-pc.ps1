# build-pc.ps1 — 苍岚大陆 PC（Windows x64）独立发行包构建。
# 产物 dist/canglan-trpg-win-x64.zip：内置精简 JRE（jlink）+ Go 启动器 + Go AI 服务，双击 苍岚大陆.exe 即玩。
# Go 组件：苍岚大陆.exe（启动器，先启 ai-service 再启 Java 后端并指向之）、ai-service.exe（AI 服务：二层记忆+规则兜底+供应商 LLM）。
$ErrorActionPreference = "Stop"
$root = "e:\studyprogram\sikiedu\Unity\C#\CSharp编程第七季\CSharp编程第七季代码\Test"
$dist = "$root\dist"
$stage = "$dist\win-x64"

Write-Host "[1/5] 编译后端（build-all 已存在则增量复用，缺则全量编译）"
if (-not (Test-Path "$root\build-all\com\canglan\api\HttpApiServer.class")) {
    Get-ChildItem -Recurse -Filter *.java -Path "$root\canglan-backend\canglan-core\src","$root\canglan-backend\canglan-data\src","$root\canglan-backend\canglan-world\src","$root\canglan-backend\canglan-save\src","$root\canglan-backend\canglan-ai-client\src","$root\canglan-backend\canglan-api\src" |
        ForEach-Object { $_.FullName } | Set-Content "$root\sources.txt" -Encoding utf8
    & javac --release 17 -encoding UTF-8 -d "$root\build-all" "@$root\sources.txt"
    if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }
}

Write-Host "[2/5] 组装发行目录"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory $stage | Out-Null
& jar --create --file "$stage\canglan.jar" --main-class com.canglan.api.HttpApiServer -C "$root\build-all" .
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败" }
Copy-Item -Recurse "$root\data" "$stage\data"
if (Test-Path "$stage\data\saves") { Remove-Item -Recurse -Force "$stage\data\saves" }
Copy-Item -Recurse "$root\frontend\dist" "$stage\web"
New-Item -ItemType Directory "$stage\saves" | Out-Null

Write-Host "[3/5] jlink 精简 JRE"
& jlink --add-modules java.base,java.logging,java.net.http --output "$stage\jre" --strip-debug --no-header-files --no-man-pages --compress=2 2>&1 |
    ForEach-Object { if ($_ -match "Error") { Write-Host $_ } }
if (-not (Test-Path "$stage\jre\bin\java.exe")) { throw "jlink 未产出 JRE" }

Write-Host "[4/5] 编译 Go 组件（启动器 + AI 服务）与说明"
# 用 Go 编译零依赖原生 exe（无黑窗、无需 .NET 运行时）
$launcherDir = "$dist\launcher"
if (Test-Path $launcherDir) { Remove-Item -Recurse -Force $launcherDir }
New-Item -ItemType Directory $launcherDir | Out-Null
@"
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
"@ | Set-Content "$launcherDir\main.go" -Encoding utf8

$env:CGO_ENABLED = "0"
& go build -ldflags="-s -w -H=windowsgui" -o "$launcherDir\苍岚大陆.exe" "$launcherDir\main.go"
if ($LASTEXITCODE -ne 0) { throw "go build 编译 exe 失败" }
Copy-Item "$launcherDir\苍岚大陆.exe" "$stage\苍岚大陆.exe"

# 编译 Go AI 服务（零依赖，替代 Python/内嵌 AI 管线；与 Java 后端共享 saves 目录）
$aiGoDir = "$root\ai-service-go"
if (-not (Test-Path "$aiGoDir\go.mod")) { throw "ai-service-go 目录缺失" }
Push-Location $aiGoDir
& go build -ldflags="-s -w" -o ai-service.exe .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "go build 编译 ai-service 失败" }
if (-not (Test-Path "$aiGoDir\ai-service.exe")) { throw "ai-service.exe 未产出" }
Copy-Item "$aiGoDir\ai-service.exe" "$stage\ai-service.exe"

$readme = @"
苍岚大陆（Windows x64 独立版）

运行：双击「苍岚大陆.exe」，自动在后台启动 Go AI 服务与游戏服务，并打开浏览器进入游戏。
关闭游戏：在任务管理器结束 javaw 与 ai-service 进程。

目录说明：
  jre\          内置精简 JRE（无需安装 Java）
  ai-service.exe Go 实现的 AI 服务（二层记忆 + 规则兜底 + 供应商 LLM 接入，零依赖）
  data\         游戏数据（21 个 JSON）
  web\          纯文字前端
  saves\        存档与 AI 记忆（运行时生成，save_1.json ~ save_10.json / memories.json / ai-config.json）

命令行方式：jre\bin\java -Dfile.encoding=UTF-8 -jar canglan.jar data saves 8080 web
"@
Set-Content -Path "$stage\README.txt" -Value $readme -Encoding Default

Write-Host "[5/5] 冒烟自测（Go AI 服务 + 后端探活 + 对话）+ 打包 zip"
$aiProc = Start-Process -FilePath "$stage\ai-service.exe" -ArgumentList "-port","8798","-saves","$stage\saves" -PassThru -WindowStyle Hidden
$proc = Start-Process -FilePath "$stage\jre\bin\java.exe" -ArgumentList "-Dfile.encoding=UTF-8","-Dcanglan.ai.url=http://127.0.0.1:8798","-jar","$stage\canglan.jar","$stage\data","$stage\saves","8799","$stage\web" -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 5
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8799/api/health" -TimeoutSec 5
    Write-Host "  后端探活：status=$($health.status) sessions=$($health.sessions)"
    if ($health.status -ne "ok") { throw "后端 status=$($health.status)" }
    $goHealth = Invoke-RestMethod -Uri "http://127.0.0.1:8798/health" -TimeoutSec 5
    Write-Host "  Go AI 服务探活：status=$($goHealth.status) llm=$($goHealth.llm)"
    if ($goHealth.status -ne "ok") { throw "Go AI 服务不可用" }
    $chatBody = '{"npcId":"npc_village_chief","npcName":"村长","playerName":"旅人","utterance":"你好，村长！","tags":[],"memory":[]}'
    $chat = Invoke-RestMethod -Uri "http://127.0.0.1:8798/api/ai/chat" -Method Post -ContentType "application/json; charset=utf-8" -Body $chatBody -TimeoutSec 5
    Write-Host "  Go AI 对话：source=$($chat.source) reply=$($chat.reply.Substring(0, [Math]::Min(20, $chat.reply.Length)))…"
    if ([string]::IsNullOrWhiteSpace($chat.reply)) { throw "Go AI 对话返回空回复" }
} catch {
    throw "发行包自测失败：$($_.Exception.Message)"
} finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $aiProc.Id -Force -ErrorAction SilentlyContinue
}
if (Test-Path "$dist\canglan-trpg-win-x64.zip") { Remove-Item "$dist\canglan-trpg-win-x64.zip" }
Compress-Archive -Path $stage -DestinationPath "$dist\canglan-trpg-win-x64.zip"
$zip = Get-Item "$dist\canglan-trpg-win-x64.zip"
Write-Host "完成：$($zip.FullName)（$([math]::Round($zip.Length/1MB, 2)) MB）"
