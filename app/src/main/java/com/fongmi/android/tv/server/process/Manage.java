package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.SyncOptions;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.ServerAuth;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.ManageService;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SourceSetting;
import com.fongmi.android.tv.utils.ProgressRequestBody;
import com.fongmi.android.tv.utils.LoginStateSync;
import com.fongmi.android.tv.utils.ScanTask;
import com.fongmi.android.tv.utils.SyncFiles;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FilterInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class Manage implements Process {

    private static final int TREE_LIMIT = 300;
    private static final int LOGIN_STATE_FINDINGS_LIMIT = 300;
    private static final int SOURCE_TEST_TIMEOUT = 65000;
    private static final MediaType ZIP = MediaType.parse("application/zip");

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/manage/") || url.startsWith("/api/sources");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            ManageService.touch();
            // spec 别名：/api/sources/* 归一化为 /manage/sources/* 走同一处理逻辑
            if (url.startsWith("/api/sources")) url = "/manage/sources" + url.substring("/api/sources".length());
            if (url.equals("/manage/session")) return session(session.getParms());
            if (url.equals("/manage/security")) return security(session.getParms());
            if (url.equals("/manage/background/settings")) return backgroundSettings(session);
            if (url.equals("/manage/devices")) return devices(session.getParms());
            if (url.equals("/manage/remote/ping")) return remotePing(session.getParms());
            if (url.equals("/manage/remote/pair")) return remotePair(session.getParms());
            if (url.equals("/manage/remote/media")) return remoteMedia(session.getParms());
            if (url.equals("/manage/action")) return forwardTo(session, "/action");
            if (url.equals("/manage/remote/file")) return remoteFile(session.getParms());
            if (url.equals("/manage/remote/archive")) return remoteArchive(session.getParms());
            if (url.equals("/manage/remote/upload")) return remoteUpload(session.getParms(), files);
            if (url.equals("/manage/remote/newFolder")) return forwardTo(session, "/newFolder");
            if (url.equals("/manage/remote/delFolder")) return forwardTo(session, "/delFolder");
            if (url.equals("/manage/remote/delFile")) return forwardTo(session, "/delFile");
            Response forwarded = forward(session, url);
            if (forwarded != null) return forwarded;
            return switch (url) {
                case "/manage/sync/paths" -> syncPaths(session.getParms());
                case "/manage/sync/tree" -> syncTree(session.getParms());
                case "/manage/sync/detect" -> detectSyncPaths();
                case "/manage/sync/start" -> syncStart(session.getParms());
                case "/manage/file/archive" -> fileArchive(session.getParms());
                case "/manage/file/tree" -> fileTree(session.getParms());
                case "/manage/configs" -> configs(session.getParms());
                case "/manage/config/use" -> configUse(session.getParms());
                case "/manage/config/delete" -> configDelete(session.getParms());
                case "/manage/login-state" -> loginState();
                case "/manage/login-state/file" -> loginStateFile(session.getParms());
                case "/manage/login-state/learn" -> loginStateLearn(session.getParms());
                case "/manage/login-state/paths" -> loginStatePaths(session.getParms());
                case "/manage/login-state/confirm" -> loginStateConfirm();
                case "/manage/login-state/tree" -> loginStateTree(session.getParms());
                case "/manage/proxy" -> proxy(session.getParms());
                case "/manage/proxy/suggest/sites" -> proxySuggestSites();
                case "/manage/proxy/suggest" -> proxySuggest(session.getParms());
                case "/manage/csp/page" -> cspPage(session.getParms());
                case "/manage/csp" -> csp(session.getParms());
                case "/manage/sources" -> sources(session.getParms());
                case "/manage/sources/import" -> sourcesImport(session.getParms());
                case "/manage/sources/delete" -> sourcesDelete(session.getParms());
                case "/manage/sources/delete-all" -> sourcesDeleteAll(session.getParms());
                case "/manage/sources/order" -> sourcesOrder(session.getParms());
                case "/manage/sources/export" -> sourcesExport(session.getParms());
                case "/manage/sources/test" -> sourcesTest(session.getParms());
                case "/manage/sources/reset" -> sourcesReset(session.getParms());
                case "/manage/sources/enable" -> sourcesSetOff(session.getParms(), false);
                case "/manage/sources/disable" -> sourcesSetOff(session.getParms(), true);
                case "/manage/sources/enable-all" -> sourcesSetOffAll(session.getParms(), false);
                case "/manage/sources/disable-all" -> sourcesSetOffAll(session.getParms(), true);
                case "/manage/sources/block-invalid" -> sourcesSetOffInvalid(session.getParms(), true);
                case "/manage/sources/delete-invalid" -> sourcesSetOffInvalid(session.getParms(), false);
                case "/manage/sources/delete-duplicate" -> sourcesDeleteDuplicate(session.getParms());
                case "/manage/sources/subscription/import" -> sourcesSubscriptionImport(session.getParms());
                case "/manage/sources/subscription/list" -> sourcesSubscriptionList();
                case "/manage/sources/subscription/update" -> sourcesSubscriptionUpdate(session.getParms());
                case "/manage/sources/subscription/update-all" -> sourcesSubscriptionUpdateAll();
                case "/manage/sources/subscription/delete" -> sourcesSubscriptionDelete(session.getParms());
                default -> Nano.error(Status.NOT_FOUND, "Not found");
            };
        } catch (Exception e) {
            return Nano.error(e.getMessage());
        }
    }

    private Response security(Map<String, String> params) {
        String mode = params.get("ipMode");
        if (!TextUtils.isEmpty(mode)) ServerAuth.setIpMode(mode);
        JsonObject object = new JsonObject();
        object.addProperty("tokenRequired", false);
        object.addProperty("ipMode", ServerAuth.ipMode());
        object.addProperty("time", System.currentTimeMillis());
        return json(object);
    }

    private Response session(Map<String, String> params) {
        if (bool(params.get("stop"), false)) ManageService.stop(App.get());
        else if (bool(params.get("close"), false)) ManageService.closeSoon();
        else ManageService.touch();
        JsonObject object = new JsonObject();
        object.addProperty("running", ManageService.isRunning());
        object.addProperty("serverRunning", com.fongmi.android.tv.server.Server.get().isRunning());
        object.addProperty("localUrl", ManageService.getLocalUrl());
        object.addProperty("lanUrl", ManageService.getLanUrl());
        object.addProperty("batteryOptimized", !ManageService.isIgnoringBatteryOptimizations(App.get()));
        object.addProperty("backgroundSettingsNeeded", ManageService.shouldOpenBackgroundPowerSettings(App.get()));
        object.addProperty("backgroundGuided", ManageService.isBackgroundPowerGuided());
        object.addProperty("backgroundGuide", ManageService.getBackgroundPowerGuide(App.get()));
        object.addProperty("wakeLock", ManageService.isWakeLockHeld());
        object.addProperty("wifiLock", ManageService.isWifiLockHeld());
        object.addProperty("lastAccess", ManageService.getLastAccess());
        object.addProperty("idleTimeout", ManageService.getIdleTimeout());
        object.addProperty("time", System.currentTimeMillis());
        return json(object);
    }

    private Response backgroundSettings(IHTTPSession session) throws IOException {
        String target = session.getParms().get("target");
        if (!TextUtils.isEmpty(target)) return forwardTo(session, "/manage/background/settings");
        boolean opened = ManageService.openBackgroundPowerSettings(App.get());
        JsonObject object = new JsonObject();
        object.addProperty("opened", opened);
        object.addProperty("backgroundSettingsNeeded", ManageService.shouldOpenBackgroundPowerSettings(App.get()));
        object.addProperty("batteryOptimized", !ManageService.isIgnoringBatteryOptimizations(App.get()));
        object.addProperty("guide", ManageService.getBackgroundPowerGuide(App.get()));
        return json(object);
    }

    private Response devices(Map<String, String> params) {
        if (bool(params.get("scan"), false)) new ScanTask(new ScanTask.Listener() {
            @Override
            public void onFind(Device device) {
            }
        }).start();
        JsonObject object = new JsonObject();
        object.add("local", publicDevice(Device.get()));
        JsonArray devices = new JsonArray();
        for (Device device : Device.getAll()) if (device.isApp() && !Device.get().equals(device)) devices.add(publicDevice(device));
        object.add("devices", devices);
        return json(object);
    }

    private JsonObject publicDevice(Device device) {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", device.getUuid());
        object.addProperty("name", device.getName());
        object.addProperty("ip", device.getIp());
        object.addProperty("type", device.getType());
        return object;
    }

    private Response remotePair(Map<String, String> params) {
        String target = stripQuery(params.get("target"));
        if (!isValidTarget(target)) return Nano.error(Status.BAD_REQUEST, "Invalid target");
        try {
            String text = remoteGet(target, "/device");
            Device device = Device.objectFrom(text);
            if (device == null || TextUtils.isEmpty(device.getUuid())) return Nano.error(Status.BAD_REQUEST, "Invalid device response");
            device.setIp(target);
            for (Device saved : Device.getAll()) {
                if (saved.getUuid().equals(device.getUuid())) {
                    device.setToken(saved.getToken());
                    break;
                }
            }
            device.save();
            return json(publicDevice(device));
        } catch (Exception e) {
            return Nano.error(Status.BAD_REQUEST, "Pairing failed");
        }
    }

    private Response remotePing(Map<String, String> params) {
        String target = params.get("target");
        JsonObject object = new JsonObject();
        object.addProperty("target", target == null ? "" : stripQuery(target));
        object.addProperty("time", System.currentTimeMillis());
        if (TextUtils.isEmpty(target) || !isValidTarget(target)) {
            object.addProperty("ok", false);
            object.addProperty("message", "Invalid target");
            return json(object);
        }
        try {
            String text = remoteGet(stripQuery(target), "/device");
            object.addProperty("ok", !TextUtils.isEmpty(text));
            if (!TextUtils.isEmpty(text)) object.add("device", publicDevice(Device.objectFrom(text)));
        } catch (Exception e) {
            object.addProperty("ok", false);
            object.addProperty("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return json(object);
    }

    private String remoteGet(String target, String path) throws IOException {
        okhttp3.OkHttpClient client = OkHttp.client(2500).newBuilder().followRedirects(false).followSslRedirects(false).build();
        Request request = new Request.Builder().url(remoteUrl(target, path)).get().build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return text;
        }
    }

    private Response remoteMedia(Map<String, String> params) throws IOException {
        String target = params.get("target");
        if (TextUtils.isEmpty(target) || !isValidTarget(target)) return Nano.error(Status.BAD_REQUEST, "Invalid target");
        String text;
        try {
            text = remoteGet(stripQuery(target), "/media");
        } catch (IOException e) {
            return Nano.error(Status.INTERNAL_ERROR, e.getMessage() == null ? "Remote media failed" : e.getMessage());
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", text);
    }

    private Response forward(IHTTPSession session, String url) throws IOException {
        if (!remoteForwardAllowed(url)) return null;
        return forwardTo(session, url, url.startsWith("/manage/sources/test") ? SOURCE_TEST_TIMEOUT : 5000);
    }

    private boolean remoteForwardAllowed(String url) {
        return "/action".equals(url) || "/file".equals(url) || url.startsWith("/file/") || "/upload".equals(url) || "/newFolder".equals(url) || "/delFolder".equals(url) || "/delFile".equals(url) || "/manage/file/archive".equals(url) || url.startsWith("/manage/sources");
    }

    private Response forwardTo(IHTTPSession session, String url) throws IOException {
        return forwardTo(session, url, 5000);
    }

    private Response forwardTo(IHTTPSession session, String url, int timeout) throws IOException {
        String target = session.getParms().get("target");
        if (TextUtils.isEmpty(target) || !remoteForwardAllowed(url)) return null;
        String remote = remoteUrl(stripQuery(target), url);
        FormBody body = buildForwardBody(session.getParms());
        okhttp3.OkHttpClient client = OkHttp.client(timeout).newBuilder().followRedirects(false).followSslRedirects(false).build();
        Request request = new Request.Builder().url(remote).post(body).build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            Status status = response.isSuccessful() ? Status.OK : Status.lookup(response.code());
            return NanoHTTPD.newFixedLengthResponse(status == null ? Status.INTERNAL_ERROR : status, response.header("Content-Type", "text/plain; charset=utf-8"), text);
        }
    }

    private Response remoteFile(Map<String, String> params) throws IOException {
        String target = params.get("target");
        if (TextUtils.isEmpty(target)) return Nano.error(Status.BAD_REQUEST, "Missing target");
        String path = params.getOrDefault("path", "");
        String remote = remoteUrl(stripQuery(target), "/file" + encodePath(path) + (bool(params.get("download"), false) ? "?download=1" : ""));
        okhttp3.OkHttpClient client = OkHttp.client(30000).newBuilder().followRedirects(false).followSslRedirects(false).build();
        okhttp3.Response response = client.newCall(new Request.Builder().url(remote).build()).execute();
        ResponseBody responseBody = response.body();
        if (responseBody == null) return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Empty response");
        Status status = response.isSuccessful() ? Status.OK : Status.lookup(response.code());
        InputStream input = new FilterInputStream(responseBody.byteStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    response.close();
                }
            }
        };
        Response result = NanoHTTPD.newFixedLengthResponse(status == null ? Status.INTERNAL_ERROR : status, response.header("Content-Type", "application/octet-stream"), input, responseBody.contentLength());
        String disposition = response.header("Content-Disposition");
        if (!TextUtils.isEmpty(disposition)) result.addHeader("Content-Disposition", disposition);
        return result;
    }

    private Response remoteUpload(Map<String, String> params, Map<String, String> files) throws IOException {
        String target = params.get("target");
        if (TextUtils.isEmpty(target)) return Nano.error(Status.BAD_REQUEST, "Missing target");
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
        body.addFormDataPart("path", params.getOrDefault("path", ""));
        for (String key : files.keySet()) {
            File temp = new File(files.get(key));
            String name = params.getOrDefault(key, temp.getName());
            body.addFormDataPart(key, name, RequestBody.create(MediaType.parse("application/octet-stream"), temp));
        }
        String remote = remoteUrl(stripQuery(target), "/upload");
        okhttp3.OkHttpClient client = OkHttp.client(30000).newBuilder().followRedirects(false).followSslRedirects(false).build();
        try (okhttp3.Response response = client.newCall(new Request.Builder().url(remote).post(body.build()).build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            Status status = response.isSuccessful() ? Status.OK : Status.lookup(response.code());
            return NanoHTTPD.newFixedLengthResponse(status == null ? Status.INTERNAL_ERROR : status, response.header("Content-Type", "text/plain; charset=utf-8"), text);
        }
    }

    private Response remoteArchive(Map<String, String> params) throws IOException {
        String target = params.get("target");
        if (TextUtils.isEmpty(target)) return Nano.error(Status.BAD_REQUEST, "Missing target");
        String remote = remoteUrl(stripQuery(target), "/manage/file/archive");
        FormBody body = buildForwardBody(params);
        okhttp3.OkHttpClient client = OkHttp.client(60000).newBuilder().followRedirects(false).followSslRedirects(false).build();
        okhttp3.Response response = client.newCall(new Request.Builder().url(remote).post(body).build()).execute();
        ResponseBody responseBody = response.body();
        if (responseBody == null) return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Empty response");
        Status status = response.isSuccessful() ? Status.OK : Status.lookup(response.code());
        InputStream input = new FilterInputStream(responseBody.byteStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    response.close();
                }
            }
        };
        Response result = NanoHTTPD.newFixedLengthResponse(status == null ? Status.INTERNAL_ERROR : status, response.header("Content-Type", "application/zip"), input, responseBody.contentLength());
        String disposition = response.header("Content-Disposition");
        if (!TextUtils.isEmpty(disposition)) result.addHeader("Content-Disposition", disposition);
        return result;
    }

    private String encodePath(String path) {
        if (TextUtils.isEmpty(path)) return "";
        StringBuilder builder = new StringBuilder();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append('/');
            builder.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return builder.toString();
    }

    private String remoteUrl(String target, String path) {
        String route = path;
        int query = route.indexOf('?');
        if (query >= 0) route = route.substring(0, query);
        if (!isValidTarget(target) || !remoteForwardAllowed(route) && !"/device".equals(route) && !"/media".equals(route)) throw new IllegalArgumentException("Invalid target host or path");
        String base = stripQuery(target);
        return base + path;
    }

    private boolean isValidTarget(String target) {
        if (TextUtils.isEmpty(target)) return false;
        try {
            URI uri = URI.create(target.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) return false;
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPath() == null || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) return false;
            int port = uri.getPort();
            if (port < 9978 || port > 9999) return false;
            String host = uri.getHost();
            if (TextUtils.isEmpty(host)) return false;
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (!addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private String stripQuery(String target) {
        int index = target.indexOf('?');
        String base = index == -1 ? target : target.substring(0, index);
        return base.replaceAll("/+$", "");
    }

    private String syncEncryptionKey(String target) {
        String clean = stripQuery(target);
        for (Device device : Device.getAll()) {
            if (clean.equals(stripQuery(device.getIp())) && !TextUtils.isEmpty(device.getToken())) return device.getToken();
        }
        // 浏览器访问不再设置 token；没有历史保存的内部密钥时按旧版兼容方式同步。
        return "";
    }

    private FormBody buildForwardBody(Map<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("target".equals(entry.getKey())) continue;
            body.add(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return body.build();
    }

    private Response syncPaths(Map<String, String> params) {
        if (params.containsKey("paths")) Setting.putSyncPaths(SyncFiles.getPathsText(SyncFiles.getPaths(params.get("paths"))));
        JsonObject object = new JsonObject();
        List<String> paths = SyncFiles.getPaths(Setting.getSyncPaths());
        object.add("paths", array(paths));
        object.addProperty("text", SyncFiles.getPathsText(paths));
        return json(object);
    }

    private Response syncTree(Map<String, String> params) throws Exception {
        String path = SyncFiles.normalize(params.get("path"));
        File root = Path.root().getCanonicalFile();
        File dir = path.isEmpty() ? root : new File(root, path).getCanonicalFile();
        if (!inside(root, dir) || !dir.isDirectory()) return Nano.error(Status.NOT_FOUND, "Directory not found");
        JsonObject object = new JsonObject();
        object.addProperty("path", path);
        object.addProperty("parent", parentOf(root, dir));
        JsonArray dirs = new JsonArray();
        File[] files = dir.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        if (files != null) Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int count = 0;
        for (File file : files == null ? new File[0] : files) {
            if (count++ >= TREE_LIMIT) break;
            JsonObject item = new JsonObject();
            item.addProperty("name", file.getName());
            item.addProperty("path", relativeTo(root, file));
            dirs.add(item);
        }
        object.add("dirs", dirs);
        object.addProperty("truncated", files != null && files.length > TREE_LIMIT);
        return json(object);
    }

    private Response fileTree(Map<String, String> params) throws Exception {
        String path = cleanRelativePath(params.getOrDefault("path", ""));
        File root = Path.root().getCanonicalFile();
        File dir = path.isEmpty() ? root : new File(root, path).getCanonicalFile();
        if (!inside(root, dir) || !dir.isDirectory()) return Nano.error(Status.NOT_FOUND, "Directory not found");
        JsonObject object = new JsonObject();
        object.addProperty("path", path);
        object.addProperty("parent", parentOf(root, dir));
        JsonArray dirs = new JsonArray();
        File[] files = dir.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        if (files != null) Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int count = 0;
        for (File file : files == null ? new File[0] : files) {
            if (count++ >= TREE_LIMIT) break;
            JsonObject item = new JsonObject();
            item.addProperty("name", file.getName());
            item.addProperty("path", relativeTo(root, file));
            item.addProperty("children", hasVisibleDirs(file));
            dirs.add(item);
        }
        object.add("dirs", dirs);
        object.addProperty("truncated", files != null && files.length > TREE_LIMIT);
        return json(object);
    }

    private Response configs(Map<String, String> params) {
        if (params.containsKey("url")) {
            int type = intValue(params.get("type"), 0);
            String url = params.getOrDefault("url", "").trim();
            String name = params.getOrDefault("name", "").trim();
            if (TextUtils.isEmpty(url)) return Nano.error(Status.BAD_REQUEST, "Missing url");
            Config config = Config.find(url, type).name(name);
            config.save();
        }
        JsonObject object = new JsonObject();
        JsonArray items = new JsonArray();
        for (int type = 0; type <= 2; type++) {
            for (Config config : Config.getAll(type)) items.add(configObject(config, false));
            Config current = currentConfig(type);
            if (!current.isEmpty() && !containsConfig(items, current)) items.add(configObject(current, true));
        }
        object.add("items", items);
        return json(object);
    }

    private Response configUse(Map<String, String> params) {
        int type = intValue(params.get("type"), 0);
        String url = params.getOrDefault("url", "").trim();
        if (TextUtils.isEmpty(url)) return Nano.error(Status.BAD_REQUEST, "Missing url");
        Config config = Config.find(url, type);
        if (config.isEmpty()) return Nano.error(Status.NOT_FOUND, "Config not found");
        switch (type) {
            case 1 -> LiveConfig.load(config, new Callback());
            case 2 -> WallConfig.load(config, new Callback());
            default -> VodConfig.load(config, new Callback());
        }
        return configs(java.util.Collections.emptyMap());
    }

    private Response configDelete(Map<String, String> params) {
        int type = intValue(params.get("type"), 0);
        String url = params.getOrDefault("url", "").trim();
        if (TextUtils.isEmpty(url)) return Nano.error(Status.BAD_REQUEST, "Missing url");
        Config.find(url, type).delete();
        return configs(java.util.Collections.emptyMap());
    }

    private JsonObject configObject(Config config, boolean forceActive) {
        JsonObject item = new JsonObject();
        item.addProperty("type", config.getType());
        item.addProperty("typeName", configTypeName(config.getType()));
        item.addProperty("name", config.getName());
        item.addProperty("url", config.getUrl());
        item.addProperty("desc", config.getDesc());
        item.addProperty("time", config.getTime());
        item.addProperty("active", forceActive || isCurrentConfig(config));
        return item;
    }

    private boolean containsConfig(JsonArray items, Config config) {
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (item.get("type").getAsInt() == config.getType() && item.get("url").getAsString().equals(config.getUrl())) return true;
        }
        return false;
    }

    private boolean isCurrentConfig(Config config) {
        Config current = currentConfig(config.getType());
        return current.getUrl().equals(config.getUrl());
    }

    private Config currentConfig(int type) {
        return switch (type) {
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> VodConfig.get().getConfig();
        };
    }

    private String configTypeName(int type) {
        return switch (type) {
            case 1 -> "直播";
            case 2 -> "壁纸";
            default -> "影视";
        };
    }

    private String cleanRelativePath(String value) {
        String path = value == null ? "" : value.trim();
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    private int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Response fileArchive(Map<String, String> params) throws IOException {
        String text = params.getOrDefault("paths", params.getOrDefault("path", ""));
        if (TextUtils.isEmpty(text)) return Nano.error(Status.BAD_REQUEST, "Missing paths");
        List<String> paths = SyncFiles.getPaths(text);
        if (paths.isEmpty()) return Nano.error(Status.BAD_REQUEST, "Missing paths");
        SyncFiles.Archive archive = SyncFiles.createArchive(paths);
        if (archive == null) return Nano.error(Status.NOT_FOUND, "No files to archive");
        InputStream input = new FilterInputStream(new FileInputStream(archive.getFile())) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    archive.delete();
                }
            }
        };
        Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/zip", input, archive.getFile().length());
        addDownloadHeader(response, archiveName(paths));
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private String archiveName(List<String> paths) {
        if (paths.size() == 1) {
            String path = paths.get(0);
            String name = path.substring(path.lastIndexOf('/') + 1).trim();
            if (!name.isEmpty()) return name + ".zip";
        }
        return "webhtv-files.zip";
    }

    private void addDownloadHeader(Response response, String name) {
        String fallback = name.replaceAll("[\\\\\"\\r\\n]", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        response.addHeader("Content-Disposition", "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded);
    }

    private Response detectSyncPaths() {
        Set<String> detected = new LinkedHashSet<>(SyncFiles.getPaths(Setting.getSyncPaths()));
        for (int type = 0; type <= 2; type++) for (Config config : Config.getAll(type)) addConfiguredPath(detected, config.getUrl());
        List<String> paths = detected.stream().toList();
        Setting.putSyncPaths(SyncFiles.getPathsText(paths));
        JsonObject object = new JsonObject();
        object.add("paths", array(paths));
        object.addProperty("text", SyncFiles.getPathsText(paths));
        return json(object);
    }

    private void addConfiguredPath(Set<String> paths, String value) {
        String candidate = localCandidate(value);
        if (TextUtils.isEmpty(candidate)) return;
        File file = Path.local(candidate);
        if (!file.exists()) return;
        String path = relativeLocalPath(file.isDirectory() ? file : file.getParentFile());
        if (!path.isEmpty() && !coveredBy(paths, path)) paths.add(path);
    }

    private String localCandidate(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String text = value.trim();
        int cut = text.indexOf('#');
        if (cut >= 0) text = text.substring(0, cut);
        cut = text.indexOf('?');
        if (cut >= 0) text = text.substring(0, cut);
        String lower = text.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("proxy://") || lower.startsWith("assets://")) return "";
        if (lower.startsWith("file:") || text.startsWith("/")) return text;
        if (lower.contains("://")) return "";
        return text.contains("/") || text.contains("\\") ? text : "";
    }

    private String relativeLocalPath(File file) {
        try {
            if (file == null) return "";
            File root = Path.root().getCanonicalFile();
            File target = file.getCanonicalFile();
            return inside(root, target) ? relativeTo(root, target) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean coveredBy(Set<String> paths, String path) {
        for (String existing : paths) if (covers(existing, path)) return true;
        return false;
    }

    private boolean covers(String parent, String child) {
        String p = SyncFiles.normalize(parent);
        String c = SyncFiles.normalize(child);
        return !p.isEmpty() && (p.equals(c) || c.startsWith(p + "/"));
    }

    private Response syncStart(Map<String, String> params) throws IOException {
        String device = params.get("device");
        if (TextUtils.isEmpty(device)) return Nano.error(Status.BAD_REQUEST, "Missing device");
        String direction = params.getOrDefault("mode", "push");
        boolean pull = "pull".equalsIgnoreCase(direction) || "2".equals(direction);
        SyncOptions options = SyncOptions.objectFrom(params.get("options"));
        if (params.containsKey("paths")) options.paths(params.get("paths"));
        SyncFiles.Archive archive = null;
        LoginStateSync.Archive loginArchive = null;
        try {
            if (!pull && options.isSpider()) archive = SyncFiles.createArchive(SyncFiles.getPaths(options.getPaths()));
            if (!pull && options.isLoginState()) {
                loginArchive = LoginStateSync.createArchive();
                // HTTP 不再使用 token 鉴权；登录态归档仍使用接收设备保存的内部密钥加密。
                if (loginArchive != null) loginArchive = LoginStateSync.encrypt(loginArchive, syncEncryptionKey(device));
            }
            RequestBody body = buildSyncBody(pull, options, archive, loginArchive);
            String remote = remoteUrl(stripQuery(device), "/action?do=sync&mode=" + (pull ? "2" : "1") + "&type=backup");
            SpiderDebug.log("sync", "manage start direction=%s device=%s options=%s archive=%s loginArchive=%s", pull ? "pull" : "push", device, options, archive == null ? "none" : archive.getFile().getAbsolutePath(), loginArchive == null ? "none" : loginArchive.getFile().getAbsolutePath());
            okhttp3.OkHttpClient client = OkHttp.client(Constant.TIMEOUT_SYNC_TRANSFER).newBuilder().followRedirects(false).followSslRedirects(false).build();
            try (okhttp3.Response response = client.newCall(new Request.Builder().url(remote).post(body).build()).execute()) {
                if (!response.isSuccessful()) {
                    ResponseBody responseBody = response.body();
                    String text = responseBody == null ? response.message() : responseBody.string();
                    Status status = Status.lookup(response.code());
                    return Nano.error(status == null ? Status.INTERNAL_ERROR : status, TextUtils.isEmpty(text) ? "Sync failed" : text);
                }
            }
            JsonObject object = new JsonObject();
            object.addProperty("ok", true);
            object.addProperty("mode", pull ? "pull" : "push");
            if (archive != null) {
                object.addProperty("files", archive.getCount());
                object.addProperty("rawSize", archive.getRawSize());
                object.addProperty("zipSize", archive.getZipSize());
            }
            if (loginArchive != null) {
                object.addProperty("loginStateFiles", loginArchive.getCount());
                object.addProperty("loginStateRawSize", loginArchive.getRawSize());
                object.addProperty("loginStateZipSize", loginArchive.getZipSize());
            }
            return json(object);
        } finally {
            if (archive != null) archive.delete();
            if (loginArchive != null) loginArchive.delete();
        }
    }

    private RequestBody buildSyncBody(boolean pull, SyncOptions options, SyncFiles.Archive archive, LoginStateSync.Archive loginArchive) {
        if (pull) {
            FormBody.Builder body = new FormBody.Builder();
            body.add("options", options.toString());
            body.add("force", "false");
            body.add("device", Device.get().toString());
            return body.build();
        }
        if (archive == null && loginArchive == null) {
            FormBody.Builder body = new FormBody.Builder();
            body.add("options", options.toString());
            body.add("force", "false");
            body.add("backup", Backup.create(options).toString());
            return body.build();
        }
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
        body.addFormDataPart("options", options.toString());
        body.addFormDataPart("force", "false");
        body.addFormDataPart("backup", Backup.create(options).toString());
        if (archive != null) {
            body.addFormDataPart(SyncFiles.PART_NAME, archive.getFile().getName(), new ProgressRequestBody(archive.getFile(), ZIP, null));
        }
        if (loginArchive != null) {
            body.addFormDataPart(LoginStateSync.PART_NAME, loginArchive.getFile().getName(), new ProgressRequestBody(loginArchive.getFile(), ZIP, null));
        }
        return body.build();
    }

    private Response loginState() {
        return json(loginStateObject());
    }

    private Response loginStateLearn(Map<String, String> params) {
        String action = params.getOrDefault("action", "begin");
        JsonObject object;
        if ("finish".equalsIgnoreCase(action)) {
            LoginStateSync.LearnResult result = LoginStateSync.finishLearning();
            object = loginStateObject();
            object.add("selectedNow", array(result.getSelected()));
            object.add("pendingNow", array(result.getPending()));
            object.addProperty("finished", result.isLearned());
        } else {
            LoginStateSync.beginLearning();
            object = loginStateObject();
            object.addProperty("started", true);
        }
        return json(object);
    }

    private Response loginStateFile(Map<String, String> params) throws IOException {
        String path = params.get("path");
        if (TextUtils.isEmpty(path)) return Nano.error(Status.BAD_REQUEST, "Missing path");
        if (params.containsKey("content")) LoginStateSync.write(path, params.get("content"));
        LoginStateSync.TextPreview preview = LoginStateSync.preview(path);
        JsonObject object = new JsonObject();
        object.addProperty("path", preview.getPath());
        object.addProperty("displayPath", preview.getDisplayPath());
        object.addProperty("content", preview.getContent());
        object.addProperty("size", preview.getSize());
        object.addProperty("truncated", preview.isTruncated());
        object.addProperty("editable", preview.isEditable());
        object.addProperty("text", preview.isText());
        object.addProperty("encoding", preview.getEncoding());
        return json(object);
    }

    private Response loginStatePaths(Map<String, String> params) {
        if (params.containsKey("paths")) LoginStateSync.savePaths(Arrays.asList(params.get("paths").split("[\\r\\n]+")));
        return loginState();
    }

    private Response loginStateTree(Map<String, String> params) {
        LoginStateSync.Tree tree = LoginStateSync.tree(params.getOrDefault("path", ""));
        JsonObject object = new JsonObject();
        object.addProperty("path", tree.getPath());
        object.addProperty("parent", tree.getParent());
        object.addProperty("valid", tree.isValid());
        object.add("items", App.gson().toJsonTree(tree.getItems()));
        object.addProperty("total", tree.getTotal());
        object.addProperty("truncated", tree.isTruncated());
        return json(object);
    }

    private Response loginStateConfirm() {
        LoginStateSync.confirmPending();
        return loginState();
    }

    private JsonObject loginStateObject() {
        List<String> learned = LoginStateSync.learnedPaths();
        List<String> pending = LoginStateSync.pendingPaths();
        List<LoginStateSync.Candidate> findings = LoginStateSync.findings();
        int findingsTotal = findings.size();
        if (findingsTotal > LOGIN_STATE_FINDINGS_LIMIT) findings = findings.subList(0, LOGIN_STATE_FINDINGS_LIMIT);
        JsonObject object = new JsonObject();
        object.addProperty("learning", LoginStateSync.hasLearningSnapshot());
        object.addProperty("learnedCount", learned.size());
        object.addProperty("pendingCount", pending.size());
        object.add("learned", array(learned));
        object.add("pending", array(pending));
        object.add("states", App.gson().toJsonTree(LoginStateSync.pathStates(learned)));
        object.add("findings", App.gson().toJsonTree(findings));
        object.addProperty("findingsTotal", findingsTotal);
        object.addProperty("findingsTruncated", findingsTotal > findings.size());
        object.addProperty("pathsText", LoginStateSync.pathsText(learned));
        return object;
    }

    private Response proxy(Map<String, String> params) {
        if (params.containsKey("enabled") || params.containsKey("url") || params.containsKey("rules")) {
            boolean enabled = bool(params.get("enabled"), Setting.isShellProxy());
            String url = params.getOrDefault("url", Setting.getShellProxyUrl());
            String rules = params.getOrDefault("rules", Setting.getShellProxyRules());
            if (enabled && !ProxySetting.isValidRules(rules, url)) return Nano.error(Status.BAD_REQUEST, "Invalid proxy rules");
            if (!enabled) Setting.putShellProxy(false);
            Setting.putShellProxyConfig(url, rules);
            if (enabled) Setting.putShellProxy(true);
        }
        JsonObject object = new JsonObject();
        object.addProperty("enabled", Setting.isShellProxy());
        object.addProperty("url", Setting.getShellProxyUrl());
        object.addProperty("rules", Setting.getShellProxyRules());
        object.addProperty("count", ProxySetting.count());
        object.addProperty("valid", ProxySetting.isValidRules(Setting.getShellProxyRules(), Setting.getShellProxyUrl()));
        return json(object);
    }

    private Response proxySuggestSites() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        String homeKey = VodConfig.get().getHome().getKey();
        for (Site site : VodConfig.get().getSites()) {
            if (site.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("key", site.getKey());
            item.addProperty("name", TextUtils.isEmpty(site.getName()) ? site.getKey() : site.getName());
            item.addProperty("home", site.getKey().equals(homeKey));
            array.add(item);
        }
        object.add("sites", array);
        object.addProperty("count", array.size());
        return json(object);
    }

    private Response proxySuggest(Map<String, String> params) {
        String key = params.getOrDefault("key", "");
        boolean all = bool(params.get("all"), false) || "all".equalsIgnoreCase(key);
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isEmpty()).toList();
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (all) {
            for (Site site : sites) addProxySuggestion(site, hosts, urls);
        } else {
            Site site = sites.stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(VodConfig.get().getHome());
            if (!site.isEmpty()) addProxySuggestion(site, hosts, urls);
        }
        JsonObject object = new JsonObject();
        object.add("hosts", array(hosts));
        object.add("urls", array(urls));
        object.addProperty("count", hosts.size());
        return json(object);
    }

    private void addProxySuggestion(Site site, LinkedHashSet<String> hosts, LinkedHashSet<String> urls) {
        ProxySetting.Suggestion suggestion = ProxySetting.suggest(site);
        hosts.addAll(suggestion.hosts());
        urls.addAll(suggestion.urls());
    }

    private Response csp(Map<String, String> params) throws Exception {
        if (params.containsKey("registry")) {
            CustomCspSetting.Registry registry = CustomCspSetting.parse(params.get("registry"));
            CustomCspSetting.save(registry);
            reloadConfigs();
        }
        CustomCspSetting.Registry registry = CustomCspSetting.load();
        CustomCspSetting.Count count = CustomCspSetting.count();
        JsonObject object = App.gson().toJsonTree(registry).getAsJsonObject();
        object.addProperty("active", count.active());
        object.addProperty("enabledCount", count.enabled());
        object.addProperty("itemsCount", registry.getItems().size());
        return json(object);
    }

    private Response cspPage(Map<String, String> params) {
        String id = params.get("id");
        if (TextUtils.isEmpty(id)) return Nano.error(Status.BAD_REQUEST, "Missing id");
        String safeId = id.replaceAll("[^A-Za-z0-9_.-]", "_");
        String link = params.get("link");
        String code = params.get("code");
        String homePage;
        if (!TextUtils.isEmpty(link)) {
            homePage = link.trim();
        } else {
            Path.write(CustomCspSetting.file(safeId, "index.html"), (code == null ? "" : code).getBytes(StandardCharsets.UTF_8));
            homePage = CustomCspSetting.localUrl(safeId, "index.html");
        }
        JsonObject object = new JsonObject();
        object.addProperty("id", safeId);
        object.addProperty("homePage", homePage);
        return json(object);
    }

    private Response sources(Map<String, String> params) {
        try {
            if (bool(params.get("refresh"), false)) SourceSetting.clearCache();
            String target = params.getOrDefault("target", "").trim();
            JsonArray items = SourceSetting.sites(target);
            Config config = SourceSetting.config(target);
            JsonObject object = new JsonObject();
            object.addProperty("configUrl", config.getUrl());
            object.addProperty("configName", config.getName());
            object.addProperty("managed", SourceSetting.isManaged(target));
            object.addProperty("count", items.size());
            object.add("items", SourceSetting.summary(items));
            object.add("configs", configsList());
            return json(object);
        } catch (Exception e) {
            return Nano.error(Status.BAD_REQUEST, message(e));
        }
    }

    /**
     * 已保存的接口列表，供前端选择要管理的目标接口。
     */
    private JsonArray configsList() {
        JsonArray result = new JsonArray();
        try {
            String current = SourceSetting.config().getUrl();
            for (Config item : Config.getAll(0)) {
                if (TextUtils.isEmpty(item.getUrl())) continue;
                JsonObject object = new JsonObject();
                object.addProperty("url", item.getUrl());
                object.addProperty("name", item.getName());
                object.addProperty("current", item.getUrl().equals(current));
                object.addProperty("managed", !TextUtils.isEmpty(item.getJson()));
                result.add(object);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Response sourcesImport(Map<String, String> params) throws Exception {
        String content = params.get("content");
        String url = params.getOrDefault("url", "").trim();
        if (TextUtils.isEmpty(content) && !TextUtils.isEmpty(url)) content = Decoder.getJson(UrlUtil.convert(url), "Manage");
        JsonArray incoming = SourceSetting.parse(content);
        if (incoming.size() == 0) return Nano.error(Status.BAD_REQUEST, "没有解析到站源");
        boolean replace = bool(params.get("replace"), false);
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        SourceSetting.apply(target, root, SourceSetting.merge(SourceSetting.sites(target), incoming, replace));
        return sources(Map.of("target", target));
    }

    private Response sourcesDelete(Map<String, String> params) throws Exception {
        String key = params.getOrDefault("key", "").trim();
        if (TextUtils.isEmpty(key)) return Nano.error(Status.BAD_REQUEST, "Missing key");
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        SourceSetting.apply(target, root, SourceSetting.remove(SourceSetting.sites(target), key));
        return sources(Map.of("target", target));
    }

    private Response sourcesDeleteAll(Map<String, String> params) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        SourceSetting.apply(target, root, new JsonArray());
        return sources(Map.of("target", target));
    }

    private Response sourcesOrder(Map<String, String> params) throws Exception {
        String order = params.getOrDefault("order", "");
        List<String> keys = new ArrayList<>();
        for (String key : order.split(",")) {
            if (!TextUtils.isEmpty(key.trim())) keys.add(key.trim());
        }
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        SourceSetting.apply(target, root, SourceSetting.reorder(SourceSetting.sites(target), keys));
        return sources(Map.of("target", target));
    }

    private Response sourcesExport(Map<String, String> params) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        JsonArray sites = SourceSetting.enabledOnly(target);
        JsonObject root = new JsonObject();
        root.add("sites", sites);
        Config config = SourceSetting.config(target);
        String name = config.getName();
        if (!TextUtils.isEmpty(name)) name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        String filename = (TextUtils.isEmpty(name) ? "sites" : name) + "-sites.json";
        JsonObject object = new JsonObject();
        object.addProperty("filename", filename);
        object.addProperty("content", root.toString());
        object.addProperty("count", sites.size());
        return json(object);
    }

    private Response sourcesReset(Map<String, String> params) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        SourceSetting.reset(target);
        return sources(Map.of("target", target));
    }

    /**
     * 启停单个源（off 标记持久化在源 JSON 中）。
     */
    private Response sourcesSetOff(Map<String, String> params, boolean off) throws Exception {
        String key = params.getOrDefault("key", "").trim();
        if (TextUtils.isEmpty(key)) return Nano.error(Status.BAD_REQUEST, "Missing key");
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        SourceSetting.apply(target, root, SourceSetting.setOff(SourceSetting.sites(target), List.of(key), off));
        return sources(Map.of("target", target));
    }

    /**
     * 全部启用 / 全部停用。
     */
    private Response sourcesSetOffAll(Map<String, String> params, boolean off) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        JsonArray sites = SourceSetting.sites(target);
        List<String> keys = new ArrayList<>();
        for (String key : SourceSetting.keys(sites)) keys.add(key);
        SourceSetting.apply(target, root, SourceSetting.setOff(sites, keys, off));
        return sources(Map.of("target", target));
    }

    private Response sourcesSetOffInvalid(Map<String, String> params, boolean off) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        JsonArray sites = SourceSetting.sites(target);
        JsonArray tested = SourceSetting.test(sites, 4000);
        List<String> keys = new ArrayList<>();
        for (JsonElement element : tested) {
            if (!element.isJsonObject()) continue;
            if ("fail".equals(Json.safeString(element.getAsJsonObject(), "state"))) keys.add(Json.safeString(element.getAsJsonObject(), "key"));
        }
        JsonArray current = sites;
        if (!off) {
            for (String key : keys) current = SourceSetting.remove(current, key);
        }
        JsonArray result = off ? SourceSetting.setOff(sites, keys, true) : current;
        SourceSetting.apply(target, root, result);
        return sources(Map.of("target", target));
    }

    /**
     * 按 api 地址去重，保留第一个。
     */
    private Response sourcesDeleteDuplicate(Map<String, String> params) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        JsonObject root = SourceSetting.configJson(target);
        SourceSetting.apply(target, root, SourceSetting.dedupeByApi(SourceSetting.sites(target)));
        return sources(Map.of("target", target));
    }

    /**
     * 测速并可选按 快→慢→失败→未测速 自动排序持久化。
     */
    private Response sourcesTest(Map<String, String> params) throws Exception {
        String target = params.getOrDefault("target", "").trim();
        JsonArray sites = SourceSetting.sites(target);
        String key = params.getOrDefault("key", "").trim();
        if (!TextUtils.isEmpty(key)) {
            JsonArray filtered = new JsonArray();
            for (JsonElement element : sites) {
                if (element.isJsonObject() && key.equals(Json.safeString(element.getAsJsonObject(), "key"))) filtered.add(element);
            }
            sites = filtered;
        }
        JsonArray tested = SourceSetting.test(sites, 4000);
        if (bool(params.get("auto"), false)) {
            List<String> keys = new ArrayList<>();
            for (JsonElement element : tested) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if ("ok".equals(Json.safeString(item, "state"))) keys.add(Json.safeString(item, "key"));
            }
            for (JsonElement element : tested) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if ("fail".equals(Json.safeString(item, "state"))) keys.add(Json.safeString(item, "key"));
            }
            JsonObject root = SourceSetting.configJson(target);
            SourceSetting.apply(target, root, SourceSetting.reorder(SourceSetting.sites(target), keys));
        }
        JsonObject object = new JsonObject();
        object.addProperty("count", tested.size());
        object.add("items", tested);
        return json(object);
    }

    /**
     * 导入订阅：下载（≤5MB）并解析合并，保存订阅 URL 与名称。
     */
    private Response sourcesSubscriptionImport(Map<String, String> params) throws Exception {
        String url = params.getOrDefault("url", "").trim();
        String name = params.getOrDefault("name", "").trim();
        if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) return Nano.error(Status.BAD_REQUEST, "订阅地址必须是 http/https");
        String content = downloadSubscription(url);
        JsonArray incoming = SourceSetting.parse(content);
        if (incoming.size() == 0) return Nano.error(Status.BAD_REQUEST, "没有解析到站源");
        JsonObject root = SourceSetting.configJson();
        SourceSetting.apply(root, SourceSetting.merge(SourceSetting.sites(root), incoming, false));
        saveSubscription(url, name, SourceSetting.keys(incoming));
        return sourcesSubscriptionList();
    }

    private Response sourcesSubscriptionList() {
        JsonObject object = new JsonObject();
        object.add("items", Setting.getManageSubscriptions());
        return json(object);
    }

    /**
     * 更新单条订阅：新增/更新源，并移除该订阅下已消失且独占的源。
     */
    private Response sourcesSubscriptionUpdate(Map<String, String> params) throws Exception {
        String id = params.getOrDefault("id", "").trim();
        String url = params.getOrDefault("url", "").trim();
        JsonArray subscriptions = Setting.getManageSubscriptions();
        JsonObject subscription = findSubscription(subscriptions, id, url);
        if (subscription == null) return Nano.error(Status.BAD_REQUEST, "订阅不存在");
        String subUrl = Json.safeString(subscription, "url");
        String content = downloadSubscription(subUrl);
        JsonArray incoming = SourceSetting.parse(content);
        if (incoming.size() == 0) return Nano.error(Status.BAD_REQUEST, "没有解析到站源");
        List<String> newKeys = SourceSetting.keys(incoming);
        List<String> oldKeys = subscriptionKeys(subscription);
        JsonObject root = SourceSetting.configJson();
        JsonArray current = SourceSetting.sites(root);
        JsonArray merged = SourceSetting.merge(current, incoming, false);
        // 仅删除：原属该订阅、现已消失、且不被其他订阅共享的源
        List<String> gone = new ArrayList<>();
        for (String key : oldKeys) {
            if (newKeys.contains(key)) continue;
            if (sharedByOther(Setting.getManageSubscriptions(), subscription, key)) continue;
            gone.add(key);
        }
        for (String key : gone) merged = SourceSetting.remove(merged, key);
        SourceSetting.apply(root, merged);
        subscription.addProperty("keys", App.gson().toJson(newKeys));
        Setting.putManageSubscriptions(subscriptions);
        return sourcesSubscriptionList();
    }

    /**
     * 全部订阅一键更新。
     */
    private Response sourcesSubscriptionUpdateAll() throws Exception {
        JsonArray subscriptions = Setting.getManageSubscriptions();
        for (JsonElement element : subscriptions) {
            if (!element.isJsonObject()) continue;
            String id = Json.safeString(element.getAsJsonObject(), "id");
            sourcesSubscriptionUpdate(Map.of("id", id));
        }
        return sourcesSubscriptionList();
    }

    /**
     * 删除订阅，默认同时删除其名下独占源。
     */
    private Response sourcesSubscriptionDelete(Map<String, String> params) throws Exception {
        String id = params.getOrDefault("id", "").trim();
        boolean removeSites = bool(params.get("removeSites"), true);
        JsonArray subscriptions = Setting.getManageSubscriptions();
        JsonObject subscription = findSubscription(subscriptions, id, "");
        if (subscription == null) return Nano.error(Status.BAD_REQUEST, "订阅不存在");
        if (removeSites) {
            List<String> keys = subscriptionKeys(subscription);
            JsonObject root = SourceSetting.configJson();
            JsonArray current = SourceSetting.sites(root);
            for (String key : keys) {
                if (sharedByOther(subscriptions, subscription, key)) continue;
                current = SourceSetting.remove(current, key);
            }
            SourceSetting.apply(root, current);
        }
        JsonArray result = new JsonArray();
        for (JsonElement element : subscriptions) {
            if (element.isJsonObject() && element.getAsJsonObject().equals(subscription)) continue;
            result.add(element);
        }
        Setting.putManageSubscriptions(result);
        return sourcesSubscriptionList();
    }

    private String downloadSubscription(String url) throws Exception {
        try {
            okhttp3.Response response = OkHttp.newCall(OkHttp.client(15000), url).execute();
            if (!response.isSuccessful()) throw new Exception("下载失败：HTTP " + response.code());
            String body = response.body() == null ? "" : response.body().string();
            if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5 * 1024 * 1024) throw new Exception("订阅内容超过 5MB 限制");
            if (body.trim().isEmpty()) throw new Exception("订阅内容为空");
            return body;
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().startsWith("下载失败") || e.getMessage().startsWith("订阅内容"))) throw e;
            throw new Exception("下载失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void saveSubscription(String url, String name, List<String> keys) {
        JsonArray subscriptions = Setting.getManageSubscriptions();
        for (JsonElement element : subscriptions) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (Json.safeString(item, "url").equals(url)) {
                if (!TextUtils.isEmpty(name)) item.addProperty("name", name);
                item.addProperty("keys", App.gson().toJson(keys));
                Setting.putManageSubscriptions(subscriptions);
                return;
            }
        }
        JsonObject item = new JsonObject();
        item.addProperty("id", String.valueOf(System.currentTimeMillis()));
        item.addProperty("url", url);
        item.addProperty("name", TextUtils.isEmpty(name) ? url : name);
        item.addProperty("keys", App.gson().toJson(keys));
        subscriptions.add(item);
        Setting.putManageSubscriptions(subscriptions);
    }

    private JsonObject findSubscription(JsonArray subscriptions, String id, String url) {
        for (JsonElement element : subscriptions) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if ((!TextUtils.isEmpty(id) && Json.safeString(item, "id").equals(id)) || (!TextUtils.isEmpty(url) && Json.safeString(item, "url").equals(url))) return item;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> subscriptionKeys(JsonObject subscription) {
        try {
            List<String> keys = App.gson().fromJson(Json.safeString(subscription, "keys"), new com.google.gson.reflect.TypeToken<List<String>>() {
            }.getType());
            return keys == null ? new ArrayList<>() : keys;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private boolean sharedByOther(JsonArray subscriptions, JsonObject self, String key) {
        for (JsonElement element : subscriptions) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (item.equals(self)) continue;
            if (subscriptionKeys(item).contains(key)) return true;
        }
        return false;
    }

    private String message(Exception e) {
        return TextUtils.isEmpty(e.getMessage()) ? "操作失败" : e.getMessage();
    }

    private void reloadConfigs() {
        App.post(() -> VodConfig.get().clear().config(VodConfig.get().getConfig()).load(new Callback() {
        }));
        App.post(() -> {
            if (LiveConfig.hasLoadedLives() || !LiveConfig.get().getConfig().isEmpty() || CustomCspSetting.hasLives()) LiveConfig.get().clear().config(LiveConfig.get().getConfig()).load(new Callback() {
            });
        });
    }

    private JsonArray array(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private boolean bool(String value, boolean fallback) {
        if (value == null) return fallback;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    private Response json(JsonObject object) {
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", object.toString());
    }

    private boolean inside(File root, File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private boolean hasVisibleDirs(File dir) {
        File[] files = dir.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        return files != null && files.length > 0;
    }

    private String relativeTo(File root, File file) throws Exception {
        return root.toPath().relativize(file.getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/');
    }

    private String parentOf(File root, File dir) throws Exception {
        if (root.equals(dir)) return ".";
        File parent = dir.getParentFile();
        if (parent == null || parent.equals(root)) return "";
        return relativeTo(root, parent);
    }
}
