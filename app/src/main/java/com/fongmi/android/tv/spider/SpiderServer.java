package com.fongmi.android.tv.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * :spider 子进程数据面：仅绑 127.0.0.1，端口区间 9950-9977（主进程 Server 占 9978-9998；
 * 部分设备 SELinux/厂商策略限制应用可绑端口，必须留在 1024-9999 区间内，真机实测 19978+ 全部 bind 失败），
 * 每个请求校验 X-Spider-Token（bind 时经 Binder 下发）。协议见各 handler。
 */
public class SpiderServer extends NanoHTTPD {

    public static final int PORT_START = 9950;
    public static final int PORT_END = 9977;
    private static final String TOKEN_HEADER = "x-spider-token";

    private final String token;

    public SpiderServer(String token, int port) {
        super("127.0.0.1", port);
        this.token = token;
    }

    /**
     * 启动并确认监听就绪。NanoHTTPD 2.3.1 在 ServerRunnable 线程内异步 bind 自有 socket
     * （且 start() 前已 setReuseAddress(true)，重启时 TIME_WAIT 不阻塞重绑），
     * 工厂预绑定会与其冲突（Already bound），故绑定结果只能轮询 isAlive() 确认；
     * bind 失败该线程立即退出，isAlive() 恒为 false。
     */
    public boolean startConfirmed() {
        try {
            super.start(SOCKET_READ_TIMEOUT, false);
            long deadline = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < deadline) {
                if (isAlive()) return true;
                Thread.sleep(50);
            }
            return false;
        } catch (Throwable e) {
            SpiderDebug.log("spider-server", "start fail port=%s err=%s", getListeningPort(), e.getMessage());
            stop();
            return false;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (!token.equals(session.getHeaders().get(TOKEN_HEADER))) return error(Response.Status.UNAUTHORIZED, "Unauthorized");
            Map<String, String> files = new HashMap<>();
            if (session.getMethod() == Method.POST) parse(session, files);
            String body = files.get("postData");
            if (TextUtils.isEmpty(body)) body = session.getParms().get("body");
            String url = session.getUri().trim();
            JSONObject json = TextUtils.isEmpty(body) ? new JSONObject() : new JSONObject(body);
            switch (url) {
                case "/health":
                    return ok(new JSONObject().put("pid", android.os.Process.myPid()).toString());
                case "/jar/load":
                    return jarLoad(json);
                case "/spider/recent":
                    SpiderHost.get().setRecent(json.optString("jarKey"));
                    return ok("{}");
                case "/spider/create":
                    return spiderCreate(json);
                case "/spider/call":
                    return spiderCall(json);
                case "/spider/proxy":
                    return spiderProxy(json);
                case "/parser/jsonExt":
                    return parserJsonExt(json);
                case "/parser/jsonExtMix":
                    return parserJsonExtMix(json);
                case "/shutdown":
                    SpiderDebug.log("spider-server", "shutdown requested");
                    Response response = ok("{}");
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                        }
                        SpiderHost.get().clear();
                        stop();
                        if (shutdownCallback != null) shutdownCallback.run();
                    }).start();
                    return response;
                default:
                    return error(Response.Status.NOT_FOUND, "Not found");
            }
        } catch (Throwable e) {
            SpiderDebug.log("spider-server", e);
            return error(Response.Status.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private Runnable shutdownCallback;

    public void setOnShutdown(Runnable callback) {
        this.shutdownCallback = callback;
    }

    private Response jarLoad(JSONObject json) {
        try {
            SpiderHost.get().load(json.optString("jarKey"), new File(json.optString("file")));
            return ok("{}");
        } catch (Throwable e) {
            SpiderDebug.log("spider-host", "jar load fail key=%s err=%s", json.optString("jarKey"), e.getMessage());
            return error(Response.Status.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private Response spiderCreate(JSONObject json) {
        try {
            SpiderHost.get().createSpider(json.optString("spKey"), json.optString("key"), json.optString("api"), json.optString("ext"), json.optString("jarKey"));
            return ok("{}");
        } catch (Throwable e) {
            SpiderDebug.log("spider-host", "spider create fail spKey=%s err=%s", json.optString("spKey"), e.getMessage());
            return error(Response.Status.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private Response spiderCall(JSONObject json) throws Exception {
        String result = SpiderHost.get().call(json.optString("spKey"), json.optString("method"), json.optJSONObject("args") == null ? new JSONObject() : json.optJSONObject("args"));
        return ok(new JSONObject().put("result", result == null ? JSONObject.NULL : result).toString());
    }

    private Response spiderProxy(JSONObject json) throws Exception {
        Map<String, String> params = new HashMap<>();
        JSONObject p = json.optJSONObject("params");
        if (p != null) for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) { String k = it.next(); params.put(k, String.valueOf(p.opt(k))); }
        SpiderHost.get().setRecent(json.optString("jarKey", SpiderHost.get().getRecent()));
        SpiderHost.ProxyResult result = SpiderHost.get().proxy(params);
        if (result == null) return error(Response.Status.NOT_FOUND, "Proxy result is null");
        Response response = newChunkedResponse(Response.Status.lookup(result.code), result.mime, result.stream);
        for (Map.Entry<String, String> entry : result.headers.entrySet()) response.addHeader(entry.getKey(), entry.getValue());
        return response;
    }

    private Response parserJsonExt(JSONObject json) throws Throwable {
        JSONObject result = SpiderHost.get().jsonExt(json.optString("key"), parseStringMap(json.optJSONObject("jxs")), json.optString("url"));
        return ok(String.valueOf(result));
    }

    private Response parserJsonExtMix(JSONObject json) throws Throwable {
        JSONObject mix = SpiderHost.get().jsonExtMix(json.optString("flag"), json.optString("key"), json.optString("name"), parseNestedMap(json.optJSONObject("jxs")), json.optString("url"));
        return ok(String.valueOf(mix));
    }

    private static LinkedHashMap<String, String> parseStringMap(JSONObject object) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (object != null) for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) { String k = it.next(); result.put(k, String.valueOf(object.opt(k))); }
        return result;
    }

    private static LinkedHashMap<String, HashMap<String, String>> parseNestedMap(JSONObject object) {
        LinkedHashMap<String, HashMap<String, String>> result = new LinkedHashMap<>();
        if (object == null) return result;
        for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
            String k = it.next();
            HashMap<String, String> inner = new HashMap<>();
            Object value = object.opt(k);
            if (value instanceof JSONObject) for (java.util.Iterator<String> sub = ((JSONObject) value).keys(); sub.hasNext(); ) { String sk = sub.next(); inner.put(sk, String.valueOf(((JSONObject) value).opt(sk))); }
            result.put(k, inner);
        }
        return result;
    }

    private static Response ok(String json) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json);
    }

    private static Response error(Response.Status status, String message) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, message);
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            String ct = session.getHeaders().get("content-type");
            if (ct != null) session.getHeaders().put("content-type", ct.replace("multipart/form-data", "multipart/form-data; charset=utf-8"));
            session.parseBody(files);
        } catch (Exception ignored) {
        }
    }
}
