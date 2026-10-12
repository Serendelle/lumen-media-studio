<#
.SYNOPSIS
  一键启动 Lumen 流明智媒（Windows 10 / 11）。
.DESCRIPTION
  1. 检查依赖（scripts\check-environment.ps1），缺少必需项时停止并给出安装提示
  2. 首次运行时根据 .env.example 创建 .env，并生成随机 JWT 密钥（不会显示任何密钥值）
  3. 按需构建前端（npm）和后端（Maven Wrapper，无需另装 Maven）
  4. 后台启动后端（同时提供前端页面），等待健康检查通过后打开浏览器
  不会修改系统设置、环境变量或已安装的软件。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\start-windows.ps1
  powershell -ExecutionPolicy Bypass -File scripts\start-windows.ps1 -Rebuild -NoBrowser
#>
param(
    [int]$Port = 8080,
    [switch]$Rebuild,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$DataDir = Join-Path $Root 'data'
$LogDir = Join-Path $DataDir 'logs'
$RunDir = Join-Path $DataDir 'run'
$PidFile = Join-Path $RunDir 'backend.pid'
New-Item -ItemType Directory -Force -Path $LogDir, $RunDir | Out-Null

function Step([string]$Text) { Write-Host ''; Write-Host "==> $Text" -ForegroundColor Cyan }
function Fail([string]$Text) { Write-Host ''; Write-Host "启动失败：$Text" -ForegroundColor Red; exit 1 }
function Mask([string]$Text) { return ($Text -replace 'sk-[A-Za-z0-9]{8,}', 'sk-***') }

# ---------------------------------------------------------------- 1. dependencies
Step '检查运行环境'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'check-environment.ps1')
if ($LASTEXITCODE -ne 0) { Fail '缺少必需依赖（见上表）。安装后重新运行本脚本。' }

