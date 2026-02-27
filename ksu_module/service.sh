#!/system/bin/sh
# PcMic Module - Boot Service (runs as root after boot)
MODDIR=${0%/*}
CONFIG="/data/adb/pcmic/config.properties"

# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done
sleep 5

# Read config
ENABLED="true"
PC_PORT="9876"
if [ -f "$CONFIG" ]; then
  ENABLED=$(grep -E "^enabled=" "$CONFIG" | cut -d= -f2 | tr -d '[:space:]')
  PC_PORT=$(grep -E "^port=" "$CONFIG" | cut -d= -f2 | tr -d '[:space:]')
fi

if [ "$ENABLED" != "true" ]; then
  log -t PcMic "Service disabled in config"
  exit 0
fi

# Kill existing daemon
killall pcmic-daemon 2>/dev/null

# Start daemon
log -t PcMic "Starting pcmic-daemon on port $PC_PORT"
$MODDIR/pcmic-daemon "$PC_PORT" &
