@echo off
title PC Mic Inject - PC Streamer
echo ==========================================
echo   PC Mic Inject - Audio Streamer
echo ==========================================
echo.

:: Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Please install Python 3.8+
    echo Download: https://www.python.org/downloads/
    pause
    exit /b 1
)

:: Install dependencies
echo [INFO] Installing dependencies...
pip install -q -r "%~dp0pc_audio_streamer\requirements.txt" >nul 2>&1

:: Run
echo [INFO] Starting streamer...
echo.
cd /d "%~dp0pc_audio_streamer"
python streamer_ui.py
if errorlevel 1 (
    echo.
    echo [ERROR] Program exited with an error.
    pause
)
