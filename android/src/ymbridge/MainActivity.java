package ymbridge;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Экран моста: что сейчас играет, настройка в два шага, параметры. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(14, 14, 16), CARD = Color.rgb(28, 28, 32), CARD2 = Color.rgb(40, 40, 46),
            TEXT = Color.WHITE, DIM = Color.argb(150, 255, 255, 255), FAINT = Color.argb(90, 255, 255, 255),
            YELLOW = Color.rgb(255, 219, 77), GREEN = Color.rgb(74, 222, 128), RED = Color.rgb(255, 92, 120);

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 700); }
    };

    private ImageView cover;
    private TextView title, artist, statusChip, lyricsChip, heart, diag, ymNotifHint;
    private LinearLayout setupCard;
    private View stepAccess, stepBattery;
    private boolean diagOpen = false;

    // ---------------- маленькие помощники ----------------
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(r));
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(2), 1f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(round(CARD, 22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 11, FAINT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(s, 12, TEXT, true);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(round(CARD2, 99));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private View row(String titleS, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        r.setBackground(round(CARD2, 16));
        r.addView(text(titleS, 15, TEXT, true));
        if (sub != null) {
            TextView s = text(sub, 12, DIM, false);
            s.setPadding(0, dp(4), 0, 0);
            r.addView(s);
        }
        r.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, "Не получилось открыть настройки", Toast.LENGTH_SHORT).show(); }
    }

    private Intent appSettings(String pkg) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + pkg));
    }

    // ---------------- экран ----------------
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(28));

        // шапка
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setBackground(round(YELLOW, 14));
        logo.setPadding(dp(9), dp(9), dp(9), dp(9));
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(12), 0, 0, 0);
        ht.addView(text("ЯМ Мост", 24, TEXT, true));
        TextView hs = text("Яндекс Музыка в системном плеере vivo", 13, DIM, false);
        hs.setPadding(0, dp(4), 0, 0);
        ht.addView(hs);
        head.addView(ht);
        root.addView(head);

        // сейчас играет
        LinearLayout now = card();
        now.addView(label("Сейчас играет"));
        LinearLayout nr = new LinearLayout(this);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(round(CARD2, 14));
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        cover.setClipToOutline(true);
        nr.addView(cover, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout nt = new LinearLayout(this);
        nt.setOrientation(LinearLayout.VERTICAL);
        nt.setPadding(dp(14), 0, dp(8), 0);
        title = text("", 17, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist = text("", 14, DIM, false);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(5), 0, 0);
        nt.addView(title);
        nt.addView(artist);
        nr.addView(nt, new LinearLayout.LayoutParams(0, -2, 1f));
        heart = text("♡", 24, TEXT, false);
        heart.setGravity(Gravity.CENTER);
        heart.setBackground(round(CARD2, 99));
        heart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BridgeService s = BridgeService.instance;
                if (s == null || !s.like())
                    Toast.makeText(MainActivity.this, "Сейчас ничего не играет", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        nr.addView(heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
        now.addView(nr);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(14), 0, 0);
        statusChip = chip("");
        lyricsChip = chip("");
        chips.addView(statusChip);
        chips.addView(lyricsChip);
        now.addView(chips);
        now.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = BridgeService.ymLaunchIntent(MainActivity.this);
                if (i != null) open(i);
            }
        });
        root.addView(now);

        // настройка
        setupCard = card();
        setupCard.addView(label("Настройка — два шага"));
        stepAccess = row("1. Доступ к уведомлениям", "Через него мост видит, что играет в Яндекс Музыке", new View.OnClickListener() {
            @Override public void onClick(View v) { open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        });
        stepBattery = row("2. Работа в фоне", "Чтобы vivo не выгружал мост", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + getPackageName())));
                } catch (Exception e) { open(appSettings(getPackageName())); }
            }
        });
        setupCard.addView(stepAccess);
        setupCard.addView(stepBattery);
        root.addView(setupCard);

        // параметры
        LinearLayout opts = card();
        opts.addView(label("Параметры"));
        Switch hide = new Switch(this);
        hide.setText("Прятать уведомление Яндекс Музыки");
        hide.setTextColor(TEXT);
        hide.setTextSize(15);
        hide.setChecked(BridgeService.hideEnabled(this));
        hide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit().putBoolean(BridgeService.PREF_HIDE, on).apply();
                BridgeService s = BridgeService.instance;
                if (on && s != null) s.hideYmNow();
            }
        });
        opts.addView(hide);
        ymNotifHint = text("", 12, DIM, false);
        ymNotifHint.setPadding(0, dp(6), 0, 0);
        opts.addView(ymNotifHint);
        opts.addView(row("Уведомления Яндекс Музыки", "Если её плеер всё равно виден в шторке — выключите там уведомление о воспроизведении",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        for (String p : BridgeService.YM_PACKAGES) {
                            if (getPackageManager().getLaunchIntentForPackage(p) == null) continue;
                            try {
                                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, p));
                            } catch (Exception e) { open(appSettings(p)); }
                            return;
                        }
                    }
                }));
        opts.addView(row("Автозапуск и фон", "Настройки приложения: разрешите автозапуск и работу в фоне",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { open(appSettings(getPackageName())); }
                }));
        root.addView(opts);

        // подсказки
        TextView tips = text("Закрепите мост в недавних приложениях (потяните карточку вниз), чтобы vivo его не закрывал. "
                + "Если плеер пропал из пункта управления — просто откройте это приложение.\n\n"
                + "Тексты песен: LRCLIB, Musixmatch, NetEase.", 12, FAINT, false);
        tips.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(tips);

        // диагностика
        final TextView diagToggle = text("Диагностика ▸", 12, FAINT, true);
        diagToggle.setPadding(dp(4), dp(18), dp(4), dp(6));
        diag = text("", 11, FAINT, false);
        diag.setPadding(dp(4), 0, dp(4), 0);
        diag.setTextIsSelectable(true);
        diag.setVisibility(View.GONE);
        diagToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                diagOpen = !diagOpen;
                diag.setVisibility(diagOpen ? View.VISIBLE : View.GONE);
                diagToggle.setText(diagOpen ? "Диагностика ▾" : "Диагностика ▸");
            }
        });
        root.addView(diagToggle);
        root.addView(diag);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(root, new FrameLayout.LayoutParams(-1, -2));
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, BridgeService.class).flattenToString());
    }

    private void setStep(View step, boolean done) {
        step.setAlpha(done ? 0.45f : 1f);
        TextView t = (TextView) ((LinearLayout) step).getChildAt(0);
        String s = t.getText().toString().replaceFirst("^(✓ |\\d\\. )", "");
        int n = step == stepAccess ? 1 : 2;
        t.setText(done ? "✓ " + s : n + ". " + s);
    }

    private void refresh() {
        boolean access = listenerEnabled();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        setStep(stepAccess, access);
        setStep(stepBattery, battery);
        setupCard.setVisibility(access && battery ? View.GONE : View.VISIBLE);

        BridgeService s = BridgeService.instance;
        String st;
        int dot;
        if (!access) { st = "Нужен доступ к уведомлениям"; dot = RED; }
        else if (s == null) {
            st = "Подключаюсь…"; dot = YELLOW;
            try { NotificationListenerService.requestRebind(new ComponentName(this, BridgeService.class)); } catch (Exception ignored) {}
        } else if (BridgeService.nowPlaying.isEmpty()) { st = "Жду Яндекс Музыку"; dot = YELLOW; }
        else { st = BridgeService.playing ? "В системном плеере" : "На паузе"; dot = GREEN; }
        statusChip.setText("● " + st);
        statusChip.setTextColor(dot);

        boolean has = !BridgeService.nowPlaying.isEmpty();
        title.setText(has ? BridgeService.nowPlaying : "Ничего не играет");
        artist.setText(has ? BridgeService.nowArtist : "Включите трек в Яндекс Музыке");
        if (BridgeService.nowArt != null) cover.setImageBitmap(BridgeService.nowArt);
        else cover.setImageResource(R.drawable.ic_logo_dim);
        String ly = BridgeService.lyricsStatus;
        lyricsChip.setText("Текст: " + (ly.isEmpty() ? "—" : ly));
        lyricsChip.setVisibility(has ? View.VISIBLE : View.GONE);

        int ls = BridgeService.likeState;
        heart.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        heart.setText(ls == 1 ? "♥" : "♡");
        heart.setTextColor(ls == 1 ? RED : TEXT);
        heart.setAlpha(ls < 0 ? 0.35f : 1f);

        ymNotifHint.setText(BridgeService.hideFailed ? "⚠ Система не дала спрятать — выключите уведомление вручную (ниже)" : "");
        ymNotifHint.setVisibility(BridgeService.hideFailed ? View.VISIBLE : View.GONE);

        diag.setText("Служба: " + (s != null ? BridgeService.status : "не запущена")
                + "\nКнопки Яндекса: " + (BridgeService.diagActions.isEmpty() ? "—" : BridgeService.diagActions)
                + "\nПоследняя команда из плеера: " + (BridgeService.diagCmd.isEmpty() ? "—" : BridgeService.diagCmd)
                + "\nПлеер vivo подключался: " + (BridgeService.diagConn.isEmpty() ? "—" : BridgeService.diagConn)
                + "\nВерсия: " + versionName());
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
