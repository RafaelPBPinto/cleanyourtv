package com.gathin.tvhome;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The few HTTP calls top_shelf's feeds make: a JSON GET and an image download. Background threads only. */
final class ShelfNet {
    private static final String TAG = "TopShelf";

    private ShelfNet() {
    }

    /** GETs JSON; a non-null bearer goes in the Authorization header. Throws on anything but 200. */
    static JSONObject json(String url, String bearer) throws Exception {
        HttpURLConnection c = open(url);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code + " for " + url.replaceAll("api_key=[^&]*", "api_key=…"));
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    /** POSTs a JSON body and returns the JSON answer (Stremio's API). Throws on anything but 200. */
    static JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    /** Keeps a file that is already there: both TMDB and TheSportsDB give a changed image a new path. */
    static boolean download(String url, File to) {
        if (to.isFile() && to.length() > 0) return true;
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File tmp = new File(to.getPath() + ".part");
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return tmp.renameTo(to);
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + url, e);
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Writes the feed beside its images in one rename, so a reader never sees half of it. */
    static void save(JSONObject feed, File file) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), feed.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) throw new IllegalStateException("cannot save " + file);
    }

    static JSONObject read(File file) {
        if (!file.isFile()) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** The last path segment of an image URL, which the image hosts make unique per image. */
    static String fileName(String url) {
        int q = url.indexOf('?');
        String path = q < 0 ? url : url.substring(0, q);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
