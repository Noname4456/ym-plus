package ymbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Кнопки уведомления моста. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BridgeService s = BridgeService.instance;
        if (s != null && intent != null) s.command(intent.getAction());
    }
}
