package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * top_shelf's "stremio": the household's own "Continue watching" from its Stremio library, the one shelf that is
 * personal. Stremio's account API (datastoreGet, the call its own apps make) gives each title's progress; TMDB, found
 * by the IMDb id Stremio keys titles by, gives the backdrop, the logo and the synopsis in the shelf's language. A
 * title TMDB does not know keeps Stremio's own background and logo.
 */
final class StremioFeed {
    private static final String TAG = "TopShelf";
    /** Bumped when what the feed holds changes, so a feed from an older build is fetched again. */
    private static final int FORMAT = 2;
    private static final String API = "https://api.strem.io/api/datastoreGet";
    /** Short: watching something in Stremio and coming back to Home should already move it to the front. */
    static final long REFRESH_MS = 20 * 60_000L;
    /** A film this far through is finished, whatever Stremio's watched flag says. */
    private static final double DONE = 0.95;

    static final class Settings {
        final String authKey, app;
        final int count;

        Settings(String authKey, String app, int count) {
            this.authKey = authKey;
            this.app = app;
            this.count = count;
        }

        String signature() {
            return FORMAT + "|" + authKey.hashCode() + "|" + app + "|" + count;
        }
    }

    private StremioFeed() {
    }

    static boolean stale(JSONObject feed, Settings s, String tmdbKey, long now) {
        if (feed == null || !(s.signature() + "|" + tmdbKey.hashCode()).equals(feed.optString("signature"))) return true;
        return now - feed.optLong("fetched", 0) > REFRESH_MS;
    }

    static JSONObject fetch(Context ctx, Settings s, String tmdbKey, String language, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        JSONObject body = new JSONObject();
        body.put("authKey", s.authKey);
        body.put("collection", "libraryItem");
        body.put("ids", new JSONArray());
        body.put("all", true);
        JSONObject answer = ShelfNet.post(API, body);
        if (answer.has("error")) throw new IllegalStateException("Stremio: " + answer.opt("error"));
        JSONArray library = answer.optJSONArray("result");

        List<JSONObject> watching = new ArrayList<>();
        for (int i = 0; library != null && i < library.length(); i++) {
            JSONObject x = library.getJSONObject(i);
            JSONObject st = x.optJSONObject("state");
            if (x.optBoolean("removed") || st == null || !x.optString("_id").startsWith("tt")) continue;
            double offset = st.optDouble("timeOffset", 0), duration = st.optDouble("duration", 0);
            if (offset <= 0) continue;
            if ("movie".equals(x.optString("type")) && duration > 0 && offset / duration > DONE) continue;
            watching.add(x);
        }
        // Stremio's own order: the last thing watched first. ISO timestamps sort as text.
        watching.sort((a, b) -> b.optJSONObject("state").optString("lastWatched")
                .compareTo(a.optJSONObject("state").optString("lastWatched")));

        String lang = language.split("-")[0];
        JSONArray items = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (JSONObject x : watching) {
            if (items.length() >= s.count) break;
            try {
                JSONObject item = item(ctx, x, tmdbKey, language, lang, dir, keep);
                if (item != null) items.put(item);
            } catch (Exception e) {
                Log.w(TAG, "skipping " + x.optString("name"), e);
            }
        }
        JSONObject apps = new JSONObject();
        apps.put(s.app, items);
        JSONObject feed = new JSONObject();
        feed.put("fetched", System.currentTimeMillis());
        feed.put("signature", s.signature() + "|" + tmdbKey.hashCode());
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

    private static JSONObject item(Context ctx, JSONObject x, String tmdbKey, String language, String lang, File dir,
                                   Set<String> keep) throws Exception {
        String imdb = x.optString("_id");
        boolean series = "series".equals(x.optString("type"));
        String title = x.optString("name"), overview = "", art = null, logo = null;

        JSONObject found = null;
        String kind = series ? "tv" : "movie";
        if (!tmdbKey.isEmpty()) {
            JSONObject r = TopShelf.tmdb(tmdbKey, "/find/" + ShelfNet.enc(imdb) + "?external_source=imdb_id&language="
                    + ShelfNet.enc(language));
            JSONArray hits = r.optJSONArray(series ? "tv_results" : "movie_results");
            if (hits != null && hits.length() > 0) found = hits.getJSONObject(0);
        }
        if (found != null) {
            String name = found.optString(series ? "name" : "title", "");
            if (!name.isEmpty()) title = name;
            overview = found.optString("overview", "").trim();
            String backdrop = found.isNull("backdrop_path") ? "" : found.optString("backdrop_path", "");
            String id = kind + found.getLong("id");
            if (!backdrop.isEmpty() && ShelfNet.download(TopShelf.IMAGES + "w1280" + backdrop, new File(dir, id + ".jpg"))) {
                art = id + ".jpg";
            }
            String logoPath = TopShelf.logoPath(tmdbKey, kind, found.getLong("id"), lang);
            if (logoPath != null && ShelfNet.download(TopShelf.IMAGES + "w500" + logoPath, new File(dir, id + "-logo.png"))) {
                logo = id + "-logo.png";
            }
        }
        // Stremio's own artwork (Cinemeta's) for what TMDB does not have.
        if (art == null) {
            String bg = x.optString("background", "");
            if (bg.startsWith("http") && ShelfNet.download(bg, new File(dir, imdb + ".jpg"))) art = imdb + ".jpg";
        }
        if (art == null) return null;
        if (logo == null) {
            String l = x.optString("logo", "");
            if (l.startsWith("http") && ShelfNet.download(l, new File(dir, imdb + "-logo.png"))) logo = imdb + "-logo.png";
        }
        keep.add(art);
        if (logo != null) keep.add(logo);

        JSONObject st = x.getJSONObject("state");
        StringBuilder meta = new StringBuilder(ctx.getString(series ? R.string.shelf_series : R.string.shelf_film));
        int season = st.optInt("season", 0), episode = st.optInt("episode", 0);
        // Often 0 in the state; the episode is also in the video id, "tt31510819:1:6".
        String[] vid = st.optString("video_id", "").split(":");
        if ((season <= 0 || episode <= 0) && vid.length == 3) {
            try {
                season = Integer.parseInt(vid[1]);
                episode = Integer.parseInt(vid[2]);
            } catch (NumberFormatException ignored) {
                // a video id that is not an episode: no season and episode shown
            }
        }
        if (series && season > 0 && episode > 0) {
            meta.append("  ·  ").append(ctx.getString(R.string.shelf_episode, season, episode));
        }
        long left = Math.round((st.optDouble("duration", 0) - st.optDouble("timeOffset", 0)) / 60_000d);
        if (left > 0) meta.append("  ·  ").append(ctx.getString(R.string.shelf_left, left));

        JSONObject item = new JSONObject();
        item.put("title", title);
        item.put("meta", meta.toString());
        item.put("overview", overview);
        item.put("art", dir.getName() + "/" + art);
        item.put("logo", logo == null ? "" : dir.getName() + "/" + logo);
        return item;
    }
}
