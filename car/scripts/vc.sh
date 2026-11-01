#!/usr/bin/env bash
# Virtual Console helpers for trying the game on the Pleos emulator.
# The console only passes values to the car while a browser session is alive, so `vc session` keeps one open.
# usage: bash vc.sh <cmd> [args]   e.g. bash vc.sh driver true; bash vc.sh fan 2; bash vc.sh dir driver 2
VC="http://127.0.0.1:8088"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
"$ADB" forward tcp:8088 tcp:8088 >/dev/null
curl -s -m 2 -o /dev/null -X POST "$VC/browser/session?client=escape-car"
put() { curl -s -m 5 -o /dev/null -X PUT "$VC/vehicle/$1"; }
case "$1" in
  session) while true; do curl -s -m 2 -o /dev/null -X POST "$VC/browser/session?client=escape-car"; sleep 1; done ;;
  driver) put "seat?kind=occupied&area=driver&value=$2" ;;
  gear) put "gear?gear=$2" ;;                       # Park, Drive
  door) put "body?door_$2=$3" ;;                    # fl|fr|rl|rr true|false
  power) put "hvac?power=$2" ;;
  recirc) put "hvac?recirc=$2" ;;
  fan) put "hvac?fan_speed=$2" ;;
  dir) put "hvac?$2_direction=$3" ;;                # driver|passenger 1=face 2=floor
  dual) put "hvac?dual=$2" ;;
  temp) put "hvac?$2_temp=$3" ;;                    # driver|passenger 16..30
  defrost) put "hvac?front_defroster=$2" ;;
  light) put "reading-lights?area=$2&value=$3" ;;   # front_left|front_right|rear_left|rear_right
  *) echo "unknown $1" ;;
esac
