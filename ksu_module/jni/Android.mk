LOCAL_PATH := $(call my-dir)

# ---- Zygisk module (.so) ----
include $(CLEAR_VARS)
LOCAL_MODULE := zygisk
LOCAL_SRC_FILES := module.cpp
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2 -fvisibility=hidden
LOCAL_CPPFLAGS := -std=c++17
include $(BUILD_SHARED_LIBRARY)

# ---- pcmic-daemon (executable) ----
include $(CLEAR_VARS)
LOCAL_MODULE := pcmic-daemon
LOCAL_SRC_FILES := daemon.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2
include $(BUILD_EXECUTABLE)
