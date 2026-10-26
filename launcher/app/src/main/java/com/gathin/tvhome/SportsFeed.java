package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * top_shelf's "sports": the next match of each followed team and the next Formula 1 weekend, for the sports apps on
 * Home. Fixtures, crests and team photos come from TheSportsDB's free tier (no account; it gives a team's next match
 * only, in any competition), the F1 sessions from Jolpica (the Ergast successor, also free). The feed holds whole
 * matches and weekends with their sessions; which session is next, and whether it is live, is decided when the
 * shelf shows it.
 */
final class SportsFeed {
    private static final String TAG = "TopShelf";
    private static final String SDB = "https://www.thesportsdb.com/api/v1/json/123/";
    private static final String F1_NEXT = "https://api.jolpi.ca/ergast/f1/current/next.json";
    private static final String F1_LEAGUE = "4370";
    private static final long MINUTE = 60_000L;
    static final long MATCH_MS = 115 * MINUTE;
    /** Fetch again after this long even when nothing ended: a kick-off time can still move. */
    static final long REFRESH_MS = 3 * 60 * MINUTE;
    /** Bumped when the images the feed makes change, so a feed made by an older build is fetched again. */
    private static final int FORMAT = 3;
    /** Crests are drawn this tall into the shelf's logo, side by side. */
    private static final int CREST_PX = 144;

    static final class Settings {
        final List<String> apps;
        final List<String> teams;
        final boolean f1;
        final Map<String, String> names;
        /** A team's own backdrop by its id ("art"), instead of one of TheSportsDB's photos. */
        final Map<String, Art> art;

        Settings(List<String> apps, List<String> teams, boolean f1, Map<String, String> names, Map<String, Art> art) {
            this.apps = apps;
            this.teams = teams;
            this.f1 = f1;
            this.names = names;
            this.art = art;
        }

        String signature() {
            return FORMAT + "|" + teams + "|" + f1 + "|" + names + "|" + art;
        }
    }

    /**
     * An image URL, and where its subject is (0 to 1 across and down) with how far to zoom in. The shelf's text and
     * scrim cover the left and the bottom, so the image is cropped once to put the subject at the right, higher up.
     */
    static final class Art {
        final String url;
        final float x, y, zoom;

        Art(String url, float x, float y, float zoom) {
            this.url = url;
            this.x = x;
            this.y = y;
            this.zoom = zoom;
        }

        boolean cropped() {
            return zoom > 1f || x != 0.5f || y != 0.5f;
        }

        @Override
        public String toString() {
            return url + "@" + x + "," + y + "x" + zoom;
        }
    }

    /** Where a cropped subject lands on screen: right of the shelf's text, above the dock's scrim. */
    private static final float SUBJECT_X = 0.68f, SUBJECT_Y = 0.40f;

    private SportsFeed() {
    }

    /** Stale when the theme asks for something else, a match or weekend in it is over, or it is REFRESH_MS old. */
    static boolean stale(JSONObject feed, Settings s, long now) {
        if (feed == null || !s.signature().equals(feed.optString("signature"))) return true;
        if (now - feed.optLong("fetched", 0) > REFRESH_MS) return true;
        JSONArray items = feed.optJSONArray("items");
        if (items == null) return true;
        for (int i = 0; i < items.length(); i++) {
            JSONArray sessions = items.optJSONObject(i).optJSONArray("sessions");
            if (sessions == null || sessions.length() == 0) continue;
            if (sessions.optJSONObject(sessions.length() - 1).optLong("end") < now) return true;
        }
        return false;
    }

