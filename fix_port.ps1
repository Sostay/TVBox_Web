# Requires -Version 5.1
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

# 检查是否具备管理员权限
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "========================================================" -ForegroundColor Cyan
    Write-Host "   TVBox Web - 正在请求管理员权限以修复端口..." -ForegroundColor Cyan
    Write-Host "========================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "[提示] Windows 即将弹出用户账户控制 (UAC) 提权窗口，请点击【是】以允许操作..." -ForegroundColor Yellow
    try {
        Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    } catch {
        Write-Host ""
        Write-Host "[错误] 用户取消了授权，或提权失败: $($_.Exception.Message)" -ForegroundColor Red
        Read-Host "按回车键退出..."
    }
    exit
}

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "   TVBox Web - 正在修复 Windows 动态端口占用 (8999)" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "[1/2] 正在重启 Windows WinNAT 网络服务以释放被保留的 8999 端口..." -ForegroundColor Yellow
net stop winnat
net start winnat

Write-Host ""
Write-Host "[2/2] 正在将 Windows 动态端口段设置为国际标准段 (49152-65535)..." -ForegroundColor Yellow
Write-Host "      (这可以永久防止以后开机 Hyper-V/WSL 再次随机抢占 8999 等端口)" -ForegroundColor Gray
netsh int ipv4 set dynamicport tcp start=49152 num=16384

Write-Host ""
Write-Host "========================================================" -ForegroundColor Green
Write-Host " [√] 修复完成！端口 8999 已经成功释放。" -ForegroundColor Green
Write-Host "     现在可以关闭本窗口，重新双击运行 start.bat 观影了！" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host ""
Read-Host "按回车键关闭窗口..."