# ---------------------------------------------------------------- 2. .env
Step '检查配置文件 .env'
$envFile = Join-Path $Root '.env'
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $Root '.env.example') $envFile
    Write-Host '已根据 .env.example 创建 .env（该文件不会被提交到 Git）。'
}
$envText = [System.IO.File]::ReadAllText($envFile, [System.Text.Encoding]::UTF8)
if ($envText -match '(?m)^\s*APP_JWT_SECRET\s*=\s*(change-me[^\r\n]*)?\s*$') {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    $envText = [regex]::Replace($envText, '(?m)^\s*APP_JWT_SECRET\s*=.*$', "APP_JWT_SECRET=$secret")
    [System.IO.File]::WriteAllText($envFile, $envText, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host '已生成随机登录密钥 APP_JWT_SECRET（值不显示）。'
}
$hasKey = ($envText -match '(?m)^\s*DEEPSEEK_API_KEY\s*=\s*sk-(?!your-key)\S+') -or
          (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY')))
if (-not $hasKey) {
    Write-Host '提示：未配置 DEEPSEEK_API_KEY，智能模式将显示「未配置」，专业模式不受影响。' -ForegroundColor Yellow
    Write-Host '      用记事本打开 .env，把 DEEPSEEK_API_KEY= 后面换成你的 Key，然后重新运行本脚本。' -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 3. build
$dist = Join-Path $Root 'frontend\dist\index.html'
if ($Rebuild -or -not (Test-Path $dist)) {
    Step '构建前端（首次约 1 分钟）'
    Push-Location (Join-Path $Root 'frontend')
    try {
        if ($Rebuild -or -not (Test-Path 'node_modules')) {
            & npm ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { Fail 'npm ci 失败（检查网络，或设置 npm 镜像：npm config set registry https://registry.npmmirror.com）' }
        }
        & npm run build
        if ($LASTEXITCODE -ne 0) { Fail '前端构建失败（见上方输出）' }
    } finally { Pop-Location }
} else { Write-Host '前端已构建，跳过（需要重新构建请加 -Rebuild）。' }

$jar = Join-Path $Root 'backend\target\video-compressor-backend-1.0.0.jar'
if ($Rebuild -or -not (Test-Path $jar)) {
    Step '构建后端（首次需要下载依赖，约 2-5 分钟）'
    Push-Location (Join-Path $Root 'backend')
    try {
        & .\mvnw.cmd -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) { Fail '后端构建失败（见上方输出；需要能访问 Maven 中央仓库）' }
    } finally { Pop-Location }
} else { Write-Host '后端已构建，跳过（需要重新构建请加 -Rebuild）。' }

# ---------------------------------------------------------------- 4. start
Step "启动服务（端口 $Port）"
if (Test-Path $PidFile) {
    $old = Get-Content $PidFile -ErrorAction SilentlyContinue
    if ($old -and (Get-Process -Id $old -ErrorAction SilentlyContinue)) {
        Write-Host "检测到已在运行（PID $old）。如需重启，请先运行 scripts\stop-windows.ps1。" -ForegroundColor Yellow
        if (-not $NoBrowser) { Start-Process "http://localhost:$Port" }
        exit 0
    }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Fail "端口 $Port 已被其他程序占用。关闭该程序，或使用 -Port 指定其他端口（例如 -Port 8090）。"
}

$java = (Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $java) { Fail '未找到 java' }
$outLog = Join-Path $LogDir 'backend.out.log'
$errLog = Join-Path $LogDir 'backend.err.log'
# Win32_Process.Create does not inherit this console's handles, so the script returns immediately even when its
# output is piped (Start-Process with redirection would keep the caller's pipe open for the life of the server).
$cmdLine = "cmd.exe /c `"`"$java`" -Dfile.encoding=UTF-8 -jar `"$jar`" --server.port=$Port 1> `"$outLog`" 2> `"$errLog`"`""
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmdLine; CurrentDirectory = $Root }
if ($created.ReturnValue -ne 0) { Fail "无法启动 Java 进程（Win32 错误 $($created.ReturnValue)）" }
$procId = [int]$created.ProcessId
Set-Content -Path $PidFile -Value $procId

Write-Host '等待服务就绪' -NoNewline
$ready = $false
$health = $null
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 1
    if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) { break }
    try {
        # Invoke-RestMethod + Accept json: PowerShell 5.1 returns raw bytes for the actuator media type otherwise
        $health = Invoke-RestMethod -UseBasicParsing -Uri "http://localhost:$Port/actuator/health" -Headers @{ Accept = 'application/json' } -TimeoutSec 3
        $ready = $true
        break
    } catch {
        # 503 means started but a core tool is DOWN: still report it below
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 503) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $health = $reader.ReadToEnd() | ConvertFrom-Json
            $ready = $true
            break
        }
        Write-Host '.' -NoNewline
    }
}
Write-Host ''

if (-not $ready) {
    Write-Host '后端未能启动，最近的日志：' -ForegroundColor Red
    foreach ($f in @($errLog, $outLog)) {
        if (Test-Path $f) { Get-Content $f -Tail 25 | ForEach-Object { Write-Host (Mask $_) } }
    }
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'stop-windows.ps1') | Out-Null
    Remove-Item $PidFile -ErrorAction SilentlyContinue
    Fail "服务在 120 秒内没有就绪。完整日志：$LogDir"
}

Write-Host ''
if ($health.status -eq 'UP') { Write-Host '服务已启动：状态 UP' -ForegroundColor Green }
else { Write-Host "服务已启动，但状态为 $($health.status)（某个核心工具不可用，见下表）" -ForegroundColor Yellow }
$tools = $health.components.mediaTools.details
if ($tools) {
    $tools.PSObject.Properties | ForEach-Object { Write-Host ('  ' + $_.Name.PadRight(14) + $_.Value) }
}
Write-Host ''
Write-Host "  访问地址：  http://localhost:$Port" -ForegroundColor Cyan
Write-Host "  API 文档：  http://localhost:$Port/swagger-ui.html"
Write-Host "  日志目录：  $LogDir"
Write-Host "  停止服务：  powershell -ExecutionPolicy Bypass -File scripts\stop-windows.ps1"
if (-not $NoBrowser) { Start-Process "http://localhost:$Port" }
exit 0
