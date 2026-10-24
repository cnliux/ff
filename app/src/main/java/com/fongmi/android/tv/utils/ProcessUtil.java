package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;

import com.fongmi.android.tv.App;

import java.util.List;

public class ProcessUtil {

    private static volatile String cached;

    /**
     * 当前进程名。API 28+ 直接取 Application.getProcessName()，
     * 低版本遍历 ActivityManager 运行进程按 pid 匹配（minSdk=24 兼容）。
     */
    public static String getProcessName() {
        if (cached != null) return cached;
        String name = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) name = Application.getProcessName();
        if (TextUtils.isEmpty(name)) name = fromActivityManager();
        cached = name == null ? "" : name;
        return cached;
    }

    private static String fromActivityManager() {
        try {
            Context context = App.get();
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return null;
            int pid = Process.myPid();
            for (ActivityManager.RunningAppProcessInfo info : processes) {
                if (info != null && info.pid == pid) return info.processName;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isMainProcess() {
        return !getProcessName().contains(":");
    }

    public static boolean isSpiderProcess() {
        return getProcessName().endsWith(":spider");
    }
}
