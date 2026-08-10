# build-android.ps1 — 苍岚大陆 Android 单机 APK 纯命令行构建（无 Gradle/Maven）。
# 流程：javac(六模块 main + android-app) → d8 → aapt2 → 组装 → zipalign → apksigner。
# 全程经 C:\canglan-trpg 目录联接（ASCII 路径）执行，规避 aapt2 中文路径问题。
$ErrorActionPreference = "Stop"

$origin = "e:\studyprogram\sikiedu\Unity\C#\CSharp编程第七季\CSharp编程第七季代码\Test"
$root = "C:\canglan-trpg"
if (-not (Test-Path $root)) {
    New-Item -ItemType Junction $root -Target $origin | Out-Null
}

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$buildTools = "$sdk\build-tools\36.1.0"
$androidJar = "$sdk\platforms\android-34\android.jar"
foreach ($p in @($buildTools, $androidJar)) {
    if (-not (Test-Path $p)) { throw "缺少 Android SDK 组件：$p" }
}

$work = "$root\build-android"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory $work | Out-Null

Write-Host "[1/7] 收集源码（六模块 main + android-app，排除测试）"
$srcDirs = @(
    "$root\canglan-backend\canglan-core\src\main",
    "$root\canglan-backend\canglan-data\src\main",
    "$root\canglan-backend\canglan-world\src\main",
    "$root\canglan-backend\canglan-save\src\main",
    "$root\canglan-backend\canglan-ai-client\src\main",
    "$root\canglan-backend\canglan-api\src\main",
    "$root\canglan-backend\android-app\src"
)
Get-ChildItem -Recurse -Filter *.java -Path $srcDirs | ForEach-Object { $_.FullName } |
    Set-Content "$work\sources.txt" -Encoding utf8
$count = (Get-Content "$work\sources.txt" | Measure-Object -Line).Lines
Write-Host "  源文件 $count 个"

Write-Host "[2/7] javac 编译（-source 17，classpath=android.jar）"
& javac -source 17 -target 17 -encoding UTF-8 -classpath $androidJar -d "$work\classes" "@$work\sources.txt" 2>&1 |
    ForEach-Object { if ($_ -match "error") { Write-Host $_ } }
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

Write-Host "[3/7] d8 dex（--min-api 30，输入为 jar 避免参数超长；输出目录须预先存在）"
& jar --create --file "$work\classes.jar" -C "$work\classes" . 2>&1 | Out-Null
New-Item -ItemType Directory "$work\dex" | Out-Null
& "$buildTools\d8.bat" --release --min-api 30 --lib $androidJar --output "$work\dex" "$work\classes.jar" 2>&1 |
    ForEach-Object { if ($_ -match "Error|Exception") { Write-Host $_ } }
if (-not (Test-Path "$work\dex\classes.dex")) { throw "d8 未产出 classes.dex" }

Write-Host "[4/7] 准备 assets（data + 前端 dist，排除存档残留）"
Copy-Item -Recurse "$root\data" "$work\assets\data"
if (Test-Path "$work\assets\data\saves") { Remove-Item -Recurse -Force "$work\assets\data\saves" }
Copy-Item -Recurse "$root\frontend\dist" "$work\assets\web"

Write-Host "[5/7] aapt2 资源编译与链接"
& "$buildTools\aapt2.exe" compile --dir "$root\canglan-backend\android-app\res" -o "$work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }
& "$buildTools\aapt2.exe" link -o "$work\base.apk" -I $androidJar `
    --manifest "$root\canglan-backend\android-app\AndroidManifest.xml" `
    --min-sdk-version 30 --target-sdk-version 34 "$work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "[6/7] 组装 APK（基座 + classes.dex + assets）"
# 基座 base.apk（aapt2 link 产出）已含二进制 AndroidManifest.xml 与未压缩 resources.arsc；
# 用 jar uf 追加条目（dex/assets 可压缩），避免 Compress-Archive 误压 resources.arsc 导致安装失败。
Copy-Item "$work\base.apk" "$work\unsigned.apk" -Force
& jar uf "$work\unsigned.apk" -C "$work\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar uf 追加 classes.dex 失败" }
& jar uf "$work\unsigned.apk" -C "$work" assets
if ($LASTEXITCODE -ne 0) { throw "jar uf 追加 assets 失败" }
& "$buildTools\zipalign.exe" -f 4 "$work\unsigned.apk" "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "[7/7] 签名（debug keystore 首次自动生成）"
$ks = "$root\canglan-backend\android-app\debug.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias androiddebugkey -storepass android -keypass android `
        -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 2>&1 | Out-Null
}
New-Item -ItemType Directory "$root\dist" -Force | Out-Null
& "$buildTools\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$root\dist\canglan-trpg-android.apk" "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败" }

$apk = Get-Item "$root\dist\canglan-trpg-android.apk"
Write-Host "完成：$($apk.FullName)（$([math]::Round($apk.Length/1MB, 2)) MB）"
