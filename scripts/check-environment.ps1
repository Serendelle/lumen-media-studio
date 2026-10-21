<#
.SYNOPSIS
  检查 Lumen 流明智媒在本机运行所需的全部依赖（只读，不修改任何系统设置）。
.DESCRIPTION
  必需：JDK 17+、Node.js 18+、FFmpeg/FFprobe、ExifTool
  可选：ImageMagick（图片/PDF）、Ghostscript（PDF）、LibreOffice（Word/Excel 转 PDF）、Chrome/Edge（HTML 转 PDF）、rembg（AI 抠图）、DeepSeek Key（智能模式）
  退出码：0 = 必需项齐全；1 = 缺少必需项。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\check-environment.ps1
#>
param([switch]$Quiet)

$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
$script:Missing = 0
$script:Warn = 0

# Honor the same explicit tool paths as the backend, including project-local installs.
$script:ToolPaths = @{}
$toolKeys = @{
    'ffmpeg' = 'FFMPEG_PATH'; 'ffprobe' = 'FFPROBE_PATH'; 'exiftool' = 'EXIFTOOL_PATH'
    'magick' = 'MAGICK_PATH'; 'gswin64c' = 'GHOSTSCRIPT_PATH'; 'soffice.com' = 'SOFFICE_PATH'
    'rembg' = 'REMBG_PATH'; 'ocr-python' = 'OCR_PYTHON'
}
$localConfig = @{}
$configFile = Join-Path $Root '.env'
if (Test-Path $configFile) {
    foreach ($line in [System.IO.File]::ReadAllLines($configFile)) {
        if ($line -match '^\s*([A-Z_]+)\s*=(.*)$' -and $toolKeys.ContainsValue($Matches[1])) {
            $localConfig[$Matches[1]] = $Matches[2].Trim()
        }
    }
}
foreach ($tool in $toolKeys.Keys) {
    $value = [Environment]::GetEnvironmentVariable($toolKeys[$tool])
    if ([string]::IsNullOrWhiteSpace($value)) { $value = $localConfig[$toolKeys[$tool]] }
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        if (-not [System.IO.Path]::IsPathRooted($value)) { $value = Join-Path $Root $value }
        $script:ToolPaths[$tool] = $value
    }
}

function Write-Row([string]$Name, [string]$Status, [string]$Detail, [string]$Level) {
    if ($Quiet -and $Level -eq 'ok') { return }
    $color = switch ($Level) { 'ok' { 'Green' } 'warn' { 'Yellow' } default { 'Red' } }
    $mark = switch ($Level) { 'ok' { '[OK]  ' } 'warn' { '[!!]  ' } default { '[XX]  ' } }
    Write-Host ($mark + $Name.PadRight(14)) -ForegroundColor $color -NoNewline
    Write-Host (' ' + $Status.PadRight(18) + ' ' + $Detail)
}

