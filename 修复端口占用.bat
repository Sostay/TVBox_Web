@echo off
chcp 65001 >nul
title TVBox Web - 修复 Windows 系统端口占用
cd /d "%~dp0"

echo ========================================================
echo   TVBox Web - 修复 Windows 动态端口冲突 (8999 EACCES)
echo ========================================================
echo.

:: 检查是否具备管理员权限
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [*] 正在请求管理员权限以重启 Windows NAT 网络服务...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

echo [*] 正在重启 Windows WinNAT 服务以释放被保留的端口...
net stop winnat
net start winnat

echo.
echo [*] 正在将 Windows 动态端口段设置为国际标准段 (49152-65535)...
echo     (这可以永久防止以后开机 Hyper-V/WSL 再次随机霸占 8999 等端口)
netsh int ipv4 set dynamicport tcp start=49152 num=16384

echo.
echo ========================================================
echo  [√] 修复完成！端口 8999 已经成功释放。
echo      请关闭本窗口，重新双击 start.bat 即可正常使用！
echo ========================================================
echo.
pause
