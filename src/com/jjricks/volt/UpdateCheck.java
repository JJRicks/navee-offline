package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Manual check for a newer Volt release on GitHub, only after the owner's consent. It reads one
 * small JSON reply and links to the release page; it never downloads or installs anything.
 */
final class UpdateCheck {
    static final String ENDPOINT = "https://api.github.com/repos/JJRicks/volt/releases/latest";
    /** Only a release page in Volt's own repository is ever offered. */
    static final String RELEASES = "https://github.com/JJRicks/volt/releases/";
    /** app_settings: the owner agreed that Volt may ask GitHub. */
    static final String CONSENT = "update_check_consented";
    static final int MAX_BYTES = 256 * 1024, MAX_NOTES = 3000;
    private UpdateCheck() {}

    interface Fetcher { String get(String url, String userAgent) throws IOException; }
    /** Replaced only by tests, which never reach the network. */
    static Fetcher fetcher = UpdateCheck::https;

    enum Outcome { AVAILABLE, CURRENT, UNREADABLE }
    static final class Result {
        final Outcome outcome; final String latest, url, notes;
        Result(Outcome outcome, String latest, String url, String notes) {
            this.outcome = outcome; this.latest = latest; this.url = url; this.notes = notes;
        }
        static Result unreadable() { return new Result(Outcome.UNREADABLE, "", "", ""); }
    }

    /** The same User-Agent the map tiles send. */
    static String userAgent(String installed) { return "Volt/" + installed + " (Android; https://github.com/JJRicks/volt)"; }

    /** Leading numeric parts: "v1.5.1" is 1.5.1 and the test build "1.5.0-n65i-test" is 1.5.0; null when there are none. */
    static int[] version(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        String[] parts = s.substring(0, end).split("\\.");
        if (end == 0 || parts.length == 0 || parts.length > 4) return null;
        int[] result = new int[parts.length];
        try { for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]); }
        catch (NumberFormatException unreadable) { return null; }
        return result;
    }

    /** Missing parts count as 0, so 1.5 equals 1.5.0. */
    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** GitHub's latest-release reply against the installed version; anything unexpected is UNREADABLE. */
    static Result evaluate(String installed, String json) {
        try {
            Map<String, Object> release = BackupJson.object(BackupJson.parse(json, 8));
            Object tag = release.get("tag_name"), url = release.get("html_url"), body = release.get("body");
            if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease"))) return Result.unreadable();
            if (!(tag instanceof String) || !(url instanceof String) || !safePage((String) url)) return Result.unreadable();
            int[] latest = version((String) tag), mine = version(installed);
            if (latest == null || mine == null) return Result.unreadable();
            String notes = body instanceof String ? plain((String) body) : "";
            if (notes.length() > MAX_NOTES) notes = notes.substring(0, MAX_NOTES).trim() + "…";
            String name = ((String) tag).startsWith("v") ? ((String) tag).substring(1) : (String) tag;
            return new Result(compare(latest, mine) > 0 ? Outcome.AVAILABLE : Outcome.CURRENT, name, (String) url, notes);
        } catch (IOException | RuntimeException unreadable) {
            return Result.unreadable();
        }
    }

    /** GitHub's markdown notes as plain text: no emphasis markers, headings as lines, list dashes as bullets. */
    static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.replace("**", "").replace("__", "").replace("`", "");
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) line = trimmed.replaceFirst("^#+\\s*", "");
            else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) line = line.substring(0, line.indexOf(trimmed)) + "• " + trimmed.substring(2);
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Only a release page in Volt's repository: releases/latest or releases/tag/<tag>, never a download or anything else. */
    static boolean safePage(String url) {
        if (url == null || url.length() > 200) return false;
        if (url.equals(RELEASES + "latest")) return true;
        if (!url.startsWith(RELEASES + "tag/")) return false;
        String tag = url.substring((RELEASES + "tag/").length());
        if (tag.isEmpty() || tag.contains("..")) return false;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) return false;
        }
        return true;
    }

    /** One bounded HTTPS GET: no redirects, short timeouts, at most MAX_BYTES. */
    private static String https(String address, String userAgent) throws IOException {
        if (!address.startsWith("https://")) throw new IOException("Only HTTPS");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", userAgent);
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int n;
                while ((n = in.read(chunk)) > 0) {
                    if (out.size() + n > MAX_BYTES) throw new IOException("Reply too large");
                    out.write(chunk, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