    static JSONObject fetch(Context ctx, Settings s, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        JSONArray items = new JSONArray();
        Set<String> keep = new HashSet<>();
        Set<String> events = new HashSet<>();
        for (String team : s.teams) {
            try {
                JSONObject item = match(s, team, dir, keep, events);
                if (item != null) items.put(item);
            } catch (Exception e) {
                Log.w(TAG, "no fixture for team " + team, e);
            }
        }
        if (s.f1) {
            try {
                JSONObject item = formula1(ctx, dir, keep);
                if (item != null) items.put(item);
            } catch (Exception e) {
                Log.w(TAG, "no F1 weekend", e);
            }
        }
        JSONObject feed = new JSONObject();
        feed.put("fetched", System.currentTimeMillis());
        feed.put("signature", s.signature());
        feed.put("items", items);
        ShelfNet.save(feed, new File(dir, "feed.json"));
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith("feed.json") && !keep.contains(f.getName())) f.delete();
            }
        }
        return feed;
    }

    /** The team's next match: its own photo behind, both crests as the logo. Null when it has none scheduled. */
    private static JSONObject match(Settings s, String team, File dir, Set<String> keep, Set<String> events)
            throws Exception {
        JSONArray next = ShelfNet.json(SDB + "eventsnext.php?id=" + ShelfNet.enc(team), null).optJSONArray("events");
        if (next == null || next.length() == 0) return null;
        JSONObject ev = next.getJSONObject(0);
        // Two followed teams playing each other: one item.
        if (!events.add(ev.optString("idEvent"))) return null;
        long start = start(ev);
        if (start <= 0) return null;

        String artName = null;
        Art own = s.art.get(team);
        if (own != null) artName = ownArt(dir, keep, team, own);
        if (artName == null) {
            JSONArray teams = ShelfNet.json(SDB + "lookupteam.php?id=" + ShelfNet.enc(team), null).optJSONArray("teams");
            JSONObject t = teams == null ? null : teams.optJSONObject(0);
            String artUrl = pick(t == null ? null : fanart(t), ev.optString("idEvent").hashCode());
            if (artUrl == null) artUrl = nonEmpty(ev.optString("strThumb"));
            if (artUrl == null || !ShelfNet.download(artUrl, new File(dir, ShelfNet.fileName(artUrl)))) return null;
            artName = ShelfNet.fileName(artUrl);
        }
        keep.add(artName);

        // "names" covers teams as well as competitions: national teams come in English ("Denmark").
        String home = rename(s, ev.optString("strHomeTeam")), away = rename(s, ev.optString("strAwayTeam"));
        String logo = crests(dir, keep, "e" + ev.optString("idEvent"),
                nonEmpty(ev.optString("strHomeTeamBadge")), nonEmpty(ev.optString("strAwayTeamBadge")));
        String venue = ev.optString("strVenue", "").trim();

        JSONObject item = new JSONObject();
        item.put("title", home + " – " + away);
        item.put("meta", rename(s, ev.optString("strLeague")));
        item.put("overview", home + "  vs  " + away + (venue.isEmpty() ? "" : "  ·  " + venue));
        item.put("art", dir.getName() + "/" + artName);
        item.put("logo", logo == null ? "" : dir.getName() + "/" + logo);
        JSONArray sessions = new JSONArray();
        sessions.put(session("", start, start + MATCH_MS));
        item.put("sessions", sessions);
        return item;
    }

    /** The next Grand Prix with every session of its weekend, the F1 logo and one of the series' photos. */
    private static JSONObject formula1(Context ctx, File dir, Set<String> keep) throws Exception {
        JSONArray races = ShelfNet.json(F1_NEXT, null).getJSONObject("MRData").getJSONObject("RaceTable")
                .optJSONArray("Races");
        if (races == null || races.length() == 0) return null;
        JSONObject race = races.getJSONObject(0);

        List<JSONObject> list = new ArrayList<>();
        addSession(list, race.optJSONObject("FirstPractice"), ctx.getString(R.string.shelf_fp1), 60);
        addSession(list, race.optJSONObject("SecondPractice"), ctx.getString(R.string.shelf_fp2), 60);
        addSession(list, race.optJSONObject("ThirdPractice"), ctx.getString(R.string.shelf_fp3), 60);
        addSession(list, race.optJSONObject("SprintQualifying"), ctx.getString(R.string.shelf_sprint_quali), 45);
        addSession(list, race.optJSONObject("Sprint"), ctx.getString(R.string.shelf_sprint), 45);
        addSession(list, race.optJSONObject("Qualifying"), ctx.getString(R.string.shelf_quali), 60);
        addSession(list, race, ctx.getString(R.string.shelf_race), 120);
        if (list.isEmpty()) return null;
        list.sort((a, b) -> Long.compare(a.optLong("start"), b.optLong("start")));

        JSONArray leagues = ShelfNet.json(SDB + "lookupleague.php?id=" + F1_LEAGUE, null).optJSONArray("leagues");
        JSONObject league = leagues == null ? null : leagues.optJSONObject(0);
        String artUrl = pick(league == null ? null : fanart(league), race.optInt("round"));
        if (artUrl == null || !ShelfNet.download(artUrl, new File(dir, ShelfNet.fileName(artUrl)))) return null;
        keep.add(ShelfNet.fileName(artUrl));
        String logoUrl = league == null ? null : nonEmpty(league.optString("strLogo"));
        String logo = null;
        if (logoUrl != null && ShelfNet.download(logoUrl, new File(dir, ShelfNet.fileName(logoUrl)))) {
            logo = ShelfNet.fileName(logoUrl);
            keep.add(logo);
        }

        JSONObject circuit = race.optJSONObject("Circuit");
        String place = circuit == null ? "" : circuit.optString("circuitName", "");
        JSONObject item = new JSONObject();
        item.put("title", race.optString("raceName"));
        item.put("meta", ctx.getString(R.string.shelf_f1));
        item.put("overview", race.optString("raceName") + (place.isEmpty() ? "" : "  ·  " + place));
        item.put("art", dir.getName() + "/" + ShelfNet.fileName(artUrl));
        item.put("logo", logo == null ? "" : dir.getName() + "/" + logo);
        item.put("sessions", new JSONArray(list));
        return item;
    }

    private static void addSession(List<JSONObject> list, JSONObject o, String label, int minutes) throws Exception {
        if (o == null || o.optString("date").isEmpty() || o.optString("time").isEmpty()) return;
        long start = Instant.parse(o.getString("date") + "T" + o.getString("time")).toEpochMilli();
        list.add(session(label, start, start + minutes * MINUTE));
    }

    private static JSONObject session(String label, long start, long end) throws Exception {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("start", start);
        o.put("end", end);
        return o;
    }

    /** Kick-off in UTC: TheSportsDB's strTimestamp has no zone, dateEvent and strTime are the fallback. */
    private static long start(JSONObject ev) {
        String ts = ev.optString("strTimestamp", "");
        if (ts.isEmpty() && !ev.optString("dateEvent").isEmpty() && !ev.optString("strTime").isEmpty()) {
            ts = ev.optString("dateEvent") + "T" + ev.optString("strTime");
        }
        if (ts.isEmpty()) return 0;
        try {
            return Instant.parse(ts.endsWith("Z") || ts.contains("+") ? ts : ts + "Z").toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Downloads a team's own image, named by its URL and focus (a news site's image path is not unique the way
     * TheSportsDB's are), and crops it to 16:9 around the focus when one is set. Null when it cannot be had.
     */
    private static String ownArt(File dir, Set<String> keep, String team, Art art) {
        String base = "art-" + team + "-" + Integer.toHexString(art.toString().hashCode());
        String src = base + ".src";
        if (!ShelfNet.download(art.url, new File(dir, src))) return null;
        keep.add(src);
        if (!art.cropped()) return src;
        String name = base + ".jpg";
        File out = new File(dir, name);
        if (!out.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(new File(dir, src).getPath());
            if (b == null) return null;
            // The largest 16:9 window at this zoom, moved so the focus sits at SUBJECT_X, SUBJECT_Y, kept inside.
            float w = Math.min(b.getWidth(), b.getHeight() * 16f / 9f) / art.zoom, h = w * 9f / 16f;
            float left = clamp(art.x * b.getWidth() - SUBJECT_X * w, 0, b.getWidth() - w);
            float top = clamp(art.y * b.getHeight() - SUBJECT_Y * h, 0, b.getHeight() - h);
            int outW = Math.min(1920, Math.round(w * 1.5f)), outH = Math.round(outW * 9f / 16f);
            Bitmap crop = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            new Canvas(crop).drawBitmap(b, new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h)),
                    new RectF(0, 0, outW, outH), new Paint(Paint.FILTER_BITMAP_FLAG));
            b.recycle();
            try (OutputStream o = new FileOutputStream(out)) {
                crop.compress(Bitmap.CompressFormat.JPEG, 90, o);
            } catch (Exception e) {
                Log.w(TAG, "cannot save cropped art", e);
                return null;
            } finally {
                crop.recycle();
            }
        }
        keep.add(name);
        return name;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Both crests side by side in one transparent image, the shelf's logo for a match. */
    private static String crests(File dir, Set<String> keep, String id, String homeUrl, String awayUrl) {
        if (homeUrl == null || awayUrl == null) return null;
        String name = id + "-pair.png";
        File out = new File(dir, name);
        File home = new File(dir, ShelfNet.fileName(homeUrl)), away = new File(dir, ShelfNet.fileName(awayUrl));
        if (!ShelfNet.download(homeUrl, home) || !ShelfNet.download(awayUrl, away)) return null;
        keep.add(home.getName());
        keep.add(away.getName());
        if (!out.isFile()) {
            Bitmap a = trimmed(BitmapFactory.decodeFile(home.getPath()));
            Bitmap b = trimmed(BitmapFactory.decodeFile(away.getPath()));
            if (a == null || b == null) return null;
            // Space alone between them: a dash in the middle of the image sat beside crests whose weight is elsewhere
            // (Liverpool's bird is at the top of its crest) and read as a stray hyphen.
            int gap = Math.round(CREST_PX * 0.42f);
            int ha = opticalHeight(a), hb = opticalHeight(b), h = Math.max(ha, hb);
            int wa = Math.round(ha * a.getWidth() / (float) a.getHeight());
            int wb = Math.round(hb * b.getWidth() / (float) b.getHeight());
            Bitmap both = Bitmap.createBitmap(wa + gap + wb, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(both);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            float ta = (h - ha) / 2f, tb = (h - hb) / 2f;
            c.drawBitmap(a, new Rect(0, 0, a.getWidth(), a.getHeight()), new RectF(0, ta, wa, ta + ha), p);
            c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), new RectF(wa + gap, tb, wa + gap + wb, tb + hb), p);
            a.recycle();
            b.recycle();
            try (OutputStream o = new FileOutputStream(out)) {
                both.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (Exception e) {
                Log.w(TAG, "cannot save crests", e);
                return null;
            } finally {
                both.recycle();
            }
        }
        keep.add(name);
        return name;
    }

    /**
     * The crest without the transparent margin around it, which differs from badge to badge: drawn to the same height
     * untrimmed, Portugal's came out visibly smaller than Denmark's.
     */
    private static Bitmap trimmed(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 24) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < left || bottom < top) return src;
        Bitmap out = Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1);
        if (out != src) src.recycle();
        return out;
    }

    /**
     * Optical sizing: a crest that fills little of its box (Portugal's open cross) looks smaller than a solid one
     * (Denmark's disc) at the same height, so it is drawn taller, up to a quarter, by the square root of the fill.
     */
    private static int opticalHeight(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int solid = 0;
        for (int p : px) if ((p >>> 24) > 128) solid++;
        float fill = Math.max(0.05f, solid / (float) px.length);
        float scale = (float) Math.sqrt(0.7f / fill);
        return Math.round(CREST_PX * Math.max(0.9f, Math.min(1.25f, scale)));
    }

    private static List<String> fanart(JSONObject o) {
        List<String> urls = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String u = nonEmpty(o.optString("strFanart" + i));
            if (u != null) urls.add(u);
        }
        return urls;
    }

    /** One of the photos, the same one for the same match or round, so a refresh does not swap it. */
    private static String pick(List<String> urls, int seed) {
        if (urls == null || urls.isEmpty()) return null;
        return urls.get(Math.floorMod(seed, urls.size()));
    }

    private static String rename(Settings s, String name) {
        String n = s.names.get(name);
        return n != null ? n : name;
    }

    private static String nonEmpty(String s) {
        return s == null || s.trim().isEmpty() || "null".equals(s) ? null : s.trim();
    }
}
