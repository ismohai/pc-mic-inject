@echo off
chcp 65001 >nul 2>&1
title PC Mic Inject - 电脑端推流工具
echo ==========================================
echo   PC Mic Inject - 虚拟麦克风推流工具
echo ==========================================
echo.

:: Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Python，请先安装 Python 3.8+
    echo 下载地址: https://www.python.org/downloads/
    pause
    exit /b 1
)

:: Install dependencies
echo [信息] 检查依赖...
pip install sounddevice >nul 2>&1

:: Run
echo [信息] 启动推流工具...
echo.
cd /d "%~dp0pc_audio_streamer"
python streamer_ui.py
if errorlevel 1 (
    echo.
    echo [错误] 程序异常退出
    pause
)
