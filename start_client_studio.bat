@echo off
title ADMX Studio Desktop Client
cd /d "%~dp0\tools\admx-studio"
echo ========================================================
echo  ADMX Studio - Standalone Multi-Pass Auto-DMX & Flowboard
echo ========================================================
echo.
python admx_studio_app.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Ein Fehler ist aufgetreten oder Python wurde nicht gefunden.
    pause
)
