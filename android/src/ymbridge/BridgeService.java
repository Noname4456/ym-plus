package ymbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Мост: находит медиасессию Яндекс Музыки и повторяет её от имени этого приложения,
 * чтобы системный плеер OriginOS (он пускает только «разрешённые» пакеты) её показал.
 * Нажатия в системном плеере уходят обратно в Яндекс Музыку.
 *
 * Для плеера vivo мост говорит на его «кооперативном» протоколе (com.vivo.musicwidgetmix):
 *  - флаги возможностей и сердечко — в метаданных трека;
 *  - текст песни (LRC) — в extras сессии;
 *  - нажатие ❤ приходит как setRating(heart).
 */
public class BridgeService extends NotificationListenerService {
    static final String[] YM_PACKAGES = {"ru.yandex.music", "com.yandex.music"};
    static final String CHANNEL = "bridge";
    static final int NOTIF_ID = 7;
    static final String ACT_PREV = "ymbridge.PREV", ACT_TOGGLE = "ymbridge.TOGGLE", ACT_NEXT = "ymbridge.NEXT",
            ACT_LIKE = "ymbridge.LIKE";
    static final String PREFS = "bridge", PREF_HIDE = "hide_ym";
    static final long SNOOZE_MS = 20 * 60 * 1000L;

    // протокол vivo
    static final String VIVO_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event";
    static final long VIVO_EVENTS = 1 | 2 | 4 | 8 | 16 | 64;   // управление, инфо, обложка, текст, перемотка, избранное

