#!/system/bin/sh
# PcMic Module - Uninstall cleanup
rm -rf /data/adb/pcmic
killall pcmic-daemon 2>/dev/null
