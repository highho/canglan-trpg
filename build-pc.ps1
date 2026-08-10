# build-pc.ps1 — 苍岚大陆 PC（Windows x64）独立发行包构建。
# 产物 dist/canglan-trpg-win-x64.zip：内置精简 JRE（jlink），双击 启动.bat 即玩。
# 模块说明：java.base（核心）+ java.logging（JDK 内部依赖）+ java.net.http（AI 客户端 HttpClient）。
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

Write-Host "[4/5] 启动脚本与说明"
$bat = @"
@echo off
cd /d "%~dp0"
start "" /b jre\bin\javaw -Dfile.encoding=UTF-8 -jar canglan.jar data saves 8080 web
timeout /t 2 /nobreak >nul
start "" http://127.0.0.1:8080/
"@
Set-Content -Path "$stage\启动.bat" -Value $bat -Encoding Default
$readme = @"
苍岚大陆（Windows x64 独立版）

运行：双击「启动.bat」，自动打开浏览器进入游戏（http://127.0.0.1:8080）。
关闭游戏：在任务管理器结束 javaw 进程，或关闭命令行窗口（若以 java 前台方式运行）。

目录说明：
  jre\    内置精简 JRE（无需安装 Java）
  data\   游戏数据（21 个 JSON）
  web\    纯文字前端
  saves\  存档（运行时生成，save_1.json ~ save_10.json）

命令行方式：jre\bin\java -Dfile.encoding=UTF-8 -jar canglan.jar data saves 8080 web
"@
Set-Content -Path "$stage\README.txt" -Value $readme -Encoding Default

Write-Host "[5/5] 冒烟自测（启动 5 秒探活后关闭）+ 打包 zip"
$proc = Start-Process -FilePath "$stage\jre\bin\java.exe" -ArgumentList "-Dfile.encoding=UTF-8","-jar","$stage\canglan.jar","$stage\data","$stage\saves","8799","$stage\web" -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 5
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8799/api/health" -TimeoutSec 5
    Write-Host "  探活成功：status=$($health.status) sessions=$($health.sessions)"
} catch {
    throw "发行包自测失败：/api/health 无响应"
} finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}
if (Test-Path "$dist\canglan-trpg-win-x64.zip") { Remove-Item "$dist\canglan-trpg-win-x64.zip" }
Compress-Archive -Path $stage -DestinationPath "$dist\canglan-trpg-win-x64.zip"
$zip = Get-Item "$dist\canglan-trpg-win-x64.zip"
Write-Host "完成：$($zip.FullName)（$([math]::Round($zip.Length/1MB, 2)) MB）"
