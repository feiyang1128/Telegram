package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only the explicit PendingIntent created for our current install session can reach this receiver. */
public final class ForkUpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) ForkUpdater.handleInstallStatus(context, intent);
    }
}