function Find-Exe([string]$Name, [string[]]$Fallbacks) {
    if ($script:ToolPaths.ContainsKey($Name)) {
        $configured = $script:ToolPaths[$Name]
        if (Test-Path -LiteralPath $configured -PathType Leaf) { return $configured }
        return $null
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    foreach ($pattern in $Fallbacks) {
        $hit = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

function Get-NativeOutput([string]$Exe, [string[]]$ArgList) {
    # native tools often print versions to stderr; capture both without PowerShell 5.1 ErrorRecord noise
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($ArgList -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    try {
        $p = [System.Diagnostics.Process]::Start($psi)
        $out = $p.StandardOutput.ReadToEnd() + $p.StandardError.ReadToEnd()
        $p.WaitForExit(15000) | Out-Null
        return $out
    } catch { return '' }
}

$LocalAppData = $env:LOCALAPPDATA
if (-not $Quiet) {
    Write-Host ''
    Write-Host 'Lumen 流明智媒 · 环境检查' -ForegroundColor Cyan
    Write-Host ('-' * 64)
}

# ---------------- required ----------------
$java = Find-Exe 'java' @("$env:JAVA_HOME\bin\java.exe", 'C:\Program Files\Eclipse Adoptium\*\bin\java.exe', 'C:\Program Files\Java\*\bin\java.exe', 'C:\Program Files\Microsoft\jdk-*\bin\java.exe')
if ($java) {
    $v = Get-NativeOutput $java @('-version')
    if ($v -match 'version "(\d+)') {
        $major = [int]$Matches[1]
        if ($major -ge 17) { Write-Row 'Java' "JDK $major" $java 'ok' }
        else { Write-Row 'Java' "JDK $major (需要 17+)" '安装：winget install EclipseAdoptium.Temurin.21.JDK' 'err'; $script:Missing++ }
    } else { Write-Row 'Java' '无法识别版本' $java 'warn'; $script:Warn++ }
} else { Write-Row 'Java' '未安装' '安装：winget install EclipseAdoptium.Temurin.21.JDK' 'err'; $script:Missing++ }

$node = Find-Exe 'node' @('C:\Program Files\nodejs\node.exe', "$env:NVM_SYMLINK\node.exe")
if ($node) {
    $nv = (Get-NativeOutput $node @('-v')).Trim()
    if ($nv -match '^v(\d+)') {
        $nmajor = [int]$Matches[1]
        if ($nmajor -ge 18) { Write-Row 'Node.js' $nv $node 'ok' }
        else { Write-Row 'Node.js' "$nv (需要 18+)" '安装：winget install OpenJS.NodeJS.LTS' 'err'; $script:Missing++ }
    }
} else {
    $dist = Join-Path $Root 'frontend\dist\index.html'
    if (Test-Path $dist) { Write-Row 'Node.js' '未安装' '已有构建好的前端，可直接运行；修改前端需安装 Node' 'warn'; $script:Warn++ }
    else { Write-Row 'Node.js' '未安装' '安装：winget install OpenJS.NodeJS.LTS（首次构建前端需要）' 'err'; $script:Missing++ }
}

$ffmpegFallback = @("$LocalAppData\Microsoft\WinGet\Packages\Gyan.FFmpeg*\ffmpeg-*\bin\ffmpeg.exe", "$LocalAppData\Microsoft\WinGet\Links\ffmpeg.exe")
$ffprobeFallback = @("$LocalAppData\Microsoft\WinGet\Packages\Gyan.FFmpeg*\ffmpeg-*\bin\ffprobe.exe", "$LocalAppData\Microsoft\WinGet\Links\ffprobe.exe")
$ffmpeg = Find-Exe 'ffmpeg' $ffmpegFallback
$ffprobe = Find-Exe 'ffprobe' $ffprobeFallback
if ($ffmpeg -and $ffprobe) {
    $fv = (Get-NativeOutput $ffmpeg @('-hide_banner', '-version')) -split "`n" | Select-Object -First 1
    Write-Row 'FFmpeg' (($fv -replace 'ffmpeg version ', '') -split ' ' | Select-Object -First 1) $ffmpeg 'ok'
} else { Write-Row 'FFmpeg' '未安装' '安装：winget install Gyan.FFmpeg' 'err'; $script:Missing++ }

$exif = Find-Exe 'exiftool' @("$LocalAppData\Programs\ExifTool\ExifTool.exe", 'C:\Program Files\ExifTool\exiftool.exe')
if ($exif) { Write-Row 'ExifTool' ((Get-NativeOutput $exif @('-ver')).Trim()) $exif 'ok' }
else { Write-Row 'ExifTool' '未安装' '安装：winget install OliverBetz.ExifTool（元数据与隐私功能需要）' 'err'; $script:Missing++ }

# ---------------- optional ----------------
$magick = Find-Exe 'magick' @('C:\Program Files\ImageMagick-*\magick.exe')
if ($magick) { Write-Row 'ImageMagick' '可用' $magick 'ok' }
else { Write-Row 'ImageMagick' '未安装（可选）' '图片与 PDF 功能不可用；安装：winget install ImageMagick.ImageMagick' 'warn'; $script:Warn++ }

$gs = Find-Exe 'gswin64c' @('C:\Program Files\gs\*\bin\gswin64c.exe')
if (-not $gs) {
    $reg = Get-ChildItem 'HKLM:\SOFTWARE\GPL Ghostscript' -ErrorAction SilentlyContinue | ForEach-Object { (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).GS_DLL } | Select-Object -First 1
    if ($reg) { $cand = Join-Path (Split-Path $reg) 'gswin64c.exe'; if (Test-Path $cand) { $gs = $cand } }
}
if ($gs) { Write-Row 'Ghostscript' '可用' $gs 'ok' }
else { Write-Row 'Ghostscript' '未安装（可选）' 'PDF 功能不可用；从 ghostscript.com 或 GitHub ArtifexSoftware/ghostpdl-downloads 安装' 'warn'; $script:Warn++ }

$soffice = Find-Exe 'soffice.com' @('C:\Program Files\LibreOffice\program\soffice.com', 'D:\LibreOffice\program\soffice.com', 'D:\Program Files\LibreOffice\program\soffice.com')
if ($soffice) { Write-Row 'LibreOffice' '可用' $soffice 'ok' }
else { Write-Row 'LibreOffice' '未安装（可选）' 'Word/Excel 转 PDF 不可用；安装：winget install TheDocumentFoundation.LibreOffice（可加 --location D:\LibreOffice）' 'warn'; $script:Warn++ }

$browser = @('C:\Program Files\Google\Chrome\Application\chrome.exe', "$LocalAppData\Google\Chrome\Application\chrome.exe", 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe') | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($browser) { Write-Row 'Chrome / Edge' '可用' $browser 'ok' }
else { Write-Row 'Chrome / Edge' '未找到（可选）' 'HTML 转 PDF 不可用' 'warn'; $script:Warn++ }

$ocr = Find-Exe 'ocr-python' @('D:\LumenTools\ocr-venv\Scripts\python.exe', 'C:\LumenTools\ocr-venv\Scripts\python.exe', "$env:USERPROFILE\.lumen\ocr-venv\Scripts\python.exe")
if ($ocr) { Write-Row 'RapidOCR' '可用' $ocr 'ok' }
else { Write-Row 'RapidOCR' '未安装（可选）' '学习播放器无法识别画面文字；见 docs/deployment.md 第 10 节' 'warn'; $script:Warn++ }

$rembg = Find-Exe 'rembg' @()
foreach ($base in @("$env:USERPROFILE\miniconda3", "$env:USERPROFILE\anaconda3", "$LocalAppData\miniconda3", 'C:\ProgramData\miniconda3')) {
    if ($rembg) { break }
    $cand = Join-Path $base 'envs\rembg-env\Scripts\rembg.exe'
    if (Test-Path $cand) { $rembg = $cand; break }
}
if (-not $rembg) { $rembg = Find-Exe 'rembg' @() }
if ($rembg) { Write-Row 'rembg' '可用' $rembg 'ok' }
else { Write-Row 'rembg' '未安装（可选）' 'AI 抠图不可用；见 docs/deployment.md 第 4 节' 'warn'; $script:Warn++ }

# ---------------- configuration (values are never printed) ----------------
$envFile = Join-Path $Root '.env'
$keyFromFile = $false
if (Test-Path $envFile) {
    $line = Select-String -Path $envFile -Pattern '^\s*DEEPSEEK_API_KEY\s*=\s*(\S+)' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($line -and $line.Matches[0].Groups[1].Value -notmatch 'your-key|change-me') { $keyFromFile = $true }
    Write-Row '.env' '存在' $envFile 'ok'
} else { Write-Row '.env' '不存在' '启动脚本会根据 .env.example 自动创建' 'warn'; $script:Warn++ }
$keyFromEnv = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY'))
if ($keyFromFile -or $keyFromEnv) {
    $src = if ($keyFromEnv) { '系统环境变量' } else { '.env' }
    Write-Row 'DeepSeek Key' '已配置' "来源：$src（值不显示）" 'ok'
} else { Write-Row 'DeepSeek Key' '未配置（可选）' '智能模式不可用；在 .env 中填写 DEEPSEEK_API_KEY' 'warn'; $script:Warn++ }
$asrFromFile = $false
if (Test-Path $envFile) {
    $line = Select-String -Path $envFile -Pattern '^\s*DASHSCOPE_API_KEY\s*=\s*(\S+)' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($line -and $line.Matches[0].Groups[1].Value -notmatch 'your-key|change-me') { $asrFromFile = $true }
}
$asrFromEnv = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY'))
if ($asrFromFile -or $asrFromEnv) { Write-Row '百炼 Key' '已配置' '学习播放器语音识别（值不显示）' 'ok' }
else { Write-Row '百炼 Key' '未配置（可选）' '学习播放器不可用；在 .env 中填写 DASHSCOPE_API_KEY' 'warn'; $script:Warn++ }

$busy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $owner = (Get-Process -Id $busy[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    Write-Row '端口 8080' '已被占用' "进程：$owner（若是本项目已在运行，可忽略）" 'warn'; $script:Warn++
} else { Write-Row '端口 8080' '空闲' '' 'ok' }

$drive = (Get-Item $Root).PSDrive
$freeGb = [math]::Round(($drive.Free / 1GB), 1)
if ($freeGb -ge 2) { Write-Row '磁盘空间' "$freeGb GB 可用" "$($drive.Name):" 'ok' }
else { Write-Row '磁盘空间' "$freeGb GB 可用" '建议至少 2GB 用于处理视频' 'warn'; $script:Warn++ }

if (-not $Quiet) {
    Write-Host ('-' * 64)
    if ($script:Missing -eq 0) { Write-Host "必需依赖齐全。提示 $($script:Warn) 项（不影响启动）。" -ForegroundColor Green }
    else { Write-Host "缺少 $($script:Missing) 项必需依赖，请按上面的提示安装后重试。" -ForegroundColor Red }
}
if ($script:Missing -gt 0) { exit 1 } else { exit 0 }
