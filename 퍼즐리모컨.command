#!/bin/bash
# macOS판 퍼즐리모컨.bat: Finder에서 더블클릭하면 리모컨 서버를 켜고 브라우저로 엽니다. 이 창을 닫으면 리모컨도 꺼집니다.
cd "$(dirname "$0")" || exit 1
node tools/remote/server.mjs &
sleep 2
open "http://127.0.0.1:8790"
wait
