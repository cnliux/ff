package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.LocalNetworkPermission;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.android.tv.utils.ProcessUtil;
import com.fongmi.android.tv.utils.EpgReminder;
import com.fongmi.hook.Hook;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        Init.set(base);
        Context localized = Setting.wrapLanguage(base);
        super.attachBaseContext(localized);
        Init.set(localized);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!ProcessUtil.isMainProcess()) {
            initSpiderProcess();
            return;
        }
        installSpiderCrashGuard();
        Prefers.remove("theme_color");
        Prefers.remove("wall_color");
        Setting.restoreClassicGreenWall();
        Setting.applyLanguage();
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        Notify.createChannel();
        EpgReminder.createChannel();
        ProxySetting.apply();
        registerActivityLifecycleCallbacks(this);
        post(this::startBackgroundServices, 1200);
    }

    /**
     * :spider 子进程最小初始化：只开日志与代理/DNS 规则（jar 内 OkHttp 需要）。
     * 故意不装崩溃守卫——子进程未捕获异常/被 System.exit 杀死正是主进程 binderDied 的检测手段。
     */
    private void initSpiderProcess() {
        DebugLogStore.restoreEnabled();
        ProxySetting.apply();
    }

    /**
     * 第三方爬虫 jar 会自建线程（Init 线程池等），其中的类校验失败（VerifyError/NoClassDefFoundError）
     * 发生在应用调用栈之外，try/catch 兜不住，会经默认 handler 直接杀掉整个应用。
     * 这里包一层默认 handler：仅吞掉堆栈源自 com.github.catvod.spider.*（jar 独有包）的后台线程错误并记日志，
     * 其余异常（含主线程）原样交给 CustomActivityOnCrash。
     */
    private void installSpiderCrashGuard() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread != null && thread != Looper.getMainLooper().getThread() && isSpiderJarError(error)) {
                SpiderDebug.log("crash", "swallowed spider-jar error thread=%s error=%s", thread.getName(), String.valueOf(error));
                return;
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private boolean isSpiderJarError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String className = frame.getClassName();
                if (className != null && className.startsWith("com.github.catvod.spider.")) return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private void startBackgroundServices() {
        SpiderDebug.log("startup", "background services start cost=%sms", System.currentTimeMillis() - time);
        Server.get().start();
        History.cleanExpired();
        if (LocalNetworkPermission.isGranted(this)) NsdDeviceDiscovery.register();
        EpgReminder.rebuildFromStorage();
        PlaybackRemoteSyncer.start();
        if (RemoteStore.get().enabled && !RemoteStore.get().serverUrl.isEmpty()) RemoteAgent.get().start();
        SpiderDebug.log("startup", "background services ready cost=%sms", System.currentTimeMillis() - time);
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        if (LocalNetworkPermission.isGranted(this)) NsdDeviceDiscovery.register();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
