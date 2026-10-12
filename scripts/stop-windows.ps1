<#
.SYNOPSIS
  停止由 start-windows.ps1 启动的 Lumen 后端（只结束本项目记录的进程及其子进程）。
#>
$Root = Split-Path -Parent $PSScriptRoot
$PidFile = Join-Path $Root 'data\run\backend.pid'
if (-not (Test-Path $PidFile)) { Write-Host '没有找到运行记录（data\run\backend.pid），服务可能未在运行。'; exit 0 }
$procId = [int](Get-Content $PidFile)
$p = Get-Process -Id $procId -ErrorAction SilentlyContinue
if (-not $p) { Write-Host "进程 $procId 已不存在。"; Remove-Item $PidFile; exit 0 }
# the recorded PID is the cmd.exe wrapper; stop the whole tree bottom-up (java, then any running FFmpeg job)
function Stop-Tree([int]$Id) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$Id" -ErrorAction SilentlyContinue | ForEach-Object { Stop-Tree $_.ProcessId }
    Stop-Process -Id $Id -Force -ErrorAction SilentlyContinue
}
Stop-Tree $procId
Remove-Item $PidFile
Write-Host "已停止 Lumen 后端（PID $procId）。" -ForegroundColor Green
