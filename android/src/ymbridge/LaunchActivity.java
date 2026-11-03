package ymbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Точка запуска приложения. Островок и карточка vivo открывают «приложение плеера» через неё,
 * поэтому, если нас позвала система, сразу открываем Яндекс Музыку.
 * С рабочего стола (и из настроек) — открывается экран моста.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent target = null;
        if (fromSystemPlayer()) target = BridgeService.ymLaunchIntent(this);
        if (target == null) target = new Intent(this, MainActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(target); } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean fromSystemPlayer() {
        Uri ref = getReferrer();
        String pkg = ref != null ? ref.getHost() : null;
        if (pkg == null || pkg.equals(getPackageName())) return false;
        return pkg.contains("musicwidget") || pkg.contains("musicmix") || pkg.contains("systemui")
                || pkg.contains("island") || pkg.equals("android");
    }
}
