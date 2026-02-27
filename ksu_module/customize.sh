#!/system/bin/sh
# PcMic Module - Installation Script
SKIPUNZIP=1

ui_print "==============================="
ui_print "  PC Mic Inject v1.0.0"
ui_print "  虚拟麦克风模块"
ui_print "==============================="

# Check architecture
ARCH=$(getprop ro.product.cpu.abi)
ui_print "- 设备架构: $ARCH"

if [ "$ARCH" != "arm64-v8a" ] && [ "$ARCH" != "armeabi-v7a" ]; then
  abort "! 不支持的架构: $ARCH"
fi

# Check Zygisk
if [ ! -d "/data/adb/modules/zygisksu" ] && [ ! -d "/data/adb/modules/zygisknext" ] && [ "$(magisk --path 2>/dev/null)" == "" ]; then
  ui_print "! 警告: 未检测到 Zygisk/ZygiskNext"
  ui_print "! 请确保已安装 ZygiskNext 模块"
fi

# Extract module files
ui_print "- 解压模块文件..."
unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2

# Set permissions
ui_print "- 设置权限..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/pcmic-daemon" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

# Create config directory
CONFIG_DIR="/data/adb/pcmic"
mkdir -p "$CONFIG_DIR"
if [ ! -f "$CONFIG_DIR/config.properties" ]; then
  cp "$MODPATH/pcmic.conf" "$CONFIG_DIR/config.properties"
  ui_print "- 已创建默认配置文件"
else
  ui_print "- 保留现有配置文件"
fi
set_perm_recursive "$CONFIG_DIR" 0 0 0755 0644

ui_print ""
ui_print "- 安装完成！"
ui_print "- 配置文件: /data/adb/pcmic/config.properties"
ui_print "- 请重启手机以生效"
ui_print "==============================="
