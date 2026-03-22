package com.os.touchrejectionv2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TouchRejectService service = TouchRejectService.getInstance();
        if (service != null) {
            service.shutdown();
        }
    }
}
