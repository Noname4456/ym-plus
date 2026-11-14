package ymbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * Точка запуска приложения. Островок и карточка vivo открывают «приложение плеера» через неё.
 * Экран моста показываем, только если нас точно открыли с рабочего стола или из настроек;
 * во всех остальных случаях (островок, карточка, неизвестный источник) — открываем Яндекс Музыку.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent target = null;
        // у «ЯМ Островка» нет музыки — всегда открываем его экран
        if (!BridgeService.islandOnly(this) && !openedByUser()) target = BridgeService.ymLaunchIntent(this);
        if (target == null) target = new Intent(this, MainActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(target); } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean openedByUser() {
        Intent in = getIntent();
        // лаунчеры передают координаты иконки для анимации запуска
        if (in != null && in.getSourceBounds() != null) return true;
        if (in != null && (in.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return true;
        Uri ref = getReferrer();
        String pkg = ref != null ? ref.getHost() : null;
        if (pkg == null) return false;
        if (pkg.equals(getPackageName())) return true;
        if (pkg.contains("settings") || pkg.contains("packageinstaller")) return true;
        return isHome(pkg);
    }

    private boolean isHome(String pkg) {
        try {
            List<ResolveInfo> homes = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            for (ResolveInfo ri : homes) if (pkg.equals(ri.activityInfo.packageName)) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