    // кнопки лайка Яндекса: «Like Not selected [actionLike]» / «Like Selected [actionUndoLike]»
    static final Pattern DISLIKE = Pattern.compile("dislike|^\\s*не\\s+нравится|thumbs?.?down", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern LIKE = Pattern.compile("like|нрав|favou?rite|heart|love", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern LIKED = Pattern.compile("(?<!not )\\bselected|undolike|unlike|убрать|remove", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ---- состояние для экрана приложения ----
    static volatile String status = "Нет доступа к уведомлениям";
    static volatile String nowPlaying = "";
    static volatile String nowArtist = "";
    static volatile Bitmap nowArt = null;
    static volatile boolean playing = false;
    static volatile int likeState = -1;            // -1 Яндекс не отдаёт лайк, 0 нет, 1 лайкнут
    static volatile String lyricsStatus = "";
    static volatile boolean hideFailed = false;
    static volatile String diagActions = "", diagCmd = "", diagConn = "";
    static BridgeService instance;

    private static MediaSession shared;

    /** Одна сессия на всё время работы: её же отдаёт «медиа-каталог» плееру vivo. */
    static synchronized MediaSession session(Context c) {
        if (shared == null) {
            shared = new MediaSession(c.getApplicationContext(), "YandexMusicBridge");
            shared.setRatingType(Rating.RATING_HEART);
        }
        return shared;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService lyricsIo = Executors.newSingleThreadExecutor();
    private MediaSessionManager msm;
    private ComponentName me;
    private NotificationManager nm;
    private MediaController src;
    private MediaSession mine;
    private Bitmap notifArt;
    private String lastNotifKey = "";
    private String lyricsKey = "";
    private boolean wasPlaying = false;
    private long notPlayingSince = 0;
    private int pendingLike = -1;                  // что мы попросили у Яндекса, пока он не подтвердил
    private long pendingLikeAt = 0;

    static boolean isYm(String pkg) {
        for (String p : YM_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }

    static String stamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss ", java.util.Locale.US).format(new java.util.Date());
    }

    // ======================= подключение к Яндекс Музыке =======================
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { pick(list); }
            };

    private final MediaController.Callback srcCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { pushMetadata(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { pushState(); }
        @Override public void onSessionDestroyed() { detach(); rescan(); }
    };

    // Команды из системного плеера → в Яндекс Музыку
    private final MediaSession.Callback forward = new MediaSession.Callback() {
        @Override public void onPlay() { if (src != null) src.getTransportControls().play(); }
        @Override public void onPause() { if (src != null) src.getTransportControls().pause(); }
        @Override public void onStop() { if (src != null) src.getTransportControls().pause(); }
        @Override public void onSkipToNext() { if (src != null) src.getTransportControls().skipToNext(); }
        @Override public void onSkipToPrevious() { if (src != null) src.getTransportControls().skipToPrevious(); }
        @Override public void onSeekTo(long pos) { if (src != null) src.getTransportControls().seekTo(pos); }
        @Override public void onFastForward() { if (src != null) src.getTransportControls().fastForward(); }
        @Override public void onRewind() { if (src != null) src.getTransportControls().rewind(); }
        @Override public void onSetRating(Rating r) {
            diagCmd = stamp() + "❤ из системного плеера";
            if (r == null) return;
            if (r.getRatingStyle() == Rating.RATING_HEART) setLiked(r.isRated() ? r.hasHeart() : likeState != 1);
            else if (r.getRatingStyle() == Rating.RATING_THUMB_UP_DOWN && r.isRated()) setLiked(r.isThumbUp());
            else setLiked(likeState != 1);
        }
        @Override public void onCustomAction(String action, Bundle extras) {
            diagCmd = stamp() + "кнопка " + action;
            if (src != null && action != null) src.getTransportControls().sendCustomAction(action, extras);
        }
    };

    @Override
    public void onListenerConnected() {
        instance = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Яндекс Музыка в системном плеере",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        me = new ComponentName(this, BridgeService.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            status = "Жду Яндекс Музыку — включите трек";
            rescan();
        } catch (SecurityException e) {
            status = "Нет доступа к уведомлениям";
        }
    }

    @Override
    public void onListenerDisconnected() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        detach();
        instance = null;
        status = "Служба отключена системой — переподключаюсь";
        try { requestRebind(new ComponentName(this, BridgeService.class)); } catch (Exception ignored) {}
    }

    private void rescan() {
        try { pick(msm.getActiveSessions(me)); } catch (Exception ignored) {}
    }

    private void pick(List<MediaController> list) {
        MediaController found = null;
        if (list != null) for (MediaController c : list) if (isYm(c.getPackageName())) { found = c; break; }
        if (found == null) {
            if (src != null) { detach(); status = "Жду Яндекс Музыку — включите трек"; }
            return;
        }
        if (src == null || !src.getSessionToken().equals(found.getSessionToken())) attach(found);
    }

    private void attach(MediaController c) {
        detach();
        src = c;
        src.registerCallback(srcCallback, handler);
        mine = session(this);
        mine.setCallback(forward, handler);
        PendingIntent pi = c.getSessionActivity();
        if (pi == null) pi = launchYm();
        if (pi != null) mine.setSessionActivity(pi);
        mine.setActive(true);
        status = "Работает";
        wasPlaying = false;
        notPlayingSince = 0;
        pushMetadata();
        pushState();
        hideYmNow();
    }

    private void detach() {
        if (src != null) { try { src.unregisterCallback(srcCallback); } catch (Exception ignored) {} }
        src = null;
        if (mine != null) { try { mine.setActive(false); } catch (Exception ignored) {} }
        mine = null;
        nowPlaying = ""; nowArtist = ""; nowArt = null; playing = false;
        lastNotifKey = ""; lyricsKey = ""; lyricsStatus = "";
        likeState = -1; pendingLike = -1;
        diagActions = "";
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    PendingIntent launchYm() {
        Intent i = ymLaunchIntent(this);
        return i == null ? null : PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Intent ymLaunchIntent(Context c) {
        for (String p : YM_PACKAGES) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    private String trackKey() {
        MediaMetadata m = src != null ? src.getMetadata() : null;
        if (m == null) return "";
        return m.getString(MediaMetadata.METADATA_KEY_ARTIST) + "|" + m.getString(MediaMetadata.METADATA_KEY_TITLE);
    }

    private String mediaId(MediaMetadata m) {
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        return id != null && !id.isEmpty() ? id : "ym:" + Integer.toHexString(trackKey().hashCode());
    }

    private static boolean hasArt(MediaMetadata m) {
        return m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                || m.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
                || m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null;
    }

    // ======================= метаданные и состояние =======================
    private void pushMetadata() {
        if (src == null || mine == null) return;
        MediaMetadata m = src.getMetadata();
        if (m == null) return;
        String key = trackKey();
        if (!key.equals(lyricsKey)) { pendingLike = -1; }
        updateLike();
        MediaMetadata.Builder b = new MediaMetadata.Builder(m);
        if (!hasArt(m) && notifArt != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, notifArt);
        Rating heart = Rating.newHeartRating(likeState == 1);
        b.putRating(MediaMetadata.METADATA_KEY_RATING, heart);
        b.putRating(MediaMetadata.METADATA_KEY_USER_RATING, heart);
        b.putLong(VIVO_SUPPORT_EVENT, VIVO_EVENTS);
        b.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId(m));
        mine.setMetadata(b.build());

        nowPlaying = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        nowArtist = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        nowArt = art != null ? art : notifArt;
        postNotification();
        requestLyrics(m);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void pushState() {
        if (src == null || mine == null) return;
        PlaybackState s = src.getPlaybackState();
        if (s == null) return;
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setState(s.getState(), s.getPosition(), s.getPlaybackSpeed(), s.getLastPositionUpdateTime())
                .setBufferedPosition(s.getBufferedPosition())
                .setActiveQueueItemId(s.getActiveQueueItemId())
                .setActions(s.getActions() | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_SET_RATING);
        if (s.getErrorMessage() != null) b.setErrorMessage(s.getErrorMessage());
        if (s.getExtras() != null) b.setExtras(s.getExtras());
        StringBuilder diag = new StringBuilder();
        if (s.getCustomActions() != null) for (PlaybackState.CustomAction ca : s.getCustomActions()) {
            diag.append(diag.length() > 0 ? ", " : "").append(ca.getName()).append(" [").append(ca.getAction()).append("]");
            if (!ca.getAction().startsWith("stub")) b.addCustomAction(ca);   // «заглушки» Яндекса при переключении не нужны
        }
        diagActions = diag.toString();
        mine.setPlaybackState(b.build());

        int old = likeState;
        updateLike();
        if (old != likeState) { lastNotifKey = ""; pushMetadata(); }

        boolean now = isPlaying();
        playing = now;
        long t = SystemClock.elapsedRealtime();
        if (now && !wasPlaying && (notPlayingSince == 0 || t - notPlayingSince > 3000)) announce();
        if (!now && wasPlaying) notPlayingSince = t;
        wasPlaying = now;
        postNotification();
    }

    /**
     * Музыка только что началась: «переобъявляем» сессию, чтобы плеер vivo
     * переключился на неё и показал островок.
     */
    private void announce() {
        if (mine == null) return;
        try { mine.setActive(false); } catch (Exception ignored) {}
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mine != null && src != null) {
                    mine.setActive(true);
                    pushState();
                    resendLyrics(300);
                }
            }
        }, 120);
    }

    private boolean isPlaying() {
        PlaybackState s = src != null ? src.getPlaybackState() : null;
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING
                || s.getState() == PlaybackState.STATE_CONNECTING);
    }

