@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem 用法：run_solo.bat [配置文件名] [HTTP端口]
rem   双击 / 不带参数                    → config-solo.ini / 8088（第一实例）
rem   run_solo.bat config-solo2.ini 8089 → 第二实例（模拟集群，或直接双击 run_solo2.bat）
rem 前台运行：关闭本窗口或按 Ctrl+C 即停止

set "CFG=%~1"
if "%CFG%"=="" set "CFG=config-solo.ini"
set "HTTP_PORT=%~2"
if "%HTTP_PORT%"=="" set "HTTP_PORT=8088"

if not exist "MediaServer.exe" (
    echo [ERROR] 当前目录没有 MediaServer.exe
    pause
    exit /b 1
)
if not exist "%CFG%" (
    echo [ERROR] 当前目录没有 %CFG%
    pause
    exit /b 1
)

netstat -ano | findstr /r /c:":%HTTP_PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [WARN] 端口 %HTTP_PORT% 已在监听，可能已有 MediaServer.exe 在运行。
    echo        继续启动会因端口冲突失败；如需重启请先在任务管理器结束 MediaServer.exe。
    echo.
)

echo ==============================================
echo  ZLMediaKit   config=%CFG%   log=1
echo  HTTP API     http://127.0.0.1:%HTTP_PORT%
echo  关闭本窗口或按 Ctrl+C 停止
echo ==============================================
echo.

MediaServer.exe -c "%CFG%" -l 1
set "RC=%ERRORLEVEL%"

echo.
echo ZLMediaKit 已退出，exit code = %RC%
pause
