@echo off
title TVBox Web 影视聚合中心
cd /d "%~dp0"

:: 1. 检查是否存在本地便携版 Node
set "NODE_CMD=node"
if exist "%~dp0bin\node.exe" (
    set "NODE_CMD=%~dp0bin\node.exe"
    goto NODE_READY
)

:: 2. 检查全局 Node
where node >nul 2>nul
if %errorlevel% equ 0 goto NODE_READY

:: 3. 提示安装
echo ========================================================
echo  【提示】运行 TVBox Web 需要 Node.js 环境（推荐 v18 或以上）
echo ========================================================
echo.
echo  检测到当前电脑尚未配置 Node.js 运行环境。
echo  您可以通过以下方式快速配置：
echo.
echo  【方式一】访问 Node.js 官网下载 LTS 安装包: https://nodejs.org
echo  【方式二】针对 Windows 10/11，可直接在下方按 Y 自动安装
echo.
echo ========================================================
set /p USER_CHOICE="是否尝试通过 Windows 官方 winget 自动安装? (Y/N): "
if /i "%USER_CHOICE%"=="Y" goto DO_INSTALL
goto EXIT_FAIL

:DO_INSTALL
echo.
echo 正在自动下载并安装 Node.js LTS，请稍候...
winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements
if %errorlevel% equ 0 (
    echo.
    echo 安装完成！请关闭此窗口并重新双击 start.bat 即可。
    pause
    exit /b 0
)
echo 自动安装遇到问题，建议前往 https://nodejs.org 手动下载安装。
pause
exit /b 1

:EXIT_FAIL
echo.
echo 请安装 Node.js 后再次运行 start.bat。
pause
exit /b 1

:NODE_READY
echo ========================================================
echo          TVBox Web 跨平台双引擎影视端 正在启动...
echo ========================================================
echo.
echo  * 网页观影主页: http://127.0.0.1:8999
echo  * 网盘扫码配置: http://127.0.0.1:8999/website
echo  * 局域网访问:   http://^<本机IP^>:8999
echo.
echo  服务启动中，正在为您打开浏览器...
timeout /t 2 /nobreak >nul
start "" http://127.0.0.1:8999
"%NODE_CMD%" gateway.js
pause
