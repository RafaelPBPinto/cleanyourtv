package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * top_shelf: Apple TV's Top Shelf for the streaming apps on the dock. A theme's config.json gives a TMDB key and the
 * TMDB watch provider of each app; every few hours the launcher fetches that service's popular titles in the region,
 * with a backdrop and a title logo each, into its files directory. Focusing the app on the dock then shows them
 * full screen behind the shelf, one after another, the way the Apple TV app's Top Shelf does. The titles are what is
 * popular on the service, not what this household watches: Android keeps other apps' recommendations behind a
 * privileged permission (ACCESS_ALL_EPG_DATA) that a launcher installed by hand cannot hold.
 *
 * <pre>
 * "top_shelf": {"tmdb_key": "...", "region": "PT", "language": "pt-PT",
 *               "apps": {"com.netflix.ninja": 8, "com.disney.disneyplus": 337},
 *               "sports": {"apps": ["com.sofascore.results"], "teams": ["135708"], "f1": true}}
 * </pre>
 * The sports apps share one list instead, the followed teams' next matches and the next F1 weekend (SportsFeed).
 */
final class TopShelf {
    private static final String TAG = "TopShelf";
    private static final String API = "https://api.themoviedb.org/3";
    static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final long REFRESH_MS = 6 * 60 * 60 * 1000L;
    /** After a failed fetch (no network yet at boot, say), wait this long before the next try. */
    private static final long RETRY_MS = 15 * 60 * 1000L;
    /** Stremio's list changes with every evening's watching: try again sooner than the others. */
    private static final long STREMIO_RETRY_MS = 5 * 60 * 1000L;
    private static final long ADVANCE_MS = 9000;
    private static final int CROSSFADE_MS = 900;

    private static final Pattern LATIN = Pattern.compile("[\\u0000-\\u024F\\u2000-\\u206F]*");

    private static final String SEP = "  ·  ";
    private static final int LONG_TITLE = 26;
    /** The red of a live badge, Apple's dark-mode system red. */
    private static final int LIVE_RED = 0xFFFF453A;

    private static final ExecutorService FETCH = Executors.newSingleThreadExecutor();
    private static long lastAttempt, lastSportsAttempt, lastStremioAttempt, lastYouTubeAttempt;

    static final class Item {
        final String title, meta, overview;
        final File art, logo;
        /** A match or a race weekend's sessions in order; empty for a film or series, which never ends. */
        final List<Session> sessions;

        Item(String title, String meta, String overview, File art, File logo, List<Session> sessions) {
            this.title = title;
            this.meta = meta;
            this.overview = overview;
            this.art = art;
            this.logo = logo;
            this.sessions = sessions;
        }

        /** The session still to come or under way; null once the last one is over. */
        Session current(long now) {
            for (Session x : sessions) if (x.end > now) return x;
            return null;
        }

        /** Before every other item while it is on, then by start: what is next comes first. */
        long order(long now) {
            Session x = current(now);
            return x == null ? Long.MAX_VALUE : x.start;
        }
    }

    static final class Session {
        final String label;
        final long start, end;

