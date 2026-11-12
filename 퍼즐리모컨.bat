@echo off
chcp 949 >nul
cd /d "%~dp0"
echo 퍼즐 리모컨을 켜는 중입니다. 이 창은 닫아도 됩니다.
start "퍼즐 리모컨 서버" /min node tools\remote\server.mjs
timeout /t 2 /nobreak >nul
start "" msedge --app=http://127.0.0.1:8790 --window-size=480,1040 --window-position=1440,0
if errorlevel 1 start "" http://127.0.0.1:8790
