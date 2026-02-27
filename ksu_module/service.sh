#!/system/bin/sh
# PcMic Module - Boot Service
MODDIR=${0%/*}
CONFIG="/data/adb/pcmic/config.properties"
PID_FILE="/data/adb/pcmic/daemon.pid"

# Wait for boot
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done
sleep 3

# Read config
ENABLED="true"
PC_PORT="9876"
if [ -f "$CONFIG" ]; then
  ENABLED=$(grep -E "^enabled=" "$CONFIG" | cut -d= -f2 | tr -d '[:space:]')
  PC_PORT=$(grep -E "^port=" "$CONFIG" | cut -d= -f2 | tr -d '[:space:]')
fi

if [ "$ENABLED" != "true" ]; then
  log -t PcMic "Disabled in config"
  exit 0
fi

# Kill old daemon using PID file
if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE")
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    kill "$OLD_PID" 2>/dev/null
    sleep 1
  fi
  rm -f "$PID_FILE"
fi
killall pcmic-daemon 2>/dev/null
sleep 1

# Start daemon
log -t PcMic "Starting daemon on port $PC_PORT"
$MODDIR/pcmic-daemon "$PC_PORT" &
