package com.gathin.tvhome;

import android.content.Context;
import android.icu.text.CompactDecimalFormat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * top_shelf's "youtube": what is trending on YouTube in the region (the Data API's mostPopular chart), for the YouTube
 * app, and the same chart in the Music category for YouTube Music. Not personal: the account's own feed needs an
 * OAuth sign-in the launcher has no way to make.
 */
final class YouTubeFeed {
    private static final String TAG = "TopShelf";
    /** Bumped when what the feed holds changes, so a feed from an older build is fetched again. */
    private static final int FORMAT = 2;
    private static final String API = "https://www.googleapis.com/youtube/v3/videos";
    static final long REFRESH_MS = 3 * 60 * 60_000L;
    private static final String MUSIC = "10";
    /** A trailing "(Official Video)", "[4K]" or "(Prod: …)", which a shelf title can do without. */
    private static final Pattern TAIL = Pattern.compile("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$");

    static final class Settings {
        final String key, region, language;
        /** App package to video category id; empty for the whole chart. */
        final Map<String, String> apps;
        final int count;

        Settings(String key, String region, String language, Map<String, String> apps, int count) {
            this.key = key;
            this.region = region;
            this.language = language;
            this.apps = apps;
            this.count = count;
        }

        String signature() {
            return FORMAT + "|" + key.hashCode() + "|" + region + "|" + language + "|" + apps + "|" + count;
        }
    }

    private YouTubeFeed() {
    }

    static boolean stale(JSONObject feed, Settings s, long now) {
        if (feed == null || !s.signature().equals(feed.optString("signature"))) return true;
        return now - feed.optLong("fetched", 0) > REFRESH_MS;
    }

    static JSONObject fetch(Context ctx, Settings s, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        CompactDecimalFormat compact = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT);
        JSONObject apps = new JSONObject();
        Set<String> keep = new HashSet<>();
        // With a music app on the shelf too, the whole chart leaves music to it: the same song led both.
        boolean musicElsewhere = s.apps.containsValue(MUSIC);
        for (Map.Entry<String, String> app : s.apps.entrySet()) {
            String url = API + "?part=snippet,statistics&chart=mostPopular&regionCode=" + ShelfNet.enc(s.region)
                    + "&hl=" + ShelfNet.enc(s.language) + "&maxResults=" + Math.min(50, s.count * 3)
                    + (app.getValue().isEmpty() ? "" : "&videoCategoryId=" + ShelfNet.enc(app.getValue()))
                    + "&key=" + ShelfNet.enc(s.key);
            JSONArray videos;
            try {
                videos = ShelfNet.json(url, null).optJSONArray("items");
            } catch (Exception e) {
                Log.w(TAG, "YouTube chart failed for " + app.getKey(), e);
                continue;
            }
            JSONArray items = new JSONArray();
            for (int i = 0; videos != null && i < videos.length() && items.length() < s.count; i++) {
                JSONObject v = videos.getJSONObject(i);
                JSONObject sn = v.optJSONObject("snippet");
                if (sn == null) continue;
                if (musicElsewhere && app.getValue().isEmpty() && MUSIC.equals(sn.optString("categoryId"))) continue;
                String thumb = thumbnail(sn.optJSONObject("thumbnails"));
                String name = "yt-" + v.optString("id") + ".jpg";
                if (thumb == null || !ShelfNet.download(thumb, new File(dir, name))) continue;
                keep.add(name);
                JSONObject loc = sn.optJSONObject("localized");
                String title = tidy(loc != null && !loc.optString("title").isEmpty() ? loc.optString("title") : sn.optString("title"));
                StringBuilder meta = new StringBuilder(sn.optString("channelTitle"));
                long views = v.optJSONObject("statistics") == null ? -1
                        : v.optJSONObject("statistics").optLong("viewCount", -1);
                if (views >= 0) {
                    meta.append("  ·  ").append(ctx.getString(R.string.shelf_views, compact.format(views)));
                }
                JSONObject item = new JSONObject();
                item.put("title", title);
                item.put("meta", meta.toString());
                item.put("overview", "");
                item.put("art", dir.getName() + "/" + name);
                item.put("logo", "");
                items.put(item);
            }
            apps.put(app.getKey(), items);
        }
        JSONObject feed = new JSONObject();
        feed.put("fetched", System.currentTimeMillis());
        feed.put("signature", s.signature());
        feed.put("apps", apps);
        ShelfNet.save(feed, new File(dir, "feed.json"));
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith("feed.json") && !keep.contains(f.getName())) f.delete();
            }
        }
        return feed;
    }

    /** The title without what follows a " | " and without trailing brackets, unless that would leave too little. */
    static String tidy(String title) {
        String t = title.trim();
        int bar = t.indexOf(" | ");
        if (bar >= 8) t = t.substring(0, bar).trim();
        for (int i = 0; i < 3; i++) {
            Matcher m = TAIL.matcher(t);
            if (!m.find() || m.start() < 8) break;
            t = t.substring(0, m.start()).trim();
        }
        return t;
    }

    /** The widest 16:9 thumbnail: maxres (1280×720) when the uploader made one, else standard or high. */
    private static String thumbnail(JSONObject t) {
        if (t == null) return null;
        for (String size : new String[]{"maxres", "standard", "high"}) {
            JSONObject o = t.optJSONObject(size);
            if (o != null && !o.optString("url").isEmpty()) return o.optString("url");
        }
        return null;
    }
}
