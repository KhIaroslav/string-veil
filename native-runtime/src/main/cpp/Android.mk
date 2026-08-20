LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := string_veil_native
LOCAL_SRC_FILES := native_decoder.cpp
LOCAL_CPPFLAGS := -std=c++17 -O3 -fvisibility=hidden -fvisibility-inlines-hidden -fno-exceptions -fno-rtti
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--exclude-libs,ALL
include $(BUILD_SHARED_LIBRARY)
