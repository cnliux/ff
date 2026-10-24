package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.spider.SpiderProcessManager;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Spider 门面：方法签名与原 Spider 一致，业务调用点零改动；
 * 实际执行经 SpiderProcessManager 转发到 :spider 子进程。
 */
public class RemoteSpider extends Spider {

    private final String spKey;
    private final String jarKey;

    public RemoteSpider(String spKey, String jarKey, String siteKey) {
        this.spKey = spKey;
        this.jarKey = jarKey;
        this.siteKey = siteKey;
    }

    public String getJarKey() {
        return jarKey;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return SpiderProcessManager.get().call(spKey, "homeContent", new JSONObject().put("filter", filter));
    }

    @Override
    public String homeVideoContent() throws Exception {
        return SpiderProcessManager.get().call(spKey, "homeVideoContent", new JSONObject());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return SpiderProcessManager.get().call(spKey, "categoryContent", new JSONObject().put("tid", nz(tid)).put("pg", nz(pg)).put("filter", filter).put("extend", new JSONObject(toStringJson(extend))));
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return SpiderProcessManager.get().call(spKey, "detailContent", new JSONObject().put("ids", new JSONArray(ids)));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return SpiderProcessManager.get().call(spKey, "searchContent", new JSONObject().put("key", nz(key)).put("quick", quick));
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return SpiderProcessManager.get().call(spKey, "searchContentPg", new JSONObject().put("key", nz(key)).put("quick", quick).put("pg", nz(pg)));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return SpiderProcessManager.get().call(spKey, "playerContent", new JSONObject().put("flag", nz(flag)).put("id", nz(id)).put("vipFlags", new JSONArray(vipFlags)));
    }

    @Override
    public String liveContent(String url) throws Exception {
        return SpiderProcessManager.get().call(spKey, "liveContent", new JSONObject().put("url", nz(url)));
    }

    @Override
    public String action(String action) throws Exception {
        return SpiderProcessManager.get().call(spKey, "action", new JSONObject().put("action", nz(action)));
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return Boolean.parseBoolean(SpiderProcessManager.get().call(spKey, "manualVideoCheck", new JSONObject()));
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return Boolean.parseBoolean(SpiderProcessManager.get().call(spKey, "isVideoFormat", new JSONObject().put("url", nz(url))));
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        Response response = SpiderProcessManager.get().proxyStream(jarKey, params);
        try {
            ResponseBody body = response.body();
            Map<String, String> headers = new HashMap<>();
            for (String name : response.headers().names()) {
                if (isHopByHop(name)) continue;
                String value = response.header(name);
                if (value != null) headers.put(name, value);
            }
            return new Object[]{response.code(), response.header("Content-Type", "application/octet-stream"), body == null ? new java.io.ByteArrayInputStream(new byte[0]) : body.byteStream(), headers};
        } catch (Throwable e) {
            response.close();
            throw e;
        }
    }

    private static boolean isHopByHop(String name) {
        return "Transfer-Encoding".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name) || "Connection".equalsIgnoreCase(name) || "Keep-Alive".equalsIgnoreCase(name);
    }

    private static String toStringJson(Map<String, String> map) {
        try {
            JSONObject object = new JSONObject();
            if (map != null) for (Map.Entry<String, String> entry : map.entrySet()) object.put(entry.getKey(), entry.getValue());
            return object.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String nz(String text) {
        return text == null ? "" : text;
    }
}