    // ======================= лайк =======================
    private PlaybackState.CustomAction findLikeAction() {
        PlaybackState s = src != null ? src.getPlaybackState() : null;
        if (s == null || s.getCustomActions() == null) return null;
        for (PlaybackState.CustomAction ca : s.getCustomActions()) {
            String name = String.valueOf(ca.getName());
            if (DISLIKE.matcher(name).find() || DISLIKE.matcher(ca.getAction()).find()) continue;
            if (LIKE.matcher(ca.getAction() + " " + name).find()) return ca;
        }
        return null;
    }

    private void updateLike() {
        int ym = -1;
        PlaybackState.CustomAction ca = findLikeAction();
        if (ca != null) ym = LIKED.matcher(ca.getAction() + " " + ca.getName()).find() ? 1 : 0;
        else if (src != null && src.getRatingType() == Rating.RATING_HEART) {
            MediaMetadata m = src.getMetadata();
            Rating r = m != null ? m.getRating(MediaMetadata.METADATA_KEY_USER_RATING) : null;
            ym = r != null && r.isRated() && r.hasHeart() ? 1 : 0;
        }
        // пока Яндекс не подтвердил нажатие — показываем то, что попросили (сердечко красится сразу)
        if (pendingLike >= 0) {
            if (ym == pendingLike || SystemClock.elapsedRealtime() - pendingLikeAt > 5000) pendingLike = -1;
            else ym = pendingLike;
        }
        likeState = ym;
    }

    boolean like() { return setLiked(likeState != 1); }

    boolean setLiked(boolean want) {
        if (src == null) return false;
        updateLike();
        if (likeState < 0) return false;
        if ((likeState == 1) != want) {
            PlaybackState.CustomAction ca = findLikeAction();
            if (ca != null) src.getTransportControls().sendCustomAction(ca.getAction(), ca.getExtras());
            else src.getTransportControls().setRating(Rating.newHeartRating(want));
            pendingLike = want ? 1 : 0;
            pendingLikeAt = SystemClock.elapsedRealtime();
        }
        likeState = want ? 1 : 0;
        lastNotifKey = "";
        pushMetadata();
        return true;
    }

