package com.fongmi.android.tv.server;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.security.SecureRandom;

import fi.iki.elonen.NanoHTTPD;

public class ServerAuth {

    private static volatile String TOKEN = token();

    public static String tokenValue() {
        return TOKEN;
    }

    public static String ipMode() {
        return Prefers.getString("server_ip_allow", "all");
    }

    public static void setIpMode(String mode) {
        Prefers.put("server_ip_allow", TextUtils.isEmpty(mode) ? "all" : mode);
    }

    public static boolean isLocal(NanoHTTPD.IHTTPSession session) {
        return isLocalIp(remoteIp(session));
    }

    public static boolean isLocalOrLan(NanoHTTPD.IHTTPSession session) {
        String ip = remoteIp(session);
        return isLocalIp(ip) || isLanIp(ip);
    }

    private static boolean isLocalIp(String ip) {
        if (TextUtils.isEmpty(ip)) return false;
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }

    private static boolean isLanIp(String ip) {
        if (TextUtils.isEmpty(ip)) return false;
        // 169.254.0.0/16 is link-local (cloud metadata on some platforms) and must not count as LAN.
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    public static boolean allow(NanoHTTPD.IHTTPSession session, String url) {
        // 本地服务不再使用 token 鉴权，仅保留用户配置的 IP 访问范围限制。
        return allowIp(session);
    }

    private static boolean allowIp(NanoHTTPD.IHTTPSession session) {
        String mode = ipMode();
        if ("all".equals(mode)) return true;
        String ip = remoteIp(session);
        if (isLocalIp(ip)) return true;
        return "lan".equals(mode) && isLanIp(ip);
    }

    private static String remoteIp(NanoHTTPD.IHTTPSession session) {
        return session.getHeaders().get("remote-addr");
    }

    private static String token() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        return builder.toString();
    }
}
