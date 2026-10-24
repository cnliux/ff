package com.fongmi.android.tv.api.loader;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.spider.SpiderProcessManager;
import com.fongmi.android.tv.utils.DependencyTrust;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

public class JarLoader {

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, Method> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Object> locks;
    private final ConcurrentHashMap<String, File> remote;
    private volatile String recent;

    public JarLoader() {
        loaders = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
        locks = new ConcurrentHashMap<>();
        remote = new ConcurrentHashMap<>();
        SpiderProcessManager.get().addDeathListener(() -> {
            // 子进程死亡：RemoteSpider 门面全部作废，惰性重建（spider 实例在子进程侧已丢失）
            spiders.entrySet().removeIf(entry -> entry.getValue() instanceof RemoteSpider);
            String jarKey = SpiderProcessManager.get().getLastDeathJarKey();
            if (jarKey == null || jarKey.isEmpty()) return;
            try {
                for (Site site : VodConfig.get().getSites()) {
                    String api = site.getApi();
                    if (api == null || !api.contains("csp_")) continue;
                    if (Util.md5(site.getJar()).equals(jarKey)) SiteHealthStore.recordProcessDeath(site.getKey(), "spider process died");
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public void clear() {
        boolean isolation = Setting.isSpiderIsolation();
        spiders.values().forEach(Spider::destroy);
        loaders.clear();
        methods.clear();
        spiders.clear();
        locks.clear();
        remote.clear();
        recent = null;
        if (isolation) SpiderProcessManager.get().shutdownAll();
        File optRoot = new File(App.get().getCodeCacheDir(), "dexopt");
        if (optRoot.exists()) for (File child : optRoot.listFiles() == null ? new File[0] : optRoot.listFiles()) Path.clear(child);
    }

    public void setRecent(String recent) {
        this.recent = recent;
        if (Setting.isSpiderIsolation()) SpiderProcessManager.get().setRecent(recent);
    }

    private void load(String key, File file) {
        if (Thread.interrupted()) return;
        if (Setting.isSpiderIsolation()) {
            // 隔离模式：DexClassLoader/Init.init 全部在 :spider 子进程执行，
            // jar 若 System.exit 只杀子进程；失败（含子进程死亡）记日志跳过，不中断配置加载。
            if (Setting.isSpiderQuarantined(key)) {
                SpiderDebug.log("jar", "isolated load skipped (quarantined) key=%s", key);
                return;
            }
            try {
                SpiderProcessManager.get().loadJar(key, file);
                remote.put(key, file);
            } catch (Throwable e) {
                SpiderDebug.log("jar", "isolated load failed key=%s err=%s", key, e.getMessage());
            }
            return;
        }
        if (!Path.exists(file) || !file.setReadOnly()) return;
        File optDir = new File(App.get().getCodeCacheDir(), "dexopt" + File.separator + key);
        if (!optDir.exists()) optDir.mkdirs();
        boolean containsProtobuf = containsProtobuf(file);
        DexClassLoader loader = new CspDexClassLoader(file.getAbsolutePath(), optDir.getAbsolutePath(), null, App.get().getClassLoader(), containsProtobuf);
        invokeInit(loader);
        invokeProxy(key, loader);
        loaders.put(key, loader);
    }

    public static boolean containsProtobuf(File file) {
        if (file == null || !file.isFile()) return false;
        try (ZipFile zip = new ZipFile(file)) {
            for (java.util.Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (name != null && name.startsWith("com/google/protobuf/") && name.endsWith(".class")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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

    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            methods.put(key, method);
        } catch (Throwable e) {
            SpiderDebug.log(e);
        }
    }

    public void parseJar(String key, String jar) {
        if (loaders.containsKey(key)) return;
        boolean asset = jar.startsWith("assets");
        if (asset) jar = UrlUtil.convert(jar);
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String[] texts = splitHash(jar);
            jar = texts[0];
            String hashType = texts[1];
            String hash = texts[2];
            if (asset) {
                File file = Download.create(jar, Path.jar(jar)).get();
                if (Path.exists(file) && (hash.isEmpty() || verify(file, hashType, hash))) load(key, file);
            } else if (jar.startsWith("https")) {
                File file = Download.create(jar, Path.jar(jar)).get();
                if (!Path.exists(file)) {
                    SpiderDebug.log("jar", "remote jar unavailable key=%s url=%s", key, jar);
                    return;
                }
                // TVBox/vgg 口径：远程 jar 的 ;md5;/;sha256; 只是提示，上游 jar 轮换会让配置里的哈希过期，
                // 真正执行的是下载到的内容。哈希缺失或不匹配都不再拒绝加载，改为以「下载到的实际字节」作为
                // 信任确认对象（首次仍需在设备上确认一次，确认记录按实际哈希缓存）。
                String trustType = hash.isEmpty() ? "md5" : hashType;
                String trustHash = hash.isEmpty() ? Util.md5(file) : hash;
                if (!hash.isEmpty() && !verify(file, hashType, hash)) {
                    trustType = "md5";
                    trustHash = Util.md5(file);
                    SpiderDebug.log("jar", "hash mismatch (stale config?), continuing with downloaded jar key=%s url=%s type=%s expected=%s actual=%s", key, jar, hashType, hash, trustHash);
                } else if (hash.isEmpty()) {
                    SpiderDebug.log("jar", "remote jar without hash, trusting computed md5 key=%s url=%s md5=%s", key, jar, trustHash);
                }
                if (!DependencyTrust.confirm("JAR", jar, trustType, trustHash, file)) {
                    SpiderDebug.log("jar", "remote jar not trusted key=%s url=%s", key, jar);
                    return;
                }
                load(key, file);
            } else if (jar.startsWith("file")) {
                if (hash.isEmpty()) {
                    SpiderDebug.log("jar", "rejected file jar without ;sha256;/;md5; key=%s url=%s", key, jar);
                    return;
                }
                File file = Path.local(jar);
                if (!Path.exists(file) || !verify(file, hashType, hash)) {
                    SpiderDebug.log("jar", "file jar missing or hash mismatch key=%s url=%s", key, jar);
                    return;
                }
                if (!DependencyTrust.confirm("JAR", jar, hashType, hash, file)) {
                    SpiderDebug.log("jar", "file jar not trusted key=%s url=%s", key, jar);
                    return;
                }
                load(key, file);
            } else if (jar.startsWith("http")) {
                SpiderDebug.log("jar", "rejected cleartext http jar key=%s url=%s", key, jar);
            } else {
                SpiderDebug.log("jar", "skipped unrecognized jar key=%s url=%s", key, jar);
            }
        }
    }

    private String[] splitHash(String jar) {
        String[] sha = jar.split(";sha256;", 2);
        if (sha.length > 1) return new String[]{sha[0], "sha256", sha[1].trim()};
        String[] md5 = jar.split(";md5;", 2);
        if (md5.length > 1) return new String[]{md5[0], "md5", md5[1].trim()};
        return new String[]{jar, "", ""};
    }

    private boolean verify(File file, String type, String hash) {
        if (file == null || !Path.exists(file) || hash == null || hash.startsWith("http")) return false;
        if ("sha256".equalsIgnoreCase(type)) return Util.equalsSha256(file, hash);
        if ("md5".equalsIgnoreCase(type)) return Util.md5(file).equalsIgnoreCase(hash);
        return false;
    }

    /**
     * jar 是否已加载（只查缓存，不触发下载/信任确认）。供源测速等内容探测使用。
     */
    public boolean isLoaded(String jar) {
        return jar != null && !jar.isEmpty() && (loaders.containsKey(Util.md5(jar)) || remote.containsKey(Util.md5(jar)));
    }

    public DexClassLoader dex(String jar) {
        try {
            String jaKey = Util.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        String jaKey = Util.md5(jar);
        String spKey = jaKey + key;
        Spider cached = spiders.get(spKey);
        if (cached != null) return cached;
        Object lock = locks.computeIfAbsent(spKey, k -> new Object());
        synchronized (lock) {
            cached = spiders.get(spKey);
            if (cached != null) return cached;
            Spider spider = createSpider(key, api, ext, jar, jaKey);
            // Only memoize a live spider. A SpiderNull means the jar could not be used
            // *yet* — most often the remote-dependency confirmation had no Activity to
            // show in (cold start, or the first-run permission dialogs sending the user
            // into Settings). Memoizing it kept the site dead for the whole process, so
            // the home page stayed empty even after the user confirmed trust; leaving
            // it uncached makes the next request ask again and recover.
            if (spider instanceof SpiderNull) return spider;
            spiders.put(spKey, spider);
            return spider;
        }
    }

    private Spider createSpider(String key, String api, String ext, String jar, String jaKey) {
        try {
            parseJar(jaKey, jar);
            if (Setting.isSpiderIsolation()) {
                // 隔离模式：实例化与 spider.init 在子进程执行，主进程只持有 RemoteSpider 门面。
                if (Setting.isSpiderQuarantined(jaKey)) {
                    SpiderDebug.log("jar", "createSpider quarantined key=%s", jaKey);
                    return new SpiderNull();
                }
                if (!remote.containsKey(jaKey)) return new SpiderNull();
                String spKey = jaKey + key;
                SpiderProcessManager.get().createSpider(spKey, key, api, ext, jaKey);
                return new RemoteSpider(spKey, jaKey, key);
            }
            DexClassLoader loader = loaders.get(jaKey);
            if (loader == null) return new SpiderNull();
            Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.split("csp_")[1]).newInstance();
            spider.siteKey = key;
            spider.init(App.get(), ext);
            return spider;
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return new SpiderNull();
        }
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        if (Setting.isSpiderIsolation()) return new JSONObject(SpiderProcessManager.get().jsonExt(key, toJson(jxs), url));
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        if (Setting.isSpiderIsolation()) return new JSONObject(SpiderProcessManager.get().jsonExtMix(flag, key, name, toNestedJson(jxs), url));
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (Setting.isSpiderIsolation()) {
            // recent 优先 + tryOthers 语义在子进程 SpiderHost 内实现
            okhttp3.Response response = SpiderProcessManager.get().proxyStream(recent, params);
            try {
                okhttp3.ResponseBody body = response.body();
                Map<String, String> headers = new HashMap<>();
                for (String headerName : response.headers().names()) {
                    if ("Transfer-Encoding".equalsIgnoreCase(headerName) || "Content-Length".equalsIgnoreCase(headerName) || "Connection".equalsIgnoreCase(headerName) || "Keep-Alive".equalsIgnoreCase(headerName)) continue;
                    String value = response.header(headerName);
                    if (value != null) headers.put(headerName, value);
                }
                return new Object[]{response.code(), response.header("Content-Type", "application/octet-stream"), body == null ? new java.io.ByteArrayInputStream(new byte[0]) : body.byteStream(), headers};
            } catch (Throwable e) {
                response.close();
                throw e;
            }
        }
        Method method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private static String toJson(Map<String, String> map) {
        org.json.JSONObject object = new org.json.JSONObject();
        if (map != null) for (Map.Entry<String, String> entry : map.entrySet()) try { object.put(entry.getKey(), entry.getValue()); } catch (Exception ignored) {}
        return object.toString();
    }

    private static String toNestedJson(Map<String, HashMap<String, String>> map) {
        org.json.JSONObject object = new org.json.JSONObject();
        if (map != null) for (Map.Entry<String, HashMap<String, String>> entry : map.entrySet()) {
            org.json.JSONObject inner = new org.json.JSONObject();
            for (Map.Entry<String, String> sub : entry.getValue().entrySet()) try { inner.put(sub.getKey(), sub.getValue()); } catch (Exception ignored) {}
            try { object.put(entry.getKey(), inner); } catch (Exception ignored) {}
        }
        return object.toString();
    }

    private Object[] tryOthers(Map<String, String> p) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), p)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return null;
        }
    }
}
