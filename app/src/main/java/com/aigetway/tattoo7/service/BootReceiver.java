package com.aigetway.tattoo7.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.aigetway.tattoo7.core.App;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!App.get().settings().bootOnStart()) return;
        Intent svc = new Intent(context, GatewayService.class);
        svc.setAction(GatewayService.ACTION_START);
        context.startForegroundService(svc);
    }
}