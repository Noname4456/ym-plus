package ymbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * «Островок+»: повторяет текущие уведомления выбранных приложений (навигация, такси, доставка…)
 * в формате vivo SuperX, который понимает атомный островок и карточки в шторке OriginOS.
 *
 * Протокол SuperX (из com.vivo.systemuiplugin):
 *   notification.superx.operation  0 — показать/обновить
 *   notification.superx.template   1 главная инфа, 2 прогресс, 9 навигация (вождение)
 *   notification.superx.scene      NAVIGATION / ORDER / …
 *   notification.superx.clickResp  PendingIntent по нажатию
 *   notification.superx.baseInfos  {title, content, icon}
 *   notification.superx.infos      данные шаблона карточки
 *   notification.superx.island     {template, leftTemplate, rightTemplate, leftInfo{icon,content}, rightInfo{content,…}}
 */
final class IslandMirror {
    static final String CHANNEL = "island2";     // обычная важность (без звука): «тихие» SuperX vivo может отбрасывать
    static final String PREF_APPS = "island_apps";
    static final String PREF_ON = "island_on";

    enum Kind { NAVI, PROGRESS, INFO }

    /** Известные российские приложения: пакет → (название, тип). Отмечаются по умолчанию, если установлены. */
    static final LinkedHashMap<String, Object[]> KNOWN = new LinkedHashMap<String, Object[]>();
    static {
        KNOWN.put("ru.yandex.yandexnavi", new Object[]{"Яндекс Навигатор", Kind.NAVI});
        KNOWN.put("ru.yandex.yandexmaps", new Object[]{"Яндекс Карты", Kind.NAVI});
        KNOWN.put("ru.dublgis.dgismobile", new Object[]{"2ГИС", Kind.NAVI});
        KNOWN.put("ru.yandex.taxi", new Object[]{"Яндекс Go", Kind.PROGRESS});
        KNOWN.put("ru.yandex.uber", new Object[]{"Fasten (Uber)", Kind.PROGRESS});
        KNOWN.put("ru.yandex.uber_by", new Object[]{"Fasten Беларусь", Kind.PROGRESS});
        KNOWN.put("ru.foodfox.client", new Object[]{"Яндекс Еда", Kind.PROGRESS});
        KNOWN.put("com.yandex.lavka", new Object[]{"Яндекс Лавка", Kind.PROGRESS});
        KNOWN.put("ru.yandex.market", new Object[]{"Яндекс Маркет", Kind.PROGRESS});
        KNOWN.put("ru.ozon.app.android", new Object[]{"Ozon", Kind.PROGRESS});
        KNOWN.put("com.wildberries.ru", new Object[]{"Wildberries", Kind.PROGRESS});
        KNOWN.put("ru.instamart", new Object[]{"Купер", Kind.PROGRESS});
        KNOWN.put("ru.samokat.android", new Object[]{"Самокат", Kind.PROGRESS});
        KNOWN.put("ru.vkusvill", new Object[]{"ВкусВилл", Kind.PROGRESS});
        KNOWN.put("ru.citymobil.mobile", new Object[]{"Ситимобил", Kind.PROGRESS});
    }

    // для экрана: последние уведомления от отслеживаемых и прочих приложений с «живыми» уведомлениями
    static final Map<String, String> lastSeen = new LinkedHashMap<String, String>();
    static final Set<String> seenOngoing = new HashSet<String>();
    static volatile String lastError = "";

    private final Context ctx;
    private final NotificationManager nm;
    private final Map<String, Integer> ids = new HashMap<String, Integer>();
    private static int nextId = 1000 + (int) ((System.currentTimeMillis() / 1000) % 800000);   // ключ исходного уведомления → наш id
    private final Map<String, Bitmap> appIcons = new HashMap<String, Bitmap>();

