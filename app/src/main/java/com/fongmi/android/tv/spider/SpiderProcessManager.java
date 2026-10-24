package com.fongmi.android.tv.spider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 主进程侧 :spider 子进程管理器。
 * Binder 控制面（bind/死亡感知/端口 token 握手）+ OkHttp 数据面（127.0.0.1 转发）。
 * 维护"已信任 jar 注册表"，子进程重启后重放 load，spider 实例由上层惰性重建。
 */
public class SpiderProcessManager implements ServiceConnection {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long BIND_TIMEOUT_MS = 8000;
    private static final long[] RESTART_LADDER = {1000, 5000, 30000, 300000};
    private static final long IDLE_UNBIND_MS = 5 * 60 * 1000L;
    private static final long IDLE_CHECK_MS = 60 * 1000L;

    private static class Holder {
        private static final SpiderProcessManager INSTANCE = new SpiderProcessManager();
    }

    public static SpiderProcessManager get() {
        return Holder.INSTANCE;
    }

    private final OkHttpClient client;
    private final LinkedHashMap<String, File> jarRegistry = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> deathListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger consecutiveDeaths = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Runnable idleCheck = this::checkIdle;
    private volatile ISpiderService service;
    private volatile boolean bound;
    private volatile long lastDeathAt;
    private volatile long lastCallAt;
    private volatile String recent;
    private volatile String lastDeathJarKey = "";

    private SpiderProcessManager() {
        client = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();
    }

    public void addDeathListener(Runnable listener) {
        deathListeners.add(listener);
    }

    public boolean isAlive() {
        return service != null;
    }

    /**
     * 确保子进程可用并完成端口/token 握手；重启间隔按连续死亡次数指数退避。
     */
    public synchronized boolean ensureUp() {
        if (service != null) return true;
        long wait = RESTART_LADDER[Math.min(Math.max(consecutiveDeaths.get() - 1, 0), RESTART_LADDER.length - 1)];
        long sinceDeath = System.currentTimeMillis() - lastDeathAt;
        if (lastDeathAt > 0 && sinceDeath < wait) {
            SpiderDebug.log("spider-proc", "backoff wait=%sms remaining=%sms", wait, wait - sinceDeath);
            return false;
        }
        try {
            Intent intent = new Intent(App.get(), SpiderService.class);
            if (!App.get().bindService(intent, this, Context.BIND_AUTO_CREATE)) return false;
            bound = true;
            long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
            while (service == null && System.currentTimeMillis() < deadline) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (service == null) {
                SpiderDebug.log("spider-proc", "bind timeout port not ready");
                unbind();
                return false;
            }
            int port = -1;
            try {
                port = service.getPort();
            } catch (RemoteException ignored) {
            }
            if (port <= 0) {
                // 进程活着但 SpiderServer 未就绪（如端口区间全部 bind 失败）：解绑让服务自杀，按启动失败退避，不归因 jar。
                SpiderDebug.log("spider-proc", "server not ready port=%s, unbind for backoff", port);
                unbind();
                lastDeathAt = System.currentTimeMillis();
                consecutiveDeaths.incrementAndGet();
                return false;
            }
            consecutiveDeaths.set(0);
            replayRegistry();
            App.removeCallbacks(idleCheck);
            App.post(idleCheck, IDLE_CHECK_MS);
            SpiderDebug.log("spider-proc", "process up port=%s", service.getPort());
            return true;
        } catch (Throwable e) {
            SpiderDebug.log("spider-proc", "ensureUp fail err=%s", e.getMessage());
            unbind();
            return false;
        }
    }

    private void replayRegistry() {
        for (Map.Entry<String, File> entry : new LinkedHashMap<>(jarRegistry).entrySet()) {
            // 拉黑检查只在 JarLoader.load 入口做，而 jar 可能在拉黑生效前就已进入本注册表；
            // 重放时须再次核对，否则每次子进程重启都会把肇事 jar 重新解密加载（拉黑语义泄漏）。
            if (Setting.isSpiderQuarantined(entry.getKey())) {
                jarRegistry.remove(entry.getKey());
                SpiderDebug.log("spider-proc", "replay skipped (quarantined) key=%s", entry.getKey());
                continue;
            }
            try {
                post("/jar/load", new JSONObject().put("jarKey", entry.getKey()).put("file", entry.getValue().getAbsolutePath()));
            } catch (Throwable e) {
                SpiderDebug.log("spider-proc", "replay load fail key=%s err=%s", entry.getKey(), e.getMessage());
            }
        }
        if (recent != null) {
            try {
                post("/spider/recent", new JSONObject().put("jarKey", recent));
            } catch (Throwable ignored) {
            }
        }
    }