        Session(String label, long start, long end) {
            this.label = label;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Settings {
        final String key, region, language;
        final Map<String, Integer> apps;
        final int count;
        /** Null when the theme follows no sports. */
        final SportsFeed.Settings sports;
        /**
         * An app whose service is not sold in the region (Globoplay, only in TMDB's Brazilian catalogue): its own
         * region and language, and the country its own productions come from, as {region, language, origin}.
         */
        final Map<String, String[]> local;
        /** Null when the theme sets no "stremio" or no "youtube". */
        StremioFeed.Settings stremio;
        YouTubeFeed.Settings youtube;

        Settings(String key, String region, String language, Map<String, Integer> apps, int count,
                 SportsFeed.Settings sports, Map<String, String[]> local) {
            this.key = key;
            this.region = region;
            this.language = language;
            this.apps = apps;
            this.count = count;
            this.sports = sports;
            this.local = local;
        }

        String region(String pkg) {
            String[] l = local.get(pkg);
            return l == null ? region : l[0];
        }

        String language(String pkg) {
            String[] l = local.get(pkg);
            return l == null ? language : l[1];
        }

        /** Empty unless the app should show its own country's productions only. */
        String origin(String pkg) {
            String[] l = local.get(pkg);
            return l == null ? "" : l[2];
        }

        /** Streaming titles need a TMDB key and at least one app mapped to a provider. */
        boolean titles() {
            return !key.isEmpty() && !apps.isEmpty();
        }

        /** Changes when anything that decides the feed changes, so a new theme fetches again at once. */
        String signature() {
            StringBuilder places = new StringBuilder();
            for (Map.Entry<String, String[]> e : local.entrySet()) {
                places.append(e.getKey()).append('=').append(String.join("/", e.getValue())).append(';');
            }
            return key.hashCode() + "|" + region + "|" + language + "|" + apps + "|" + count + "|" + places;
        }
    }

    private final Context ctx;
    private final Settings settings;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decode = Executors.newSingleThreadExecutor();
    /** Two backdrops in RGB_565 at 1280×720 are under 4 MB; more would only serve a quick back-and-forth. */
    private final LruCache<String, Bitmap> bitmaps = new LruCache<>(4);
    private final ImageView art;
    private final View info;
    private final ImageView logo;
    private final TextView title, meta, overview;
    private final boolean animate;

    private Map<String, List<Item>> feed = Collections.emptyMap();
    private final Map<String, Integer> position = new HashMap<>();
    private String shown;
    private float bottomY;
    /** Bumped on every change of item, so a decode that finishes late does not draw over a newer one. */
    private int token;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            List<Item> items = shown == null ? null : items(shown);
            if (items == null || items.isEmpty()) return;
            // A single match still advances: it shows again, so "Today" turns into "LIVE" on its own.
            int next = (index(shown) + 1) % items.size();
            position.put(shown, next);
            display(items.get(next), true);
            main.postDelayed(this, ADVANCE_MS);
        }
    };

