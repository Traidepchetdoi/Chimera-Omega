package com.omega.host;

import android.content.Intent;

// [OMEGA STATIC VAULT] Két sắt chứa Token, bypass hoàn toàn lỗi IPC Serialize
public class MediaProjectionVault {
    public static volatile int resultCode = -1;
    public static volatile Intent data = null;
    public static volatile boolean isServiceReady = false;

    public static void store(int code, Intent intent) {
        resultCode = code;
        data = intent;
        isServiceReady = false;
    }

    public static void clear() {
        resultCode = -1;
        data = null;
    }
}