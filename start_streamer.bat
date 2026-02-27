@echo off
chcp 65001 >nul
title PC Mic Inject
echo.
echo ========================================
echo   PC Mic Inject
echo ========================================
echo.
cd /d "%~dp0pc_audio_streamer"
echo Installing dependencies...
pip install -r requirements.txt -q
echo.
echo Press Ctrl+C to stop
echo ========================================
echo.
python audio_streamer.py
echo.
pause
