package com.fongmi.android.tv.spider;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.loader.CspDexClassLoader;
import com.fongmi.android.tv.api.loader.JarLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import fi.iki.elonen.NanoHTTPD;

/**
 * :spider 子进程内的 jar 宿主。镜像主进程 JarLoader 的 load/invokeInit/invokeProxy/
 * createSpider/proxy/jsonExt 逻辑，但全部在子进程执行：jar 若调用 System.exit 只杀死
 * 本进程，主进程经 binderDied 感知并重启恢复。
 * 每次 load/create 前写 breadcrumb 文件，死亡后主进程读它归因肇事 jar。
 */
public class SpiderHost {

    private final ConcurrentHashMap<String, DexClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> methods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile String recent;

    public static SpiderHost get() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        private static final SpiderHost INSTANCE = new SpiderHost();
    }

    public void setRecent(String jarKey) {
        recent = jarKey;
    }

    public String getRecent() {
        return recent;
    }

    public boolean isLoaded(String jarKey) {
        return loaders.containsKey(jarKey);
    }

    /**
     * 归因面包屑：主进程在子进程死亡后读取该文件，确定最后一个正在加载/执行的 jar。
     */
    private void breadcrumb(String jarKey) {
        try {
            File file = new File(Path.jar(), ".spider_current.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                out.write(jarKey.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable e) {
            SpiderDebug.log("spider-host", "breadcrumb fail err=%s", e.getMessage());
        }
    }

    public void load(String jarKey, File file) throws Exception {
        breadcrumb(jarKey);
        Object lock = locks.computeIfAbsent(jarKey, k -> new Object());
        synchronized (lock) {
            if (loaders.containsKey(jarKey)) return;
            if (!Path.exists(file) || !file.setReadOnly()) throw new Exception("jar file unavailable: " + file);
            File optDir = new File(App.get().getCodeCacheDir(), "dexopt" + File.separator + jarKey);
            if (!optDir.exists()) optDir.mkdirs();
            boolean containsProtobuf = JarLoader.containsProtobuf(file);
            DexClassLoader loader = new CspDexClassLoader(file.getAbsolutePath(), optDir.getAbsolutePath(), null, App.get().getClassLoader(), containsProtobuf);
            invokeInit(loader);
            invokeProxy(jarKey, loader);
            loaders.put(jarKey, loader);
            SpiderDebug.log("spider-host", "jar loaded key=%s file=%s", jarKey, file.getName());
        }
    }

    private void invokeInit(DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            Method method = clz.getMethod("init", Context.class);
            method.invoke(clz, App.get());
        } catch (Throwable e) {
            SpiderDebug.log(e);
        }
    }

    private void invokeProxy(String jarKey, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            methods.put(jarKey, clz.getMethod("proxy", Map.class));
        } catch (Throwable e) {
            SpiderDebug.log(e);
        }
    }

    public void createSpider(String spKey, String key, String api, String ext, String jarKey) throws Exception {
        breadcrumb(jarKey);
        Object lock = locks.computeIfAbsent(spKey, k -> new Object());
        synchronized (lock) {
            if (spiders.containsKey(spKey)) return;
            DexClassLoader loader = loaders.get(jarKey);
            if (loader == null) throw new Exception("jar not loaded: " + jarKey);
            Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.split("csp_")[1]).newInstance();
            spider.siteKey = key;
            spider.init(App.get(), ext);
            spiders.put(spKey, spider);
            SpiderDebug.log("spider-host", "spider created spKey=%s api=%s", spKey, api);
        }
    }

    public void removeSpider(String spKey) {
        Spider spider = spiders.remove(spKey);
        if (spider != null) spider.destroy();
    }

    /**
     * 显式方法分发（不用反射找 spider 方法，参数类型边界清晰）。
     * args 约定：{"filter":bool,"tid":str,"pg":str,"extend":obj,"ids":[],"key":str,
     * "quick":bool,"flag":str,"id":str,"vipFlags":[],"url":str,"action":str,"value":bool}
     */
    public String call(String spKey, String method, JSONObject args) throws Exception {
        Spider spider = spiders.get(spKey);
        if (spider == null) throw new Exception("spider not created: " + spKey);
        breadcrumb(spiderJarKey(spKey));
        switch (method) {
            case "homeContent":
                return spider.homeContent(args.optBoolean("filter"));
            case "homeVideoContent":
                return spider.homeVideoContent();
            case "categoryContent":
                return spider.categoryContent(args.optString("tid"), args.optString("pg"), args.optBoolean("filter"), toStringMap(args.optJSONObject("extend")));
            case "detailContent":
                return spider.detailContent(toStringList(args.optJSONArray("ids")));
            case "searchContent":
                return spider.searchContent(args.optString("key"), args.optBoolean("quick"));
            case "searchContentPg":
                return spider.searchContent(args.optString("key"), args.optBoolean("quick"), args.optString("pg"));
            case "playerContent":
                return spider.playerContent(args.optString("flag"), args.optString("id"), toStringList(args.optJSONArray("vipFlags")));
            case "liveContent":
                return spider.liveContent(args.optString("url"));
            case "action":
                return spider.action(args.optString("action"));
            case "manualVideoCheck":
                return String.valueOf(spider.manualVideoCheck());
            case "isVideoFormat":
                return String.valueOf(spider.isVideoFormat(args.optString("url")));
            default:
                throw new Exception("unknown method: " + method);
        }
    }

    private String spiderJarKey(String spKey) {
        // spKey = jaKey + siteKey，jaKey 固定 32 位 md5
        return spKey.length() >= 32 ? spKey.substring(0, 32) : spKey;
    }

    /**
     * proxy 结果统一解包为四元组流式输出：Object[]{code, mime, InputStream, headers}，
     * 或 rs[0] 直接是 NanoHTTPD Response 的形态（部分 fork jar 会这样返回）。
     */
    public ProxyResult proxy(Map<String, String> params) throws Exception {
        breadcrumb(recent);
        Object[] rs = proxyInvoke(recent != null ? methods.get(recent) : null, params);
        if (rs == null) rs = tryOthers(params);
        if (rs == null) return null;
        return unpack(rs);
    }

    private Object[] tryOthers(Map<String, String> params) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), params)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    private ProxyResult unpack(Object[] rs) {
        ProxyResult result = new ProxyResult();
        if (rs[0] instanceof NanoHTTPD.Response) {
            NanoHTTPD.Response response = (NanoHTTPD.Response) rs[0];
            result.code = response.getStatus().getRequestStatus();
            result.mime = response.getMimeType();
            result.stream = response.getData();
            return result;
        }
        result.code = rs.length > 0 && rs[0] instanceof Integer ? (Integer) rs[0] : 500;
        result.mime = rs.length > 1 && rs[1] instanceof String ? (String) rs[1] : "application/octet-stream";
        result.stream = rs.length > 2 && rs[2] instanceof InputStream ? (InputStream) rs[2] : new java.io.ByteArrayInputStream(new byte[0]);
        if (rs.length > 3 && rs[3] instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) rs[3]).entrySet()) if (entry.getKey() instanceof String && entry.getValue() instanceof String) result.headers.put((String) entry.getKey(), (String) entry.getValue());
        return result;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        methods.clear();
        loaders.clear();
        locks.clear();
        recent = null;
    }

    private static List<String> toStringList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    private static HashMap<String, String> toStringMap(JSONObject object) {
        HashMap<String, String> result = new HashMap<>();
        if (object != null) for (Iterator<String> it = object.keys(); it.hasNext(); ) { String k = it.next(); result.put(k, String.valueOf(object.opt(k))); }
        return result;
    }

    public static class ProxyResult {
        public int code = 500;
        public String mime = "application/octet-stream";
        public InputStream stream;
        public final Map<String, String> headers = new HashMap<>();
    }
}
