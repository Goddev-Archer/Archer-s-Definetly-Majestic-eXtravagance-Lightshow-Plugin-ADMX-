@echo off
title ADMX Studio Desktop Client (Tauri 2)
cd /d "%~dp0tools\admx-studio-tauri"
echo ========================================================
echo  ADMX Studio - Standalone Multi-Pass Auto-DMX & Flowboard
echo ========================================================
echo.
call npm run tauri dev
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Ein Fehler ist aufgetreten oder Node/Rust wurde nicht gefunden.
    pause
)
