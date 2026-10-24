package com.fongmi.android.tv.spider;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.github.catvod.crawler.SpiderDebug;

import java.security.SecureRandom;

/**
 * :spider 进程控制面锚点。Binder 仅回传 HTTP 端口与会话 token；
 * 数据面全部走 SpiderServer（127.0.0.1）。进程被 jar 的 System.exit 杀死时，
 * 主进程经 binderDied 感知，本进程不做任何崩溃兜底（死亡即检测手段）。
 */
public class SpiderService extends Service {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String token = generateToken();
    private SpiderServer server;
    private volatile int port = -1;

    private final ISpiderService.Stub binder = new ISpiderService.Stub() {
        @Override
        public int getPort() {
            return port;
        }

        @Override
        public String getToken() {
            return token;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            port = startServer();
            if (port > 0) {
                SpiderDebug.log("spider-service", "created port=%s pid=%s", port, android.os.Process.myPid());
            } else {
                SpiderDebug.log("spider-service", "start fail err=no free spider port in %s-%s", SpiderServer.PORT_START, SpiderServer.PORT_END);
                stopSelf();
            }
        } catch (Throwable e) {
            SpiderDebug.log("spider-service", "start fail err=%s", e.getMessage());
            stopSelf();
        }
    }

    private int startServer() {
        for (int candidate = SpiderServer.PORT_START; candidate <= SpiderServer.PORT_END; candidate++) {
            SpiderServer server = new SpiderServer(token, candidate);
            server.setOnShutdown(() -> handler.post(this::stopSelf));
            if (server.startConfirmed()) {
                this.server = server;
                return candidate;
            }
            server.stop();
        }
        return -1;
    }

    @Override
    public IBinder onBind(Intent intent) {
        handler.removeCallbacks(stopDelayed);
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 解绑后延迟自杀，空闲回收；短暂重绑不触发进程重启
        handler.postDelayed(stopDelayed, 60_000);
        return false;
    }

    private final Runnable stopDelayed = this::stopSelf;

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(stopDelayed);
        if (server != null) server.stop();
        SpiderHost.get().clear();
        SpiderDebug.log("spider-service", "destroyed");
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