    private TopShelf(Activity activity, Settings settings, boolean animate) {
        this.ctx = activity.getApplicationContext();
        this.settings = settings;
        this.dir = new File(ctx.getFilesDir(), "top_shelf");
        this.animate = animate;
        art = activity.findViewById(R.id.shelfArt);
        info = activity.findViewById(R.id.shelfInfo);
        logo = activity.findViewById(R.id.shelfLogo);
        title = activity.findViewById(R.id.shelfTitle);
        meta = activity.findViewById(R.id.shelfMeta);
        overview = activity.findViewById(R.id.shelfOverview);
        art.setVisibility(View.VISIBLE);
        info.setVisibility(View.VISIBLE);
        // A full-screen bitmap: apply its alpha while drawing, not through an offscreen buffer.
        art.forceHasOverlappingRendering(false);
        // The cinematic scrim darkens the left and the bottom; a bright backdrop still washed out the clock and the
        // pills along the top, so shade the top third too. Evenly spaced stops: dark only over the first third.
        art.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99060406, 0x00060406, 0x00060406}));
        // Bottom-aligned to the shelf whatever the logo or overview makes its height.
        info.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setY(bottomY - v.getHeight()));
    }

    /** Null when the theme sets no top_shelf, or sets it without a key or apps. */
    static TopShelf create(Activity activity, boolean animate) {
        Settings s = readSettings(activity);
        // layout-iw has no shelf views: right-to-left Home keeps the plain shelf.
        if (s == null || activity.findViewById(R.id.shelfArt) == null) return null;
        TopShelf shelf = new TopShelf(activity, s, animate);
        shelf.load();
        return shelf;
    }

    private static Settings readSettings(Context ctx) {
        File base = Config.dir(ctx);
        if (base == null) return null;
        File file = new File(base, Config.FILE_NAME);
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONObject o = json.optJSONObject("top_shelf");
            if (o == null) return null;
            String key = o.optString("tmdb_key", "").trim();
            JSONObject apps = o.optJSONObject("apps");
            String region = o.optString("region", "US").trim(), language = o.optString("language", "en-US").trim();
            Map<String, Integer> map = new LinkedHashMap<>();
            Map<String, String[]> local = new LinkedHashMap<>();
            if (apps != null) {
                for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
                    String pkg = it.next();
                    // A provider id, or {"provider": 307, "region": "BR", "language": "pt-BR"}.
                    JSONObject one = apps.optJSONObject(pkg);
                    int provider = one != null ? one.optInt("provider", 0) : apps.optInt(pkg, 0);
                    if (provider <= 0) continue;
                    map.put(pkg, provider);
                    if (one != null && (one.has("region") || one.has("language") || one.has("origin"))) {
                        local.put(pkg, new String[]{one.optString("region", region).trim(),
                                one.optString("language", language).trim(), one.optString("origin", "").trim()});
                    }
                }
            }
            int count = Math.max(1, Math.min(20, o.optInt("count", 8)));
            Settings s = new Settings(key, region, language, map, count, sports(o.optJSONObject("sports")), local);
            JSONObject st = o.optJSONObject("stremio");
            if (st != null && !st.optString("auth_key").trim().isEmpty() && !st.optString("app").trim().isEmpty()) {
                s.stremio = new StremioFeed.Settings(st.optString("auth_key").trim(), st.optString("app").trim(),
                        Math.max(1, Math.min(20, st.optInt("count", count))));
            }
            JSONObject yt = o.optJSONObject("youtube");
            if (yt != null && !yt.optString("key").trim().isEmpty() && yt.optJSONObject("apps") != null) {
                // App package to YouTube video category: "" for the whole chart, "10" for music.
                Map<String, String> ytApps = new LinkedHashMap<>();
                JSONObject a = yt.optJSONObject("apps");
                for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String pkg = it.next();
                    ytApps.put(pkg, a.optString(pkg, "").trim());
                }
                s.youtube = new YouTubeFeed.Settings(yt.optString("key").trim(), yt.optString("region", region).trim(),
                        yt.optString("language", language).trim(), ytApps, Math.max(1, Math.min(20, yt.optInt("count", count))));
            }
            return s.titles() || s.sports != null || s.stremio != null || s.youtube != null ? s : null;
        } catch (Exception e) {
            Log.w(TAG, "top_shelf in config.json is not valid", e);
            return null;
        }
    }

    /**
     * "sports": {"apps": [packages], "teams": [TheSportsDB team ids], "f1": true, "names": {"league": "shown as"},
     * "art": {"team id": "image URL" or {"url", "focus", "zoom"}}}.
     * Null without apps, or with neither teams nor F1.
     */
    private static SportsFeed.Settings sports(JSONObject o) {
        if (o == null) return null;
        List<String> apps = strings(o.optJSONArray("apps"));
        List<String> teams = strings(o.optJSONArray("teams"));
        boolean f1 = o.optBoolean("f1", false);
        if (apps.isEmpty() || teams.isEmpty() && !f1) return null;
        return new SportsFeed.Settings(apps, teams, f1, map(o.optJSONObject("names")), art(o.optJSONObject("art")));
    }

    /** "art": {"team id": "image URL"}, or {"team id": {"url": "...", "focus": [x, y], "zoom": 1.4}}. */
    private static Map<String, SportsFeed.Art> art(JSONObject o) {
        Map<String, SportsFeed.Art> out = new LinkedHashMap<>();
        if (o == null) return out;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String team = it.next();
            JSONObject a = o.optJSONObject(team);
            String url = a != null ? a.optString("url", "").trim() : o.optString(team, "").trim();
            if (url.isEmpty()) continue;
            JSONArray focus = a == null ? null : a.optJSONArray("focus");
            float x = focus == null ? 0.5f : (float) focus.optDouble(0, 0.5);
            float y = focus == null ? 0.5f : (float) focus.optDouble(1, 0.5);
            float zoom = a == null ? 1f : (float) Math.max(1.0, Math.min(3.0, a.optDouble("zoom", 1.0)));
            out.put(team, new SportsFeed.Art(url, x, y, zoom));
        }
        return out;
    }

    private static Map<String, String> map(JSONObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o == null) return out;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = o.optString(k, "").trim();
            if (!v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "").trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    // ---- what the dock shows ---------------------------------------------------------------------------

    /**
     * Shows this dock app's titles above a shelf whose bottom edge is at bottomY. False when the app has none, and
     * the caller shows its usual shelf instead.
     */
    boolean show(String pkg, float bottomY) {
        this.bottomY = bottomY;
        List<Item> items = pkg == null ? null : items(pkg);
        if (items == null || items.isEmpty()) {
            hide();
            return false;
        }
        info.setY(bottomY - info.getHeight());
        if (pkg.equals(shown)) return true;
        shown = pkg;
        main.removeCallbacks(advance);
        display(items.get(Math.min(index(pkg), items.size() - 1)), art.getAlpha() > 0f);
        main.postDelayed(advance, ADVANCE_MS);
        return true;
    }

    void hide() {
        main.removeCallbacks(advance);
        shown = null;
        token++;
        if (art.getAlpha() == 0f && info.getAlpha() == 0f) return;
        art.animate().cancel();
        info.animate().cancel();
        if (animate) {
            art.animate().alpha(0f).setDuration(300).start();
            info.animate().alpha(0f).setStartDelay(0).setDuration(160).start();
        } else {
            art.setAlpha(0f);
            info.setAlpha(0f);
        }
    }

    /** Home went to the background or memory is short: drop the decoded images, they decode again in a moment. */
    void release() {
        hide();
        art.animate().cancel();
        art.setAlpha(0f);
        art.setImageDrawable(null);
        bitmaps.evictAll();
    }

    void close() {
        release();
        decode.shutdownNow();
    }

    /** The app's items still to show: matches and weekends that are over drop out, the next one comes first. */
    private List<Item> items(String pkg) {
        List<Item> all = feed.get(pkg);
        if (all == null || all.isEmpty() || all.get(0).sessions.isEmpty()) return all;
        long now = System.currentTimeMillis();
        List<Item> on = new ArrayList<>();
        for (Item i : all) if (i.current(now) != null) on.add(i);
        on.sort((a, b) -> Long.compare(a.order(now), b.order(now)));
        return on;
    }

    private int index(String pkg) {
        Integer i = position.get(pkg);
        return i == null ? 0 : i;
    }

    /** Decodes off the main thread, then crossfades the backdrop and swaps the text. */
    private void display(Item item, boolean crossfade) {
        final int mine = ++token;
        decode.execute(() -> {
            Bitmap backdrop = bitmap(item.art, false);
            Bitmap mark = item.logo == null ? null : bitmap(item.logo, true);
            main.post(() -> {
                if (mine != token) return;
                showArt(backdrop, crossfade);
                showInfo(item, mark, crossfade);
            });
        });
    }

    private Bitmap bitmap(File file, boolean alpha) {
        String key = file.getPath();
        Bitmap b = bitmaps.get(key);
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        // Backdrops have no transparency; 16-bit halves their memory, and the scrim hides any banding.
        o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        b = BitmapFactory.decodeFile(file.getPath(), o);
        if (b != null) bitmaps.put(key, b);
        return b;
    }

    private void showArt(Bitmap next, boolean crossfade) {
        if (next == null) return;
        Drawable now = art.getDrawable();
        Drawable incoming = new BitmapDrawable(ctx.getResources(), next);
        art.animate().cancel();
        if (now instanceof TransitionDrawable) now = ((TransitionDrawable) now).getDrawable(1);
        if (!animate || !crossfade || now == null || art.getAlpha() == 0f) {
            art.setImageDrawable(incoming);
            if (animate) art.animate().alpha(1f).setDuration(500).start();
            else art.setAlpha(1f);
            return;
        }
        TransitionDrawable fade = new TransitionDrawable(new Drawable[]{now, incoming});
        fade.setCrossFadeEnabled(true);
        art.setImageDrawable(fade);
        art.setAlpha(1f);
        fade.startTransition(CROSSFADE_MS);
    }

    private void showInfo(Item item, Bitmap mark, boolean crossfade) {
        Runnable fill = () -> {
            logo.setImageBitmap(mark);
            logo.setVisibility(mark != null ? View.VISIBLE : View.GONE);
            title.setText(item.title);
            title.setVisibility(mark != null ? View.GONE : View.VISIBLE);
            // A film's name fits one large line; a video's title is a sentence: smaller, on up to two lines.
            boolean longTitle = item.title.length() > LONG_TITLE;
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, longTitle ? 40 : 56);
            title.setSingleLine(!longTitle);
            title.setMaxLines(longTitle ? 2 : 1);
            meta.setText(metaText(item));
            overview.setText(item.overview);
            overview.setVisibility(item.overview.isEmpty() ? View.GONE : View.VISIBLE);
        };
        info.animate().cancel();
        if (!animate) {
            fill.run();
            info.setAlpha(1f);
            return;
        }
        if (!crossfade || info.getAlpha() == 0f) {
            fill.run();
            info.setTranslationX(0f);
            info.animate().alpha(1f).setStartDelay(0).setDuration(400).start();
            return;
        }
        float shift = 12 * ctx.getResources().getDisplayMetrics().density;
        info.animate().alpha(0f).translationX(-shift).setStartDelay(0).setDuration(220).withEndAction(() -> {
            fill.run();
            info.setTranslationX(shift);
            info.animate().alpha(1f).translationX(0f).setStartDelay(CROSSFADE_MS / 3).setDuration(420)
                    .setInterpolator(Motion.EASE).start();
        }).start();
    }

    /**
     * A title's "Series · 2025" as fetched; a match's or session's when, worked out now: "LIVE · Taça da Liga",
     * "Formula 1 · Qualifying · Tomorrow, 21:00".
     */
    private CharSequence metaText(Item item) {
        if (item.sessions.isEmpty()) return item.meta;
        long now = System.currentTimeMillis();
        Session x = item.current(now);
        if (x == null) return item.meta;
        StringBuilder rest = new StringBuilder(item.meta);
        if (!x.label.isEmpty()) rest.append(SEP).append(x.label);
        if (x.start > now) {
            rest.append(SEP).append(when(x.start, now));
            return rest.toString();
        }
        String live = ctx.getString(R.string.shelf_live);
        SpannableString text = new SpannableString(live + SEP + rest);
        text.setSpan(new ForegroundColorSpan(LIVE_RED), 0, live.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** "Today, 20:15", "Tomorrow, 20:00", "Wednesday, 20:00" within the week, "14 November, 19:45" after. */
    private String when(long start, long now) {
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime at = Instant.ofEpochMilli(start).atZone(zone);
        long days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), at.toLocalDate());
        String time = at.format(DateTimeFormatter.ofPattern("HH:mm", locale));
        String day;
        if (days <= 0) day = ctx.getString(R.string.shelf_today);
        else if (days == 1) day = ctx.getString(R.string.shelf_tomorrow);
        else if (days < 7) day = at.format(DateTimeFormatter.ofPattern("EEEE", locale));
        else day = at.format(DateTimeFormatter.ofPattern(ctx.getString(R.string.shelf_date_pattern), locale));
        return Character.toUpperCase(day.charAt(0)) + day.substring(1) + ", " + time;
    }

    // ---- the feed on disk -----------------------------------------------------------------------------

    private File sportsDir() {
        return new File(dir, "sports");
    }

    private void load() {
        FETCH.execute(() -> {
            Map<String, List<Item>> parsed = parseAll();
            main.post(() -> setFeed(parsed));
        });
    }

    private void setFeed(Map<String, List<Item>> next) {
        String was = shown;
        feed = next;
        position.clear();
        if (was != null) {
            shown = null;
            show(was, bottomY);
        }
    }

    /**
     * Fetches the titles again when they are older than REFRESH_MS, the sports when SportsFeed says so, and either
     * one at once when the theme changed what it asks for.
     */
    void refreshIfStale() {
        final Context app = ctx;
        final Settings s = settings;
        final File d = dir, sd = sportsDir();
        FETCH.execute(() -> {
            long now = System.currentTimeMillis();
            boolean changed = false;
            if (s.titles()) {
                JSONObject current = ShelfNet.read(new File(d, "feed.json"));
                long age = now - (current == null ? 0 : current.optLong("fetched", 0));
                boolean same = current != null && s.signature().equals(current.optString("signature"));
                if ((!same || age >= REFRESH_MS) && now - lastAttempt >= RETRY_MS) {
                    lastAttempt = now;
                    try {
                        fetch(app, s, d);
                        changed = true;
                    } catch (Exception e) {
                        Log.w(TAG, "titles fetch failed", e);
                    }
                }
            }
            if (s.sports != null && SportsFeed.stale(ShelfNet.read(new File(sd, "feed.json")), s.sports, now)
                    && now - lastSportsAttempt >= RETRY_MS) {
                lastSportsAttempt = now;
                try {
                    SportsFeed.fetch(app, s.sports, sd);
                    changed = true;
                } catch (Exception e) {
                    Log.w(TAG, "sports fetch failed", e);
                }
            }
            if (s.stremio != null && StremioFeed.stale(ShelfNet.read(new File(d, "stremio/feed.json")), s.stremio, s.key, now)
                    && now - lastStremioAttempt >= STREMIO_RETRY_MS) {
                lastStremioAttempt = now;
                try {
                    StremioFeed.fetch(app, s.stremio, s.key, s.language, new File(d, "stremio"));
                    changed = true;
                } catch (Exception e) {
                    Log.w(TAG, "Stremio fetch failed", e);
                }
            }
            if (s.youtube != null && YouTubeFeed.stale(ShelfNet.read(new File(d, "youtube/feed.json")), s.youtube, now)
                    && now - lastYouTubeAttempt >= RETRY_MS) {
                lastYouTubeAttempt = now;
                try {
                    YouTubeFeed.fetch(app, s.youtube, new File(d, "youtube"));
                    changed = true;
                } catch (Exception e) {
                    Log.w(TAG, "YouTube fetch failed", e);
                }
            }
            if (!changed) return;
            Map<String, List<Item>> parsed = parseAll();
            main.post(() -> setFeed(parsed));
        });
    }

    /** The titles per app, then the one sports list shared by every sports app. */
    private Map<String, List<Item>> parseAll() {
        Map<String, List<Item>> out = settings.titles()
                ? parse(ShelfNet.read(new File(dir, "feed.json")), settings.apps.keySet()) : new HashMap<>();
        if (settings.stremio != null) {
            out.putAll(parse(ShelfNet.read(new File(dir, "stremio/feed.json")),
                    Collections.singleton(settings.stremio.app)));
        }
        if (settings.youtube != null) {
            out.putAll(parse(ShelfNet.read(new File(dir, "youtube/feed.json")), settings.youtube.apps.keySet()));
        }
        if (settings.sports == null) return out;
        JSONObject sports = ShelfNet.read(new File(sportsDir(), "feed.json"));
        List<Item> items = sports == null ? Collections.emptyList() : items(sports.optJSONArray("items"));
        if (items.isEmpty()) return out;
        for (String pkg : settings.sports.apps) out.put(pkg, items);
        return out;
    }

    /** A feed's {"apps": {package: [items]}}, for the packages the theme still asks it for. */
    private Map<String, List<Item>> parse(JSONObject json, Set<String> allowed) {
        if (json == null) return new HashMap<>();
        JSONObject apps = json.optJSONObject("apps");
        if (apps == null) return new HashMap<>();
        Map<String, List<Item>> out = new HashMap<>();
        for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
            String pkg = it.next();
            if (!allowed.contains(pkg)) continue;
            List<Item> items = items(apps.optJSONArray(pkg));
            if (!items.isEmpty()) out.put(pkg, items);
        }
        return out;
    }

    /** A feed's items whose backdrop is on disk; art and logo paths are relative to the shelf's folder. */
    private List<Item> items(JSONArray arr) {
        List<Item> items = new ArrayList<>();
        if (arr == null) return items;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            File art = new File(dir, o.optString("art"));
            if (!art.isFile()) continue;
            String logoName = o.optString("logo", "");
            File logo = logoName.isEmpty() ? null : new File(dir, logoName);
            if (logo != null && !logo.isFile()) logo = null;
            List<Session> sessions = new ArrayList<>();
            JSONArray ss = o.optJSONArray("sessions");
            for (int k = 0; ss != null && k < ss.length(); k++) {
                JSONObject x = ss.optJSONObject(k);
                if (x != null) sessions.add(new Session(x.optString("label"), x.optLong("start"), x.optLong("end")));
            }
            items.add(new Item(o.optString("title"), o.optString("meta"), o.optString("overview"), art, logo, sessions));
        }
        return items;
    }

    // ---- TMDB -----------------------------------------------------------------------------------------

    /** One service's most popular series and films with a backdrop, mixed by popularity, saved with their images. */
    private static JSONObject fetch(Context ctx, Settings s, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        String series = ctx.getString(R.string.shelf_series), film = ctx.getString(R.string.shelf_film);
        JSONObject apps = new JSONObject();
        Set<String> keep = new HashSet<>();
        // New and notable rather than merely popular: sorting by popularity alone fills the shelf with twenty-year-old
        // procedurals and titles that are popular in other countries. Series that aired this year and began in the
        // last six, films from the last three, each with enough votes to have been noticed.
        LocalDate today = LocalDate.now();
        String recent = "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false";
        String[] filters = {
                "&air_date.gte=" + today.minusYears(1) + "&first_air_date.gte=" + today.minusYears(6) + "&vote_count.gte=50",
                "&primary_release_date.gte=" + today.minusYears(3) + "&vote_count.gte=100"};
        for (Map.Entry<String, Integer> app : s.apps.entrySet()) {
            String language = s.language(app.getKey()), lang = language.split("-")[0];
            String base = "?watch_region=" + ShelfNet.enc(s.region(app.getKey())) + "&with_watch_providers=" + app.getValue()
                    + "&language=" + ShelfNet.enc(language) + recent;
            List<JSONObject> titles = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String origin = s.origin(app.getKey());
            String[] wanted = filters;
            if (!origin.isEmpty()) {
                // A broadcaster's own shows (Globo's novelas) have too few TMDB votes for the filters above: what
                // aired this year and was made there, without the news and talk shows on the same schedule.
                String from = "&with_origin_country=" + ShelfNet.enc(origin);
                wanted = new String[]{"&air_date.gte=" + today.minusYears(1) + from + "&without_genres=10763,10767",
                        "&primary_release_date.gte=" + today.minusYears(3) + from};
            }
            discover(s, base, wanted, titles, seen);
            // A small catalogue (RTP Play) has almost nothing that recent: fill up with its popular titles.
            if (titles.size() < s.count) discover(s, base, new String[]{"", ""}, titles, seen);
            titles.sort((a, b) -> Double.compare(b.optDouble("popularity", 0), a.optDouble("popularity", 0)));
            JSONArray items = new JSONArray();
            for (JSONObject r : titles) {
                if (items.length() >= s.count) break;
                String kind = r.getString("kind");
                String id = kind + r.getLong("id");
                String artName = id + ".jpg";
                if (!ShelfNet.download(IMAGES + "w1280" + r.getString("backdrop_path"), new File(dir, artName))) continue;
                keep.add(artName);
                String logoName = "";
                String logoPath = logoPath(s.key, kind, r.getLong("id"), lang);
                if (logoPath != null && ShelfNet.download(IMAGES + "w500" + logoPath, new File(dir, id + "-logo.png"))) {
                    logoName = id + "-logo.png";
                    keep.add(logoName);
                }
                boolean tv = kind.equals("tv");
                String date = r.optString(tv ? "first_air_date" : "release_date", "");
                String year = date.length() >= 4 ? date.substring(0, 4) : "";
                JSONObject item = new JSONObject();
                item.put("title", r.optString(tv ? "name" : "title"));
                item.put("meta", year.isEmpty() ? (tv ? series : film) : (tv ? series : film) + "  ·  " + year);
                item.put("overview", r.optString("overview", "").trim());
                item.put("art", artName);
                item.put("logo", logoName);
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
                // Files only: sports/ is SportsFeed's, which cleans up after itself.
                if (f.isFile() && !f.getName().startsWith("feed.json") && !keep.contains(f.getName())) f.delete();
            }
        }
        return feed;
    }

    /** Adds one page of series and one of films, each filter for its kind, skipping titles already in seen. */
    private static void discover(Settings s, String base, String[] filters, List<JSONObject> titles, Set<String> seen)
            throws Exception {
        String[] kinds = {"tv", "movie"};
        for (int k = 0; k < kinds.length; k++) {
            JSONArray results = tmdb(s.key, "/discover/" + kinds[k] + base + filters[k]).optJSONArray("results");
            if (results == null) continue;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (r.isNull("backdrop_path") || r.optString("backdrop_path", "").isEmpty()) continue;
                // Untranslated titles in another script are the ones the region never watches.
                if (!LATIN.matcher(r.optString(kinds[k].equals("tv") ? "name" : "title")).matches()) continue;
                if (!seen.add(kinds[k] + r.getLong("id"))) continue;
                r.put("kind", kinds[k]);
                titles.add(r);
            }
        }
    }

    /** The title's logo in the shelf's language, else English, else one without language; PNG only. */
    static String logoPath(String key, String kind, long id, String lang) {
        try {
            JSONObject images = tmdb(key, "/" + kind + "/" + id + "/images?include_image_language=" + ShelfNet.enc(lang) + ",en,null");
            JSONArray logos = images.optJSONArray("logos");
            if (logos == null) return null;
            String[] order = {lang, "en", null};
            for (String want : order) {
                for (int i = 0; i < logos.length(); i++) {
                    JSONObject l = logos.getJSONObject(i);
                    String path = l.optString("file_path", "");
                    String iso = l.isNull("iso_639_1") ? null : l.optString("iso_639_1");
                    boolean match = want == null ? iso == null : want.equals(iso);
                    if (match && path.endsWith(".png")) return path;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no logo for " + kind + " " + id, e);
        }
        return null;
    }

    /** A TMDB GET; path carries its own query. Shared with StremioFeed. */
    static JSONObject tmdb(String key, String path) throws Exception {
        // A v4 read access token is a JWT and goes in the header; a v3 API key goes in the query.
        boolean bearer = key.startsWith("eyJ");
        return ShelfNet.json(API + path + (bearer ? "" : "&api_key=" + ShelfNet.enc(key)), bearer ? key : null);
    }
}
