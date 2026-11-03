package ymbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Поиск синхронного текста (LRC): LRCLIB → Musixmatch → NetEase. */
final class Lyrics {
    static final class Result {
        final String lrc, source;
        Result(String lrc, String source) { this.lrc = lrc; this.source = source; }
    }

    private static final Pattern STAMP = Pattern.compile("\\[\\d+:\\d+(?:[.:]\\d+)?\\]");
    private static final Pattern CREDIT = Pattern.compile("(作词|作曲|编曲|制作|混音|母带|录音|和声|吉他|贝斯|监制|出品)\\s*[:：]");
    private static final String UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final String MXM = "https://apic-desktop.musixmatch.com/ws/1.1/";
    private static final String MXM_APP = "web-desktop-app-v1.0";

    private static final Map<String, Result> cache = new LinkedHashMap<String, Result>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Result> e) { return size() > 300; }
    };

    private Lyrics() {}

    static synchronized boolean cached(String key) { return cache.containsKey(key); }
    static synchronized Result get(String key) { return cache.get(key); }
    private static synchronized void put(String key, Result r) { cache.put(key, r); }

    static Result find(Context c, String key, String artist, String title, long durationMs) {
        if (cached(key)) return get(key);
        Result r = null;
        try { r = fromLrclib(artist, title, durationMs); } catch (Exception ignored) {}
        if (r == null) try { r = fromMusixmatch(c, artist, title, durationMs); } catch (Exception ignored) {}
        if (r == null) try { r = fromNetease(artist, title, durationMs); } catch (Exception ignored) {}
        put(key, r);
        return r;
    }

    // ---------------- общее ----------------
    private static String http(String url, String... headers) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", UA);
        for (int i = 0; i + 1 < headers.length; i += 2) con.setRequestProperty(headers[i], headers[i + 1]);
        int code = con.getResponseCode();
        if (code >= 400) { con.disconnect(); throw new Exception("HTTP " + code); }
        InputStream is = con.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        con.disconnect();
        return bo.toString("UTF-8");
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean synced(String lrc) {
        if (lrc == null) return false;
        int lines = 0;
        for (String l : lrc.split("\n")) if (STAMP.matcher(l).find() && STAMP.matcher(l).replaceAll("").trim().length() > 0) lines++;
        return lines >= 3;
    }

    private static String cleanTitle(String t) { return t == null ? "" : t.replaceAll("\\s*[(\\[].*?[)\\]]", "").trim(); }

    private static String mainArtist(String a) {
        if (a == null) return "";
        return a.split(",|&| feat\\.| ft\\.| x ")[0].trim();
    }

    private static boolean sameSong(String wantArtist, String wantTitle, String gotArtist, String gotTitle) {
        String wt = norm(cleanTitle(wantTitle)), gt = norm(cleanTitle(gotTitle));
        if (wt.isEmpty() || gt.isEmpty() || !(wt.contains(gt) || gt.contains(wt))) return false;
        String wa = norm(mainArtist(wantArtist)), ga = norm(gotArtist);
        return !wa.isEmpty() && !ga.isEmpty() && (wa.contains(ga) || ga.contains(wa));
    }

    // ---------------- LRCLIB (lrclib.net) ----------------
    private static Result fromLrclib(String artist, String title, long durMs) throws Exception {
        String[] queries = {
                "track_name=" + enc(title) + "&artist_name=" + enc(artist),
                "track_name=" + enc(cleanTitle(title)) + "&artist_name=" + enc(mainArtist(artist)),
                "q=" + enc(mainArtist(artist) + " " + cleanTitle(title)),
        };
        for (String q : queries) {
            JSONArray arr = new JSONArray(http("https://lrclib.net/api/search?" + q, "Lrclib-Client", "ym-bridge-android"));
            String best = null;
            int bestScore = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String lrc = o.optString("syncedLyrics", "");
                if (!synced(lrc)) continue;
                if (q.startsWith("q=") && !sameSong(artist, title, o.optString("artistName"), o.optString("trackName"))) continue;
                int score = 1;
                double d = o.optDouble("duration", 0);
                if (durMs > 0 && d > 0 && Math.abs(d - durMs / 1000.0) <= 3) score += 2;
                if (score > bestScore) { bestScore = score; best = lrc; }
            }
            if (best != null) return new Result(best, "LRCLIB");
        }
        return null;
    }

    // ---------------- Musixmatch (много русских песен) ----------------
    private static String mxmToken(Context c, boolean fresh) throws Exception {
        SharedPreferences p = c.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE);
        String t = p.getString("mxm_token", "");
        if (!fresh && !t.isEmpty()) return t;
        JSONObject msg = new JSONObject(http(MXM + "token.get?app_id=" + MXM_APP + "&user_language=en&t=" + System.currentTimeMillis(),
                "Cookie", "AWSELBCORS=0; AWSELB=0")).getJSONObject("message");
        JSONObject body = msg.optJSONObject("body");
        t = body != null ? body.optString("user_token", "") : "";
        if (t.isEmpty() || t.startsWith("UpgradeOnly")) throw new Exception("musixmatch: нет токена");
        p.edit().putString("mxm_token", t).apply();
        return t;
    }

    private static Result fromMusixmatch(Context c, String artist, String title, long durMs) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = mxmToken(c, attempt > 0);
            String url = MXM + "macro.subtitles.get?format=json&namespace=lyrics_richsynched&subtitle_format=lrc"
                    + "&app_id=" + MXM_APP + "&usertoken=" + enc(token)
                    + "&q_artist=" + enc(mainArtist(artist)) + "&q_track=" + enc(cleanTitle(title))
                    + (durMs > 0 ? "&q_duration=" + (durMs / 1000) : "");
            JSONObject msg = new JSONObject(http(url, "Cookie", "AWSELBCORS=0; AWSELB=0")).getJSONObject("message");
            int code = msg.getJSONObject("header").optInt("status_code");
            if (code == 401) continue;                         // токен устарел — берём новый
            JSONObject body = msg.optJSONObject("body");
            JSONObject calls = body != null ? body.optJSONObject("macro_calls") : null;
            if (calls == null) return null;
            JSONObject sub = calls.optJSONObject("track.subtitles.get");
            JSONObject sm = sub != null ? sub.optJSONObject("message") : null;
            if (sm != null && sm.getJSONObject("header").optInt("status_code") == 401) continue;
            JSONObject sb = sm != null ? sm.optJSONObject("body") : null;
            JSONArray list = sb != null ? sb.optJSONArray("subtitle_list") : null;
            if (list == null || list.length() == 0) return null;
            String lrc = list.getJSONObject(0).getJSONObject("subtitle").optString("subtitle_body", "");
            return synced(lrc) ? new Result(lrc, "Musixmatch") : null;
        }
        return null;
    }

    // ---------------- NetEase ----------------
    private static Result fromNetease(String artist, String title, long durMs) throws Exception {
        String s = mainArtist(artist) + " " + cleanTitle(title);
        JSONObject res = new JSONObject(http("https://music.163.com/api/search/get?type=1&limit=10&s=" + enc(s),
                "Referer", "https://music.163.com/")).optJSONObject("result");
        JSONArray songs = res != null ? res.optJSONArray("songs") : null;
        if (songs == null) return null;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.getJSONObject(i);
            JSONArray ar = song.optJSONArray("artists");
            StringBuilder names = new StringBuilder();
            if (ar != null) for (int k = 0; k < ar.length(); k++) names.append(ar.getJSONObject(k).optString("name")).append(' ');
            if (!sameSong(artist, title, names.toString(), song.optString("name"))) continue;
            long d = song.optLong("duration", 0);
            if (durMs > 0 && d > 0 && Math.abs(d - durMs) > 5000) continue;
            JSONObject lyr = new JSONObject(http("https://music.163.com/api/song/lyric?lv=1&kv=1&tv=-1&id=" + song.optLong("id"),
                    "Referer", "https://music.163.com/"));
            JSONObject l = lyr.optJSONObject("lrc");
            String lrc = l != null ? l.optString("lyric", "") : "";
            if (lrc.contains("纯音乐")) return null;
            StringBuilder out = new StringBuilder();
            for (String line : lrc.split("\n")) if (!CREDIT.matcher(line).find()) out.append(line).append('\n');
            if (synced(out.toString())) return new Result(out.toString(), "NetEase");
        }
        return null;
    }
}
