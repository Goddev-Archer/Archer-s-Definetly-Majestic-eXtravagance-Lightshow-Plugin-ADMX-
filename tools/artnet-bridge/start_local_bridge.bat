@echo off
title ADMX Art-Net Bridge
echo =========================================================================
echo   ADMX Art-Net WebSocket-zu-UDP Bridge (Lokaler Test)
echo =========================================================================
echo.
python "%~dp0admx_artnet_client.py" --server ws://127.0.0.1:8765 --token local-dev-artnet-token
pause