    IslandMirror(Context c) {
        ctx = c;
        nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.deleteNotificationChannel("island"); } catch (Exception ignored) {}
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Островок для приложений", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        clearAll();                                       // после перезапуска не оставляем зависших островков
    }

    // ===================== настройки =====================
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return BridgeService.islandOnly(c) && prefs(c).getBoolean(PREF_ON, true); }   // островок vivo пускает только у Baidu-версии

    static Set<String> apps(Context c) {
        Set<String> s = prefs(c).getStringSet(PREF_APPS, null);
        if (s != null) return new HashSet<String>(s);
        Set<String> def = new HashSet<String>();                // по умолчанию — все известные установленные
        for (String p : KNOWN.keySet()) if (installed(c, p)) def.add(p);
        return def;
    }

    static void setApp(Context c, String pkg, boolean on) {
        Set<String> s = apps(c);
        if (on) s.add(pkg); else s.remove(pkg);
        prefs(c).edit().putStringSet(PREF_APPS, s).apply();
    }

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    static String label(Context c, String pkg) {
        Object[] k = KNOWN.get(pkg);
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(c.getPackageManager().getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return k != null ? (String) k[0] : pkg;
        }
    }

    static Kind kindOf(String pkg, Notification n) {
        Object[] k = KNOWN.get(pkg);
        if (k != null && k[1] == Kind.NAVI) return Kind.NAVI;
        if (Notification.CATEGORY_NAVIGATION.equals(n.category)) return Kind.NAVI;
        if (n.extras != null && n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return Kind.PROGRESS;
        return k != null ? (Kind) k[1] : Kind.INFO;
    }

    // ===================== входящие уведомления =====================
    static boolean isLive(Notification n) {
        return n != null && ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
    }

    private static boolean isMedia(Notification n) {
        return n.extras != null && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    void onPosted(StatusBarNotification sbn) {
        if (sbn == null || ctx.getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (isMedia(n)) return;
        if (!isLive(n)) {                      // поездку отменили/завершили — уведомление стало обычным
            if (ids.containsKey(sbn.getKey())) remove(sbn.getKey());
            if (DELIVERY.contains(sbn.getPackageName())) onOrderPush(sbn, n);
            return;
        }
        String pkg = sbn.getPackageName();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence sub = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        int pMax = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0), p = n.extras.getInt(Notification.EXTRA_PROGRESS, 0);
        synchronized (lastSeen) {
            seenOngoing.add(pkg);
            lastSeen.put(pkg, BridgeService.stamp() + "«" + nz(title) + "» · «" + nz(text) + "»"
                    + (big != null && !big.toString().equals(nz(text)) ? " · big «" + big + "»" : "")
                    + (sub != null ? " · sub «" + sub + "»" : "")
                    + (pMax > 0 ? " · прогресс " + p + "/" + pMax : "")
                    + (n.category != null ? " · " + n.category : "")
                    + (n.getLargeIcon() != null ? " · есть картинка" : ""));
        }
        if (!enabled(ctx) || !apps(ctx).contains(pkg)) return;
        try {
            Kind kind = kindOf(pkg, n);
            String t = str(title), tx = str(!isEmpty(text) ? text : big), sb = str(sub);
            Bitmap ic = null;

            if (kind == Kind.NAVI) {
                // Карты рисуют манёвр своим макетом — читаем текст и стрелку прямо из него
                RvData rv = readRemoteViews(n);
                String all = t + " " + tx + " " + str(big) + " " + rv.text;
                synchronized (lastSeen) {
                    if (!rv.text.isEmpty()) lastSeen.put(pkg, lastSeen.get(pkg) + " · макет «" + rv.text + "»" + (rv.image != null ? " · стрелка" : ""));
                }
                if (!guiding(all)) {
                    // временно «Navigator is running»: держим последний манёвр до 90 с, потом убираем
                    final String key = sbn.getKey();
                    final Long since = lastGuide.get(key);
                    if (since == null || System.currentTimeMillis() - since > 90_000) { remove(key); return; }
                    h.postDelayed(new Runnable() {            // маршрут так и не продолжился — убираем
                        @Override public void run() {
                            Long now = lastGuide.get(key);
                            if (now != null && now.equals(since)) { lastGuide.remove(key); remove(key); }
                        }
                    }, 91_000 - (System.currentTimeMillis() - since));
                    return;
                }
                lastGuide.put(sbn.getKey(), System.currentTimeMillis());
                if (!guiding(t + " " + tx) && !rv.lines.isEmpty()) {     // в тексте пусто — берём строки из макета
                    t = rv.lines.get(0);
                    tx = rv.lines.size() > 1 ? rv.lines.get(1) : "";
                    if (sb.isEmpty() && rv.lines.size() > 2) sb = rv.lines.get(2);
                }
                String trip = tripInfo(rv.lines, t + " " + tx);
                if (!trip.isEmpty()) sb = trip;
                ic = rv.image;
                if (ic == null) {
                    int arrow = maneuver(t + " " + tx);
                    if (n.getLargeIcon() == null) ic = draw(arrow);
                }
            }
            if (t.isEmpty() && tx.isEmpty()) return;
            if (ic == null) ic = iconFor(sbn);
            post(sbn.getKey(), pkg, kind, t, tx, sb, pMax > 0 ? Math.round(100f * p / pMax) : -1, ic, n.contentIntent);
            lastError = "";
        } catch (Exception e) {
            lastError = BridgeService.stamp() + e;
        }
    }

    private final Map<String, Long> lastGuide = new HashMap<String, Long>();

    static final class RvData {
        String text = "";
        java.util.List<String> lines = new java.util.ArrayList<String>();
        Bitmap image;
    }

    private static final java.util.regex.Pattern KM = java.util.regex.Pattern.compile(
            "\\d+[.,]?\\d*\\s*(км|km|mi|м|m)(?!\\p{L})", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern DUR = java.util.regex.Pattern.compile(
            "(\\d+\\s*(ч|h|hr)\\s*)?\\d+\\s*(мин|min)(?!\\p{L})|\\d+\\s*(ч|h|hr)(?!\\p{L})", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern ETA = java.util.regex.Pattern.compile("(?<![\\d:])([01]?\\d|2[0-3]):[0-5]\\d(?![\\d:])(\\s?[AaPp]\\.?[Mm]\\.?)?");

    /** Остаток пути из строк макета (кроме строк ближайшего манёвра): «12 км · 18 мин · 18:42». */
    static String tripInfo(java.util.List<String> lines, String maneuver) {
        String km = "", dur = "", eta = "";
        for (String l : lines) {
            if (maneuver.contains(l)) continue;
            java.util.regex.Matcher m;
            if (km.isEmpty() && (m = KM.matcher(l)).find()) km = m.group().trim();
            if (dur.isEmpty() && (m = DUR.matcher(l)).find()) dur = m.group().trim();
            if (eta.isEmpty() && (m = ETA.matcher(l)).find()) eta = m.group(1) + l.substring(m.end(1), m.end());
        }
        StringBuilder sb = new StringBuilder();
        for (String x : new String[]{km, dur, eta}) if (!x.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(x);
        return sb.toString();
    }

    /** Текст и самая крупная картинка из своего макета уведомления (RemoteViews). */
    private RvData readRemoteViews(Notification n) {
        RvData d = new RvData();
        android.widget.RemoteViews[] all = {n.bigContentView, n.contentView, n.headsUpContentView};
        for (android.widget.RemoteViews rv : all) {
            if (rv == null) continue;
            try {
                android.view.View v = rv.apply(ctx, new android.widget.FrameLayout(ctx));
                collect(v, d);
                if (!d.lines.isEmpty()) break;
            } catch (Exception ignored) {}
        }
        StringBuilder sb = new StringBuilder();
        for (String l : d.lines) sb.append(sb.length() > 0 ? " | " : "").append(l);
        d.text = sb.toString();
        return d;
    }

    private void collect(android.view.View v, RvData d) {
        if (v.getVisibility() != android.view.View.VISIBLE) return;
        if (v instanceof android.widget.TextView) {
            String s = String.valueOf(((android.widget.TextView) v).getText()).trim();
            if (!s.isEmpty() && !d.lines.contains(s)) d.lines.add(s);
        } else if (v instanceof android.widget.ImageView) {
            Drawable dr = ((android.widget.ImageView) v).getDrawable();
            Bitmap b = dr != null ? toBitmap(dr) : null;
            // только примерно квадратные картинки — это стрелка манёвра, а не полоса прогресса маршрута
            if (b != null && b.getWidth() >= 24 && b.getHeight() >= 24) {
                float r = (float) b.getWidth() / b.getHeight();
                if (r > 0.6f && r < 1.67f && (d.image == null || b.getWidth() * b.getHeight() > d.image.getWidth() * d.image.getHeight()))
                    d.image = b;
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), d);
        }
    }

    // ===================== доставка: обычные пуши «собрали / в пути / рядом» =====================
    static final Set<String> DELIVERY = new HashSet<String>(java.util.Arrays.asList(
            "ru.foodfox.client", "com.yandex.lavka", "ru.yandex.market", "ru.ozon.app.android", "com.wildberries.ru",
            "ru.instamart", "ru.samokat.android", "ru.vkusvill"));
    private static final java.util.regex.Pattern ADS = java.util.regex.Pattern.compile(
            "(?iu)скидк|промокод|акци|распрод|кэшбэк|кешбэк|балл|купон|подар|новинк|выгод|бесплатн|%");
    // {регулярка, подпись, прогресс}; порядок важен — сначала финал
    private static final Object[][] STAGES = {
            {"(?iu)отмен", null, -1},
            {"(?iu)доставлен|доставили|получен|вручен|приятного аппетита|оставили у двери", "Доставлено", 100},
            {"(?iu)пункт(е|а)? выдачи|постамат|можно забрать|ждёт вас|ждет вас|готов к выдаче", "Можно забрать", 100},
            {"(?iu)рядом|подъезжа|почти у вас|почти на месте|у двери|скоро будет|прибыл|подходит", "Рядом", 90},
            {"(?iu)в пути|едет|везёт|везет|выехал|забрал заказ|передали курьеру|передан курьеру|отправлен|в доставке|будет через|привезёт через|привезет через", "В пути", 70},
            {"(?iu)ищем курьера|ищут курьера|назначен|курьер найден|нашли курьера", "Ищут курьера", 45},
            {"(?iu)собира|собрали|собран|готовит|готовят|приготов|принят|подтвержд|оформлен|упаков", "Собирают", 20},
    };
    private final Map<String, Runnable> orderTimers = new HashMap<String, Runnable>();

    private void onOrderPush(StatusBarNotification sbn, Notification n) {
        final String pkg = sbn.getPackageName();
        String t = str(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence bigCs = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String tx = str(bigCs != null ? bigCs : n.extras.getCharSequence(Notification.EXTRA_TEXT));
        String all = t + " " + tx;
        synchronized (lastSeen) { lastSeen.put(pkg, BridgeService.stamp() + "пуш «" + t + "» · «" + tx + "»"); }
        if (!enabled(ctx) || !apps(ctx).contains(pkg)) return;
        if (ADS.matcher(all).find() && !all.toLowerCase().contains("курьер")) return;       // реклама
        String stage = null; int progress = -1; boolean match = false;
        for (Object[] st : STAGES) {
            if (java.util.regex.Pattern.compile((String) st[0]).matcher(all).find()) {
                stage = (String) st[1]; progress = (Integer) st[2]; match = true; break;
            }
        }
        if (!match) return;
        final String key = "order:" + pkg;
        if (stage == null) { remove(key); return; }                                       // отменили
        java.util.regex.Matcher am = AMOUNT.matcher(all);
        String main = am.find() && progress < 100 ? stage + " " + am.group().trim().replaceAll("минут[аы]?", "мин").replaceAll("час[аов]{0,2}", "ч") : stage;
        String body = tx.isEmpty() ? t : tx;
        if (body.length() > 70) body = body.substring(0, 69) + "…";
        try {
            post(key, pkg, Kind.PROGRESS, main, label(ctx, pkg), body, progress, iconFor(sbn), n.contentIntent);
            lastError = "";
        } catch (Exception e) { lastError = String.valueOf(e); }
        // сам островок не уходит: после «доставлено» держим 3 мин, иначе — 90 мин без новостей
        Runnable old = orderTimers.remove(key);
        if (old != null) h.removeCallbacks(old);
        Runnable r = new Runnable() { @Override public void run() { orderTimers.remove(key); remove(key); } };
        orderTimers.put(key, r);
        h.postDelayed(r, progress >= 100 ? 3 * 60_000L : 90 * 60_000L);
    }

    void onRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        remove(sbn.getKey());
        synchronized (lastSeen) {                 // в диагностике помечаем, что уведомление уже ушло
            String was = lastSeen.get(sbn.getPackageName());
            if (was != null && !was.endsWith("(убрано)") && isLive(sbn.getNotification()))
                lastSeen.put(sbn.getPackageName(), was + " · " + BridgeService.stamp() + "(убрано)");
        }
    }

    private final Map<Integer, Notification> last = new HashMap<Integer, Notification>();
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Убрать с островка: vivo держит островок, пока не получит операцию «удалить» (2). */
    void remove(String key) {
        final Integer id = ids.remove(key);
        if (id == null) return;
        Notification prev = last.remove(id);
        if (prev != null) {
            try {
                prev.extras.putInt("notification.superx.operation", 2);
                Bundle cap = prev.extras.getBundle("notification.superx.capsule");
                if (cap != null) cap.putInt("notification.superx.capsule.state", 0);
                nm.notify(id, prev);
            } catch (Exception ignored) {}
        }
        h.postDelayed(new Runnable() { @Override public void run() { nm.cancel(id); } }, 400);
    }

    /** Убрать всё, что мост когда-либо выводил на островок (в т.ч. после перезапуска). */
    void clearAll() {
        for (String k : new java.util.ArrayList<String>(ids.keySet())) remove(k);
        try {
            for (android.service.notification.StatusBarNotification a : nm.getActiveNotifications()) {
                Notification n = a.getNotification();
                if (n.extras != null && n.extras.containsKey("notification.superx.operation")) {
                    n.extras.putInt("notification.superx.operation", 2);
                    try { nm.notify(a.getId(), n); } catch (Exception ignored) {}
                    final int id = a.getId();
                    h.postDelayed(new Runnable() { @Override public void run() { nm.cancel(id); } }, 400);
                }
            }
        } catch (Exception ignored) {}
    }

    // ===================== SuperX =====================
    void post(String key, String pkg, Kind kind, String title, String text, String sub, int progress,
              Bitmap icon, PendingIntent click) {
        Integer id = ids.get(key);
        if (id == null) {
            // каждый раз новый id: если островок смахнуть, vivo на час блокирует уведомление с тем же id
            id = nextId++;
            if (nextId > 900000) nextId = 1000;
            ids.put(key, id);
        }
        if (click == null) {
            Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) click = PendingIntent.getActivity(ctx, id, li, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        if (click == null) click = PendingIntent.getActivity(ctx, id, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Bitmap appIcon = appIcon(pkg);
        if (icon == null) icon = appIcon;
        Icon left = Icon.createWithBitmap(icon != null ? icon : appIcon);
        String appName = label(ctx, pkg);
        String main = !title.isEmpty() ? title : text;
        String second = !title.isEmpty() ? text : sub;

        Bundle x = new Bundle();
        x.putInt("notification.superx.operation", 0);
        x.putParcelable("notification.superx.clickResp", click);
        x.putInt("notification.superx.versionCode", 1);

        Bundle base = new Bundle();
        base.putCharSequence("notification.superx.baseInfos.title", appName);
        base.putCharSequence("notification.superx.baseInfos.content", main);
        if (!second.isEmpty()) base.putCharSequence("notification.superx.baseInfos.subText", second);
        if (appIcon != null) base.putParcelable("notification.superx.baseInfos.icon", Icon.createWithBitmap(appIcon));
        x.putBundle("notification.superx.baseInfos", base);

        Bundle shortInfo = new Bundle();          // обязательный блок: без него vivo отклоняет SuperX (ошибка 2240)
        shortInfo.putString("notification.superx.shortInfos.coreInfoShort", shortText(main));
        shortInfo.putString("notification.superx.shortInfos.describeShort", second.isEmpty() ? appName : second);
        shortInfo.putParcelable("notification.superx.shortInfos.image", left);
        shortInfo.putParcelable("notification.superx.shortInfos.icon", left);
        x.putBundle("notification.superx.shortInfos", shortInfo);

        Bundle infos = new Bundle();
        Bundle island = new Bundle();
        Bundle li = new Bundle(), ri = new Bundle();
        li.putParcelable("island.superx.leftInfo.icon", left);

        switch (kind) {
            case NAVI:
                x.putInt("notification.superx.template", 9);
                x.putString("notification.superx.scene", "NAVIGATION");
                infos.putParcelable("notification.superx.infos.dirIcon", left);
                infos.putString("notification.superx.infos.mainHighlightText", main);
                infos.putString("notification.superx.infos.mainNormalText", second);
                if (!sub.isEmpty()) infos.putString("notification.superx.infos.dirSubText", sub);
                infos.putString("notification.superx.infos.navMsg", main + " " + second);
                island.putInt("island.superx.template", 3);
                island.putInt("island.superx.leftTemplate", 1);      // иконка манёвра
                island.putInt("island.superx.rightTemplate", 4);     // текст
                ri.putCharSequence("island.superx.rightInfo.content", shortText(main));
                break;
            default: {
                // vivo пускает сторонние SuperX только в сцене «навигация» — показываем такси/доставку в её виде
                x.putInt("notification.superx.template", 9);
                x.putString("notification.superx.scene", "NAVIGATION");
                // крупно — только время/расстояние, рядом номер машины, остальное — строкой ниже
                String[] c = compact(main, second, sub, appName);
                infos.putParcelable("notification.superx.infos.dirIcon", left);
                infos.putString("notification.superx.infos.mainHighlightText", c[0]);
                infos.putString("notification.superx.infos.mainNormalText", c[1]);
                if (!c[2].isEmpty()) infos.putString("notification.superx.infos.dirSubText", c[2]);
                infos.putString("notification.superx.infos.navMsg", main + " " + second);
                island.putInt("island.superx.template", 3);
                island.putInt("island.superx.leftTemplate", 1);
                island.putInt("island.superx.rightTemplate", 4);
                String rt = shortText(main);
                if (rt.equals(main) && progress >= 0) rt = progress + "%";
                ri.putCharSequence("island.superx.rightInfo.content", rt);
            }
        }
        x.putBundle("notification.superx.infos", infos);
        island.putBundle("island.superx.leftInfo", li);
        island.putBundle("island.superx.rightInfo", ri);
        island.putParcelable("island.superx.clickResp", click);
        x.putBundle("notification.superx.island", island);

        // капсула в строке состояния (запасной вид для экранов без островка)
        Bundle cap = new Bundle();
        cap.putInt("notification.superx.capsule.state", 1);
        cap.putString("notification.superx.capsule.content", shortText(main));
        cap.putParcelable("notification.superx.capsule.icon", left);
        x.putBundle("notification.superx.capsule", cap);

        Notification.Builder nb = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_island)
                .setContentTitle(main)
                .setContentText(second.isEmpty() ? appName : second)
                .setSubText(appName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(click)
                .setCategory(kind == Kind.NAVI ? Notification.CATEGORY_NAVIGATION : Notification.CATEGORY_PROGRESS)
                .addExtras(x);
        if (icon != null) nb.setLargeIcon(icon);
        if (kind == Kind.PROGRESS && progress >= 0) nb.setProgress(100, progress, false);
        Notification built = nb.build();
        last.put(id, built);
        nm.notify(id, built);
    }

    void cancelAll() { clearAll(); }

    // ===================== тест =====================
    void test(Kind kind) {
        if (kind == Kind.NAVI) {
            post("test-navi", ctx.getPackageName(), Kind.NAVI, "300 м", "Поверните направо на Тверскую", "Прибытие в 18:42 · 12 км",
                    -1, draw(R.drawable.ic_turn_right), null);
        } else if (kind == Kind.INFO) {
            post("test-order", ctx.getPackageName(), Kind.PROGRESS, "В пути 12 мин", "Самокат", "Курьер везёт ваш заказ",
                    70, draw(R.drawable.ic_car), null);
        } else {
            post("test-taxi", ctx.getPackageName(), Kind.PROGRESS, "Водитель будет через 4 мин", "Белый Kia Rio · А123ВС 77", "",
                    65, draw(R.drawable.ic_car), null);
        }
    }

    void cancelTests() {
        for (String k : new String[]{"test-navi", "test-taxi", "test-order"}) remove(k);
    }

    // ===================== иконки =====================
    private Bitmap iconFor(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        try {
            Icon li = n.getLargeIcon();
            if (li != null) { Bitmap b = toBitmap(li.loadDrawable(ctx)); if (b != null) return b; }
        } catch (Exception ignored) {}
        try {
            Icon si = n.getSmallIcon();
            if (si != null) {
                Drawable d = si.loadDrawable(ctx);
                if (d != null) { d.setTint(Color.WHITE); return toBitmap(d); }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static final java.util.regex.Pattern DIST =
            java.util.regex.Pattern.compile("\\d+[.,]?\\d*\\s*(м|км|m|km|ft|mi|yd)(?!\\p{L})", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern TURN = java.util.regex.Pattern.compile(
            "(right|left|straight|u-turn|uturn|roundabout|exit|направо|налево|прямо|разворот|развернит|кольц|съезд|правее|левее)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** Идёт ведение по маршруту: в тексте есть расстояние или манёвр. */
    static boolean guiding(String t) {
        return DIST.matcher(t).find() || TURN.matcher(t).find();
    }

    /** Стрелка по тексту манёвра (Карты присылают только текст). */
    static int maneuver(String t) {
        String s = t.toLowerCase();
        if (s.matches("(?s).*(u-turn|uturn|развернит|разворот).*")) return R.drawable.ic_uturn;
        if (s.matches("(?s).*(left|налево|левее).*")) return R.drawable.ic_turn_left;
        if (s.matches("(?s).*(right|направо|правее).*")) return R.drawable.ic_turn_right;
        if (s.matches("(?s).*(straight|прямо|continue|продолж).*")) return R.drawable.ic_straight;
        if (s.matches("(?s).*(arriv|прибыт|финиш|destination|пункт назнач).*")) return R.drawable.ic_finish;
        return R.drawable.ic_straight;
    }

    private Bitmap appIcon(String pkg) {
        if (appIcons.containsKey(pkg)) return appIcons.get(pkg);
        Bitmap b = null;
        try { b = toBitmap(ctx.getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) {}
        if (b == null) b = draw(R.drawable.ic_island);
        appIcons.put(pkg, b);
        return b;
    }

    private Bitmap draw(int res) {
        return toBitmap(ctx.getDrawable(res));
    }

    static Bitmap toBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        int w = d.getIntrinsicWidth() > 0 ? Math.min(256, d.getIntrinsicWidth()) : 128;
        int h = d.getIntrinsicHeight() > 0 ? Math.min(256, d.getIntrinsicHeight()) : 128;
        if (w < 96) { h = h * 96 / w; w = 96; }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private static boolean isEmpty(CharSequence s) { return s == null || s.toString().trim().isEmpty(); }
    private static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }
    private static String nz(CharSequence s) { return s == null ? "" : s.toString(); }

    /** Для островка нужен короткий текст: «300 м», «4 мин». */
    private static final java.util.regex.Pattern AMOUNT = java.util.regex.Pattern.compile(
            "(\\d+[.,]?\\d*\\s*(?:минут[аы]?|мин|час[аов]{0,2}|ч|сек|км|м|min|mins|h|hr|sec|km|m|mi|%)(?!\\p{L})\\.?|\\b\\d{1,2}:\\d{2}\\b)");
    // госномер: А123ВС 77 / а123вс777 (кириллица и похожая латиница)
    private static final java.util.regex.Pattern PLATE = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])[АВЕКМНОРСТУХABEKMHOPCTYX]\\s?\\d{3}\\s?[АВЕКМНОРСТУХABEKMHOPCTYX]{2}\\s?\\d{2,3}(?!\\d)");
    private static final java.util.regex.Pattern FILLER = java.util.regex.Pattern.compile(
            "(?iu)(?<!\\p{L})(будет|приедет|подъедет|прибудет|доставит|привезёт|привезет|через|осталось|примерно|около|ориентировочно|ещё|еще|arriving|arrives|arrive|will|be|in|about|approx|approximately|is)(?!\\p{L})");

    /** {крупно, рядом, строка ниже} для такси/доставки: на островке помещается ~6 символов крупно и ~12 рядом. */
    static String[] compact(String main, String second, String sub, String appName) {
        String all = (main + " · " + second + " · " + sub);
        java.util.regex.Matcher am = AMOUNT.matcher(main);
        if (!am.find()) { am = AMOUNT.matcher(second); if (!am.find()) am = null; }
        java.util.regex.Matcher pm = PLATE.matcher(all);
        String plate = pm.find() ? pm.group().replaceAll("\\s+", "").toUpperCase()
                .replaceAll("^(\\p{L}\\d{3}\\p{L}{2})(\\d+)$", "$1 $2") : "";
        if (am == null) return new String[]{main, plate.isEmpty() ? (second.isEmpty() ? appName : second) : plate,
                plate.isEmpty() ? sub : join(strip(second, pm), sub)};
        String amount = am.group().trim().replaceAll("\\.$", "").replaceAll("минут[аы]?", "мин").replaceAll("час[аов]{0,2}", "ч");
        // что осталось от фразы без числа и слов-связок: «Водитель будет через 4 мин» → «Водитель»
        String rest = FILLER.matcher(main.replace(am.group(), " ")).replaceAll(" ").replaceAll("\\s+", " ").trim()
                .replaceAll("^[·,:\\-–—\\s]+|[·,:\\-–—\\s]+$", "");
        String sec = !second.contains(am.group()) ? second : FILLER.matcher(second.replace(am.group(), " ")).replaceAll(" ")
                .replaceAll("\\s+", " ").replaceAll("^[·,:\\-–—\\s]+|[·,:\\-–—\\s]+$", "").trim();
        String near, below;
        if (!plate.isEmpty()) {
            near = plate;
            below = join(strip(sec, PLATE.matcher(sec)), rest.isEmpty() ? sub : join(rest, sub));
        } else {
            near = rest.isEmpty() ? appName : rest;
            below = join(sec, sub);
        }
        return new String[]{amount, near, below};
    }

    private static String strip(String s, java.util.regex.Matcher plateMatcher) {
        if (s == null) return "";
        return PLATE.matcher(s).replaceAll("").replaceAll("\\s*[·,]\\s*$|^\\s*[·,]\\s*", "").replaceAll("\\s+", " ").trim();
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a.trim(); b = b == null ? "" : b.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty() || a.contains(b)) return a;
        return a + " · " + b;
    }

    static String shortText(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+[.,]?\\d*\\s*(м|км|мин|ч|сек|%))").matcher(s);
        if (m.find()) return m.group(1);
        return s.length() > 14 ? s.substring(0, 13) + "…" : s;
    }
}
