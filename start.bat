@echo off
chcp 65001 >nul
title TVBox Web 影视聚合服务
cd /d "%~dp0"

echo ========================================================
echo               TVBox Web 跨平台双引擎影视端
echo ========================================================
echo.

:: 1. 检查是否存在本地便携版 Node.js
set "NODE_CMD=node"
if exist "%~dp0bin\node.exe" (
    set "NODE_CMD=%~dp0bin\node.exe"
    echo [*] 使用本地内置便携版 Node.js
    goto CHECK_PORT
)

:: 2. 检查系统全局环境变量中是否存在 Node.js
where node >nul 2>nul
if %errorlevel% equ 0 (
    echo [*] 检测到系统全局已安装 Node.js
    goto CHECK_PORT
)

:: 3. 未检测到 Node.js 时的友好提示与安装指引
echo ========================================================
echo  【错误提示】未检测到 Node.js 运行环境！
echo ========================================================
echo.
echo  TVBox Web Windows 服务端依赖 Node.js (推荐 v18 或更高版本)。
echo  请通过以下方式之一准备环境：
echo.
echo  【推荐方案一】自动安装 (Windows 10/11)：
echo    直接按 Y 并回车，将通过微软官方包管理器自动安装。
echo.
echo  【常规方案二】手动官网下载：
echo    前往官网下载安装包: https://nodejs.org (下载 LTS 长期支持版)
echo.
echo  【便携方案三】绿色免安装版：
echo    将官方 node-v*-win-x64.zip 解压后的 node.exe 放入当前目录的 bin 文件夹
echo.
echo ========================================================
set /p USER_CHOICE="是否尝试通过 Windows 官方 winget 自动安装 Node.js? (Y/N): "
if /i "%USER_CHOICE%"=="Y" goto DO_INSTALL
goto EXIT_FAIL

:DO_INSTALL
echo.
echo [*] 正在调用 Windows winget 自动安装 Node.js LTS，请稍候...
winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements
if %errorlevel% equ 0 (
    echo.
    echo [√] Node.js 安装完成！请关闭此窗口并重新双击运行 start.bat。
    echo.
    pause
    exit /b 0
)
echo.
echo [!] 自动安装失败（可能系统未启用 winget），请前往官网手动下载安装:
echo     https://nodejs.org
echo.
pause
exit /b 1

:EXIT_FAIL
echo.
echo 请安装 Node.js 后再次双击运行 start.bat。
echo.
pause
exit /b 1

:CHECK_PORT
:: 4. 检查 8999 端口是否已被占用
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c = Get-NetTCPConnection -LocalPort 8999 -State Listen -ErrorAction SilentlyContinue; if ($c) { exit 10 } else { exit 0 }" >nul 2>nul
if %errorlevel% equ 10 (
    echo.
    echo [提示] 服务端口 8999 已处于监听状态，TVBox Web 可能已在运行中！
    echo        * Web 观影地址: http://127.0.0.1:8999
    echo        * 如需重启服务，请先双击运行 stop.bat
    echo.
)

:: 5. 打印就绪信息并打开浏览器
echo.
echo [*] 正在启动统一服务网关 (端口: 8999)...
echo.
echo  ======================================================
echo    * Web 观影主页:     http://127.0.0.1:8999
echo    * 网盘扫码配置中心: http://127.0.0.1:8999/website
echo    * 局域网其他设备:   http://局域网IP:8999
echo  ======================================================
echo.
echo [*] 正在调起默认浏览器打开观影界面...
start "" "http://127.0.0.1:8999"

:: 6. 运行 Node 网关并保持窗口
echo [*] 正在启动服务引擎（关闭此黑框将停止服务，静默运行请双击 start_silent.bat）...
echo.
"%NODE_CMD%" gateway.js

:: 7. 若 Node 进程异常退出，保持窗口让用户看清错误信息
echo.
echo ========================================================
echo  [提示] TVBox Web 服务已退出。
echo ========================================================
echo.
pause