    public void loadJar(String jarKey, File file) throws IOException {
        jarRegistry.put(jarKey, file);
        if (!ensureUp()) throw new IOException("spider process not available");
        try {
            post("/jar/load", new JSONObject().put("jarKey", jarKey).put("file", file.getAbsolutePath()));
        } catch (IOException e) {
            jarRegistry.remove(jarKey);
            throw e;
        } catch (Exception e) {
            jarRegistry.remove(jarKey);
            throw new IOException(e.getMessage());
        }
    }

    public void createSpider(String spKey, String key, String api, String ext, String jarKey) throws IOException {
        if (!ensureUp()) throw new IOException("spider process not available");
        try {
            post("/spider/create", new JSONObject().put("spKey", spKey).put("key", key).put("api", api).put("ext", ext).put("jarKey", jarKey));
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    public void setRecent(String jarKey) {
        recent = jarKey;
        if (service == null) return;
        try {
            post("/spider/recent", new JSONObject().put("jarKey", jarKey));
        } catch (Throwable ignored) {
        }
    }

    public String call(String spKey, String method, JSONObject args) throws IOException {
        if (!ensureUp()) throw new IOException("spider process not available");
        try {
            JSONObject body = new JSONObject().put("spKey", spKey).put("method", method).put("args", args);
            return extractResult(post("/spider/call", body));
        } catch (IOException e) {
            return retryOnce(() -> extractResult(post("/spider/call", new JSONObject().put("spKey", spKey).put("method", method).put("args", args))));
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    public String jsonExt(String key, String jxsJson, String url) throws IOException {
        if (!ensureUp()) throw new IOException("spider process not available");
        try {
            return extractResult(post("/parser/jsonExt", new JSONObject().put("key", key).put("jxs", new JSONObject(jxsJson)).put("url", url)));
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    public String jsonExtMix(String flag, String key, String name, String jxsJson, String url) throws IOException {
        if (!ensureUp()) throw new IOException("spider process not available");
        try {
            return extractResult(post("/parser/jsonExtMix", new JSONObject().put("flag", flag).put("key", key).put("name", name).put("jxs", new JSONObject(jxsJson)).put("url", url)));
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    /**
     * proxy 流式转发：调用方负责关闭 Response body（主进程 Proxy 处理器写完后自然关闭）。
     */
    public Response proxyStream(String jarKey, Map<String, String> params) throws IOException {
        if (!ensureUp()) throw new IOException("spider process not available");
        inFlight.incrementAndGet();
        try {
            JSONObject json = new JSONObject();
            if (params != null) for (Map.Entry<String, String> entry : params.entrySet()) json.put(entry.getKey(), entry.getValue());
            JSONObject body = new JSONObject().put("jarKey", jarKey).put("params", json);
            // 任意状态码都原样透传（爬虫业务码），由上层按四元组处理
            return countStream(request("/spider/proxy", body.toString()));
        } catch (IOException e) {
            inFlight.decrementAndGet();
            throw e;
        } catch (Exception e) {
            inFlight.decrementAndGet();
            throw new IOException(e.getMessage());
        }
    }

    /**
     * 活跃流计数：调用方关闭 body 时递减，空闲回收期间不 unbind。
     */
    private Response countStream(Response response) {
        okhttp3.ResponseBody original = response.body();
        if (original == null) {
            inFlight.decrementAndGet();
            return response;
        }
        okhttp3.ResponseBody counting = new okhttp3.ResponseBody() {
            @Override
            public okhttp3.MediaType contentType() {
                return original.contentType();
            }

            @Override
            public long contentLength() {
                return original.contentLength();
            }

            @Override
            public okio.BufferedSource source() {
                return okio.Okio.buffer(new okio.ForwardingSource(original.source()) {
                    @Override
                    public void close() throws IOException {
                        inFlight.decrementAndGet();
                        super.close();
                    }
                });
            }
        };
        return response.newBuilder().body(counting).build();
    }

    private void checkIdle() {
        synchronized (this) {
            if (service == null) return;
            if (inFlight.get() > 0 || System.currentTimeMillis() - lastCallAt < IDLE_UNBIND_MS) {
                App.post(idleCheck, IDLE_CHECK_MS);
                return;
            }
            SpiderDebug.log("spider-proc", "idle unbind idle=%sms", System.currentTimeMillis() - lastCallAt);
            unbind();
        }
    }

    public String getLastDeathJarKey() {
        return lastDeathJarKey;
    }

    public void shutdownAll() {
        try {
            if (service != null) post("/shutdown", new JSONObject());
        } catch (Throwable ignored) {
        }
        jarRegistry.clear();
        recent = null;
        unbind();
    }

    private String retryOnce(Callable callable) throws IOException {
        try {
            if (ensureUp()) return callable.call();
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        throw new IOException("spider process died");
    }

    private interface Callable {
        String call() throws Exception;
    }

    private String extractResult(String text) throws Exception {
        JSONObject object = new JSONObject(text);
        Object result = object.opt("result");
        return result == null || JSONObject.NULL.equals(result) ? null : String.valueOf(result);
    }

    private String post(String path, JSONObject body) throws IOException {
        return request(path, body.toString()).body().string();
    }

    private Response request(String path, String body) throws IOException {
        lastCallAt = System.currentTimeMillis();
        ISpiderService current = service;
        if (current == null) throw new IOException("spider process not bound");
        int port;
        String token;
        try {
            port = current.getPort();
            token = current.getToken();
        } catch (RemoteException e) {
            throw new IOException("spider process died");
        }
        if (port <= 0) throw new IOException("spider server not ready");
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + path).header("X-Spider-Token", token).post(RequestBody.create(JSON, body)).build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful() && path.equals("/spider/proxy")) {
            // proxy 的非 2xx 是爬虫业务状态码透传，交由上层处理；其余路径非 2xx 视为失败
            return response;
        }
        if (!response.isSuccessful()) {
            String message = response.body() == null ? "status " + response.code() : response.body().string();
            response.close();
            throw new IOException(message);
        }
        return response;
    }

    private void unbind() {
        if (!bound) return;
        try {
            App.get().unbindService(this);
        } catch (Throwable ignored) {
        }
        bound = false;
        service = null;
    }

    private void onProcessDeath() {
        lastDeathAt = System.currentTimeMillis();
        int deaths = consecutiveDeaths.incrementAndGet();
        String jarKey = readBreadcrumb();
        lastDeathJarKey = jarKey == null ? "" : jarKey;
        if (!lastDeathJarKey.isEmpty()) Setting.addSpiderJarDeath(lastDeathJarKey);
        SpiderDebug.log("spider-proc", "process died deaths=%s breadcrumb=%s", deaths, jarKey);
        service = null;
        for (Runnable listener : deathListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private String readBreadcrumb() {
        try {
            File file = new File(com.github.catvod.utils.Path.jar(), ".spider_current.txt");
            if (!file.exists()) return "";
            byte[] bytes = new byte[(int) Math.min(file.length(), 64)];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int n = in.read(bytes);
                return n > 0 ? new String(bytes, 0, n) : "";
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return "";
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        try {
            service = ISpiderService.Stub.asInterface(binder);
        } catch (Throwable e) {
            SpiderDebug.log("spider-proc", "asInterface fail err=%s", e.getMessage());
        }
        synchronized (this) {
            notifyAll();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        SpiderDebug.log("spider-proc", "service disconnected");
        onProcessDeath();
    }

    @Override
    public void onBindingDied(ComponentName name) {
        SpiderDebug.log("spider-proc", "binding died");
        bound = false;
        onProcessDeath();
    }

    @Override
    public void onNullBinding(ComponentName name) {
        SpiderDebug.log("spider-proc", "null binding (service start failed)");
        bound = false;
    }
}
