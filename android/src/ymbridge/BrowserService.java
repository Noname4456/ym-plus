package ymbridge;

import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.service.media.MediaBrowserService;

import java.util.ArrayList;
import java.util.List;

/**
 * «Медиа-каталог». Через него плеер vivo подключается к сессии приложения.
 * Метка com.vivo.musicwidgetmix.support.service в манифесте включает «кооперативный» протокол vivo.
 */
public class BrowserService extends MediaBrowserService {
    @Override
    public void onCreate() {
        super.onCreate();
        setSessionToken(BridgeService.session(this).getSessionToken());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        BridgeService.diagConn = BridgeService.stamp() + clientPackageName;
        BridgeService s = BridgeService.instance;
        if (s != null) s.resendLyrics(1500);
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
    }
}
