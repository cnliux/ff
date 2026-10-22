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
    private static final String OFF = "off";
    private static final int TEST_THREADS = 16;
    private static final long TEST_TIMEOUT = 4000;
    private static String cachedUrl = "";
    private static JsonObject cachedObject;

    public static Config config() {
        return VodConfig.get().getConfig();
    }

    /**
     * 按接口地址取目标接口配置：target 为空时是当前启用接口；否则从已保存的接口列表中查找，不切换当前启用接口。
     */
    public static Config config(String target) throws Exception {
        if (TextUtils.isEmpty(target)) return config();
        if (target.equals(config().getUrl())) return config();
        for (Config item : Config.getAll(0)) if (target.equals(item.getUrl())) return item;
        Config found = Config.find(target, 0);
        if (found != null && !TextUtils.isEmpty(found.getUrl())) return found;
        throw new Exception("未找到该接口，请先在“接口”中添加");
    }

    public static boolean isManaged() {
        return !TextUtils.isEmpty(config().getJson());
    }

    public static boolean isManaged(String target) throws Exception {
        return !TextUtils.isEmpty(config(target).getJson());
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
        return configJson("");
    }

    /**
     * 读取目标接口配置内容，优先使用本地保存的版本。
     */
    public static JsonObject configJson(String target) throws Exception {
        Config config = config(target);
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
        return sites("");
    }

    /**
     * 目标接口的站源列表，保留原始 JSON 以便导出时不丢字段。
     */
    public static JsonArray sites(String target) throws Exception {
        return sites(configJson(target));
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
            // 是否停用，页面据此展示启停状态
            item.addProperty(OFF, isOff(site) ? 1 : 0);
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
        // storeHouse 分支：转换成站源语义（sourceName/sourceUrl -> key/name/api）
        if (object.has("storeHouse")) {
            JsonElement storeHouse = object.get("storeHouse");
            if (!storeHouse.isJsonArray()) throw new Exception("storeHouse 不是数组");
            JsonArray array = new JsonArray();
            int index = 0;
            for (JsonElement item : storeHouse.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject entry = item.getAsJsonObject();
                String url = Json.safeString(entry, "sourceUrl").trim();
                if (TextUtils.isEmpty(url)) continue;
                String name = Json.safeString(entry, "sourceName").trim();
                JsonObject site = new JsonObject();
                site.addProperty(KEY, TextUtils.isEmpty(name) ? "house_" + index : name);
                site.addProperty(NAME, TextUtils.isEmpty(name) ? url : name);
                site.addProperty("api", url);
                site.addProperty("type", 0);
                array.add(site);
                index++;
            }
            return filter(array);
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
     * 判断站源是否处于停用状态（off=1 或 true）。
     */
    public static boolean isOff(JsonObject site) {
        JsonElement element = site.get(OFF);
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) return element.getAsJsonPrimitive().getAsBoolean();
            return element.getAsJsonPrimitive().isNumber() && element.getAsJsonPrimitive().getAsInt() != 0;
        }
        return false;
    }

    /**
     * 对指定 key 的站源置位或清除停用标记，返回新列表。
     */
    public static JsonArray setOff(JsonArray current, List<String> keys, boolean off) {
        JsonArray result = new JsonArray();
        for (JsonElement element : current) {
            if (!element.isJsonObject()) continue;
            JsonObject site = element.getAsJsonObject().deepCopy();
            if (keys.contains(Json.safeString(site, KEY))) {
                if (off) site.addProperty(OFF, 1);
                else site.remove(OFF);
            }
            result.add(site);
        }
        return result;
    }

    /**
     * 过滤掉停用的站源。
     */
    public static JsonArray filterEnabled(JsonArray sites) {
        JsonArray result = new JsonArray();
        for (JsonElement element : sites) {
            if (!element.isJsonObject()) continue;
            if (isOff(element.getAsJsonObject())) continue;
            result.add(element);
        }
        return result;
    }

    /**
     * 当前接口的启用（非停用）站源列表。
     */
    public static JsonArray enabledOnly() throws Exception {
        return enabledOnly("");
    }

    public static JsonArray enabledOnly(String target) throws Exception {
        return filterEnabled(sites(target));
    }

    /**
     * 当前接口全部站源 key，保持原始顺序。
     */
    public static List<String> keys(JsonArray sites) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : sites) {
            if (!element.isJsonObject()) continue;
            result.add(Json.safeString(element.getAsJsonObject(), KEY));
        }
        return result;
    }

    /**
     * 按 api 地址去重，保留第一个出现的站源。
     */
    public static JsonArray dedupeByApi(JsonArray current) {
        JsonArray result = new JsonArray();
        List<String> seen = new ArrayList<>();
        for (JsonElement element : current) {
            if (!element.isJsonObject()) continue;
            String api = Json.safeString(element.getAsJsonObject(), "api").trim();
            if (!TextUtils.isEmpty(api) && seen.contains(api)) continue;
            if (!TextUtils.isEmpty(api)) seen.add(api);
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
        apply("", sites);
    }

    public static void apply(String target, JsonArray sites) throws Exception {
        apply(target, configJson(target), sites);
    }

    /**
     * 保存站源并重新加载接口配置，复用已读取的接口内容避免重复下载。
     */
    public static void apply(JsonObject object, JsonArray sites) throws Exception {
        apply("", object, sites);
    }

    /**
     * 保存站源并重新加载接口配置，复用已读取的接口内容避免重复下载。
     * target 指向非当前接口时仅保存该接口的源数据，不切换当前启用接口。
     */
    public static void apply(String target, JsonObject object, JsonArray sites) throws Exception {
        Config config = config(target);
        object.add(SITES, sites);
        config.setJson(object.toString());
        config.save();
        clearCache();
        if (TextUtils.isEmpty(target) || target.equals(config().getUrl())) reload(config);
    }

    /**
     * 清除本地保存的站源，恢复接口原始配置。
     */
    public static void reset() throws Exception {
        reset("");
    }

    public static void reset(String target) throws Exception {
        Config config = config(target);
        config.setJson(null);
        config.save();
        clearCache();
        if (TextUtils.isEmpty(target) || target.equals(config().getUrl())) reload(config);
    }

    /**
     * 测速，优先探测 api，其次探测 jar，都没有则跳过。
     */
    public static JsonArray test(JsonArray sites, long timeout) {
        JsonArray result = new JsonArray();
        if (sites == null || sites.isEmpty()) return result;
        // 带 jar 的 csp_ 爬虫源：解析出真实 jar 地址再测速（lambda 引用需 effectively final）
        final String base = currentBaseUrl();
        final String globalSpider = currentGlobalSpider();
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
                futures.add(pool.submit(() -> probe(client, site, base, globalSpider)));
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

    /**
     * 当前启用接口的地址，用于解析相对路径 jar；无接口时返回空。
     */
    private static String currentBaseUrl() {
        try {
            Config config = config();
            return config.isEmpty() ? "" : config.getUrl();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 当前接口配置的全局 spider 地址，用于无 jar 字段的 csp_ 源回退测速。
     */
    private static String currentGlobalSpider() {
        try {
            return Json.safeString(configJson(), "spider");
        } catch (Exception e) {
            return "";
        }
    }

    private static void reload(Config config) {
        App.post(() -> VodConfig.get().clear().config(config).load(new Callback() {
        }));
    }

    private static JsonObject probe(OkHttpClient client, JsonObject site, String base, String globalSpider) {
        String key = Json.safeString(site, KEY);
        String name = Json.safeString(site, NAME);
        String url = probeUrl(site, base, globalSpider);
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
        return probeUrl(site, "", "");
    }

    /**
     * 测速地址选择：优先 http api；csp_ 爬虫源解析相对路径 jar / 回退全局 spider，assets 本地 jar 跳过。
     */
    private static String probeUrl(JsonObject site, String base, String globalSpider) {
        String api = Json.safeString(site, "api");
        if (isHttp(api)) return api;
        String jar = resolveJar(Json.safeString(site, "jar"), base);
        if (!TextUtils.isEmpty(jar)) return jar;
        return resolveJar(globalSpider, base);
    }

    /**
     * 把 jar 地址解析为可测速的 http 地址：相对路径按接口地址解析，assets/data 本地地址返回空。
     */
    private static String resolveJar(String jar, String base) {
        if (TextUtils.isEmpty(jar)) return "";
        jar = jar.trim();
        if (jar.startsWith("assets://") || jar.startsWith("data:") || jar.startsWith("asset://")) return "";
        if (isHttp(jar)) return jar;
        if (TextUtils.isEmpty(base)) return "";
        try {
            String resolved = UrlUtil.resolve(base, jar);
            return isHttp(resolved) ? resolved : "";
        } catch (Exception e) {
            return "";
        }
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
