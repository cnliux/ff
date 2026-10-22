package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * 管理当前影视接口配置里的站源（sites）。
 * <p>
 * 站源保存在接口原始 JSON 中，本地接管后的内容写入 {@link Config#getJson()}，
 * 这样无需额外存储权限，也不会改动接口地址；清空该字段即可恢复接口原始站源。
 */
public class SourceSetting {

    private static final String TAG = SourceSetting.class.getSimpleName();
    private static final String KEY = "key";
    private static final String NAME = "name";
    private static final String SITES = "sites";
    private static final int TEST_THREADS = 16;
    private static final long TEST_TIMEOUT = 4000;
    private static String cachedUrl = "";
    private static JsonObject cachedObject;

    public static Config config() {
        return VodConfig.get().getConfig();
    }

    public static boolean isManaged() {
        return !TextUtils.isEmpty(config().getJson());
    }

    /**
     * 接口内容发生变化时清理缓存，避免读到旧数据。
     */
    public static void clearCache() {
        cachedUrl = "";
        cachedObject = null;
    }

    /**
     * 读取当前接口配置内容，优先使用本地保存的版本。
     */
    public static JsonObject configJson() throws Exception {
        Config config = config();
        if (config.isEmpty()) throw new Exception("当前没有启用影视接口");
        String text = config.getJson();
        if (TextUtils.isEmpty(text)) {
            if (cachedObject != null && config.getUrl().equals(cachedUrl)) return cachedObject.deepCopy();
            text = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
            cachedUrl = config.getUrl();
            cachedObject = object(text);
            return cachedObject.deepCopy();
        }
        return object(text);
    }

    private static JsonObject object(String text) throws Exception {
        JsonElement element = Json.parse(text);
        if (element == null || !element.isJsonObject()) throw new Exception("接口配置格式不正确");
        JsonObject result = element.getAsJsonObject();
        if (result.has("urls")) throw new Exception("当前接口是配置仓库，请先在“接口”中切换到具体接口");
        return result;
    }

    /**
     * 当前接口的站源列表，保留原始 JSON 以便导出时不丢字段。
     */
    public static JsonArray sites() throws Exception {
        return sites(configJson());
    }

    /**
     * 从接口配置内容中取出站源列表。
     */
    public static JsonArray sites(JsonObject object) {
        JsonElement element = object.get(SITES);
        if (element == null || element.isJsonNull()) return new JsonArray();
        if (!element.isJsonArray()) throw new IllegalStateException("当前接口的站源来自远程地址，无法直接管理");
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonObject()) result.add(item.getAsJsonObject().deepCopy());
        }
        return result;
    }

    /**
     * 站源列表摘要，用于页面展示。
     */
    public static JsonArray summary(JsonArray sites) {
        JsonArray result = new JsonArray();
        for (JsonElement element : sites) {
            if (!element.isJsonObject()) continue;
            JsonObject site = element.getAsJsonObject();
            JsonObject item = new JsonObject();
            item.addProperty(KEY, Json.safeString(site, KEY));
            item.addProperty(NAME, Json.safeString(site, NAME));
            item.addProperty("api", Json.safeString(site, "api"));
            item.addProperty("jar", Json.safeString(site, "jar"));
            item.addProperty("ext", text(site.get("ext")));
            if (site.has("type")) item.add("type", site.get("type"));
            if (site.has("hide")) item.add("hide", site.get("hide"));
            if (site.has("searchable")) item.add("searchable", site.get("searchable"));
            if (site.has("changeable")) item.add("changeable", site.get("changeable"));
            if (site.has("quickSearch")) item.add("quickSearch", site.get("quickSearch"));
            item.addProperty("url", probeUrl(site));
            result.add(item);
        }
        return result;
    }

    /**
     * 解析导入内容，支持 TVBox 配置（sites 数组）、纯站源数组与单个站源对象。
     */
    public static JsonArray parse(String text) throws Exception {
        if (TextUtils.isEmpty(text) || text.trim().isEmpty()) throw new Exception("内容为空");
        JsonElement element;
        try {
            element = Json.parse(text.trim());
        } catch (Exception e) {
            throw new Exception("JSON 格式错误");
        }
        if (element == null || element.isJsonNull()) throw new Exception("内容为空");
        if (element.isJsonArray()) return filter(element.getAsJsonArray());
        if (!element.isJsonObject()) throw new Exception("无法识别的内容格式");
        JsonObject object = element.getAsJsonObject();
        if (object.has(SITES)) {
            JsonElement sites = object.get(SITES);
            if (!sites.isJsonArray()) throw new Exception("sites 不是数组");
            return filter(sites.getAsJsonArray());
        }
        if (object.has("urls")) throw new Exception("这是配置仓库内容，没有站源");
        if (object.has(KEY)) {
            JsonArray array = new JsonArray();
            array.add(object.deepCopy());
            return filter(array);
        }
        throw new Exception("没有找到站源数据");
    }

    /**
     * 合并站源，replace 为 true 时直接覆盖，否则同 key 覆盖、不同 key 追加。
     */
    public static JsonArray merge(JsonArray current, JsonArray incoming, boolean replace) {
        if (replace) return incoming;
        JsonArray result = new JsonArray();
        for (JsonElement element : current) result.add(element);
        for (JsonElement element : incoming) {
            if (!element.isJsonObject()) continue;
            String key = Json.safeString(element.getAsJsonObject(), KEY);
            int index = indexOf(result, key);
            if (index >= 0) result.set(index, element);
            else result.add(element);
        }
        return result;
    }

    /**
     * 删除指定 key 的站源。
     */
    public static JsonArray remove(JsonArray current, String key) {
        JsonArray result = new JsonArray();
        for (JsonElement element : current) {
            if (element.isJsonObject() && key.equals(Json.safeString(element.getAsJsonObject(), KEY))) continue;
            result.add(element);
        }
        return result;
    }

    /**
     * 按给定顺序重排，未列出的站源保持在后面。
     */
    public static JsonArray reorder(JsonArray current, List<String> keys) {
        Map<String, JsonElement> items = new LinkedHashMap<>();
        for (JsonElement element : current) {
            if (!element.isJsonObject()) continue;
            items.put(Json.safeString(element.getAsJsonObject(), KEY), element);
        }
        JsonArray result = new JsonArray();
        for (String key : keys) {
            JsonElement element = items.remove(key);
            if (element != null) result.add(element);
        }
        for (JsonElement element : items.values()) result.add(element);
        return result;
    }

    /**
     * 保存站源并重新加载接口配置。
     */
    public static void apply(JsonArray sites) throws Exception {
        apply(configJson(), sites);
    }

    /**
     * 保存站源并重新加载接口配置，复用已读取的接口内容避免重复下载。
     */
    public static void apply(JsonObject object, JsonArray sites) throws Exception {
        Config config = config();
        object.add(SITES, sites);
        config.setJson(object.toString());
        config.save();
        clearCache();
        reload(config);
    }

    /**
     * 清除本地保存的站源，恢复接口原始配置。
     */
    public static void reset() throws Exception {
        Config config = config();
        config.setJson(null);
        config.save();
        clearCache();
        reload(config);
    }

    /**
     * 测速，优先探测 api，其次探测 jar，都没有则跳过。
     */
    public static JsonArray test(JsonArray sites, long timeout) {
        JsonArray result = new JsonArray();
        if (sites == null || sites.isEmpty()) return result;
        int size = sites.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(TEST_THREADS, size));
        OkHttpClient client = OkHttp.client(timeout);
        try {
            List<Future<JsonObject>> futures = new ArrayList<>();
            List<JsonObject> items = new ArrayList<>();
            for (JsonElement element : sites) {
                if (!element.isJsonObject()) continue;
                JsonObject site = element.getAsJsonObject();
                items.add(site);
                futures.add(pool.submit(() -> probe(client, site)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    result.add(futures.get(i).get(timeout + 3000, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    JsonObject site = items.get(i);
                    JsonObject item = new JsonObject();
                    item.addProperty(KEY, Json.safeString(site, KEY));
                    item.addProperty(NAME, Json.safeString(site, NAME));
                    item.addProperty("state", "fail");
                    item.addProperty("cost", -1);
                    item.addProperty("message", "超时");
                    result.add(item);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private static void reload(Config config) {
        App.post(() -> VodConfig.get().clear().config(config).load(new Callback() {
        }));
    }

    private static JsonObject probe(OkHttpClient client, JsonObject site) {
        String key = Json.safeString(site, KEY);
        String name = Json.safeString(site, NAME);
        String url = probeUrl(site);
        JsonObject item = new JsonObject();
        item.addProperty(KEY, key);
        item.addProperty(NAME, name.isEmpty() ? key : name);
        if (TextUtils.isEmpty(url)) {
            item.addProperty("state", "skip");
            item.addProperty("cost", -1);
            item.addProperty("message", "无可测地址");
            return item;
        }
        long start = System.currentTimeMillis();
        try (Response response = OkHttp.newCall(client, url).execute()) {
            item.addProperty("state", response.isSuccessful() ? "ok" : "fail");
            item.addProperty("cost", System.currentTimeMillis() - start);
            item.addProperty("code", response.code());
            item.addProperty("message", "HTTP " + response.code());
            return item;
        } catch (Exception e) {
            item.addProperty("state", "fail");
            item.addProperty("cost", System.currentTimeMillis() - start);
            item.addProperty("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return item;
        }
    }

    private static String probeUrl(JsonObject site) {
        String api = Json.safeString(site, "api");
        if (isHttp(api)) return api;
        String jar = Json.safeString(site, "jar");
        if (isHttp(jar)) return jar;
        return "";
    }

    private static boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static JsonArray filter(JsonArray array) {
        JsonArray result = new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject site = element.getAsJsonObject();
            if (TextUtils.isEmpty(Json.safeString(site, KEY))) continue;
            result.add(site.deepCopy());
        }
        return result;
    }

    private static int indexOf(JsonArray array, String key) {
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element.isJsonObject() && key.equals(Json.safeString(element.getAsJsonObject(), KEY))) return i;
        }
        return -1;
    }

    private static String text(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        String value = element.toString();
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }
}