    // ======================= текст песни =======================
    private void requestLyrics(final MediaMetadata m) {
        final String key = trackKey();
        if (key.isEmpty() || key.equals(lyricsKey)) return;
        lyricsKey = key;
        lyricsStatus = "ищу…";
        final String title = m.getString(MediaMetadata.METADATA_KEY_TITLE), artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                mid = mediaId(m);
        final long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        sendLyrics(mid, "");                      // старый текст убираем сразу
        lyricsIo.execute(new Runnable() {
            @Override public void run() {
                final Lyrics.Result r = Lyrics.find(BridgeService.this, key, artist, title, dur);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!key.equals(trackKey())) return;
                        lyricsStatus = r != null ? "есть · " + r.source : "не найден";
                        sendLyrics(mid, r != null ? r.lrc : "");
                    }
                });
            }
        });
    }

    private void sendLyrics(String mid, String lrc) {
        if (mine == null) return;
        Bundle e = new Bundle();
        e.putString("vivomusicmix.meida.extra.key.action", "vivomusicmix.extra.lrc_change");   // опечатки — как у vivo
        e.putString("vivomusicmix.extra.key.meidia_id", mid);
        e.putString("vivomusicmix.extra.key.lyric", lrc);
        mine.setExtras(e);
    }

    /** Повторить текст текущего трека (например, плеер vivo только что подключился). */
    void resendLyrics(long delay) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (src == null || mine == null || src.getMetadata() == null) return;
                String key = trackKey();
                if (!Lyrics.cached(key)) return;
                Lyrics.Result r = Lyrics.get(key);
                sendLyrics(mediaId(src.getMetadata()), r != null ? r.lrc : "");
            }
        }, delay);
    }

    // ======================= уведомление =======================
    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, ActionReceiver.class).setAction(act);
        return PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void command(String act) {
        if (src == null) return;
        MediaController.TransportControls tc = src.getTransportControls();
        if (ACT_PREV.equals(act)) tc.skipToPrevious();
        else if (ACT_NEXT.equals(act)) tc.skipToNext();
        else if (ACT_TOGGLE.equals(act)) { if (isPlaying()) tc.pause(); else tc.play(); }
        else if (ACT_LIKE.equals(act)) like();
    }

    private void postNotification() {
        if (src == null || mine == null || nm == null) return;
        boolean pl = isPlaying();
        Bitmap art = nowArt;
        String key = nowPlaying + "|" + nowArtist + "|" + pl + "|" + likeState + "|" + (art != null ? art.getGenerationId() : 0);
        if (key.equals(lastNotifKey)) return;
        lastNotifKey = key;

        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(nowPlaying.isEmpty() ? "Яндекс Музыка" : nowPlaying)
                .setContentText(nowArtist)
                .setSubText("Яндекс Музыка")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(pl)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_prev), "Назад", action(ACT_PREV, 11)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, pl ? R.drawable.ic_pause : R.drawable.ic_play),
                        pl ? "Пауза" : "Играть", action(ACT_TOGGLE, 12)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next), "Вперёд", action(ACT_NEXT, 13)).build());
        if (likeState >= 0) nb.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, likeState == 1 ? R.drawable.ic_heart_on : R.drawable.ic_heart_off),
                likeState == 1 ? "В любимом" : "В любимое", action(ACT_LIKE, 14)).build());
        nb.setStyle(new Notification.MediaStyle().setMediaSession(mine.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (art != null) nb.setLargeIcon(art);
        PendingIntent pi = src.getSessionActivity();
        if (pi == null) pi = launchYm();
        if (pi != null) nb.setContentIntent(pi);
        try { nm.notify(NOTIF_ID, nb.build()); } catch (Exception ignored) {}
    }

    // ======================= уведомление Яндекса =======================
    static boolean hideEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_HIDE, true);
    }

    private static boolean isMediaNotification(Notification n) {
        if (n == null) return false;
        if (n.extras != null && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true;
        String tpl = n.extras != null ? n.extras.getString(Notification.EXTRA_TEMPLATE) : null;
        return (tpl != null && tpl.contains("MediaStyle")) || Notification.CATEGORY_TRANSPORT.equals(n.category);
    }

    private void hideYm(final StatusBarNotification sbn) {
        if (!hideEnabled(this) || mine == null) return;
        try { snoozeNotification(sbn.getKey(), SNOOZE_MS); } catch (Exception e) { hideFailed = true; return; }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                boolean still = false;
                try {
                    StatusBarNotification[] act = getActiveNotifications();
                    if (act != null) for (StatusBarNotification a : act) if (a.getKey().equals(sbn.getKey())) still = true;
                } catch (Exception ignored) {}
                hideFailed = still;
            }
        }, 700);
    }

    void hideYmNow() {
        try {
            StatusBarNotification[] act = getActiveNotifications();
            if (act != null) for (StatusBarNotification a : act)
                if (isYm(a.getPackageName()) && isMediaNotification(a.getNotification())) hideYm(a);
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isYm(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (src == null) rescan();
        if (isMediaNotification(n)) hideYm(sbn);
        Icon ic = n != null ? n.getLargeIcon() : null;
        if (ic == null) return;
        try {
            Bitmap bmp = toBitmap(ic.loadDrawable(this));
            if (bmp != null) {
                notifArt = bmp;
                MediaMetadata m = src != null ? src.getMetadata() : null;
                if (m != null && !hasArt(m)) { lastNotifKey = ""; pushMetadata(); }
            }
        } catch (Exception ignored) {}
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        int w = Math.max(1, Math.min(512, d.getIntrinsicWidth())), h = Math.max(1, Math.min(512, d.getIntrinsicHeight()));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }
}
