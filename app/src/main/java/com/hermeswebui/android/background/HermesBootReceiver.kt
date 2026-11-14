package com.hermeswebui.android.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermeswebui.android.webapp.WebappGatewayCoordinator

/**
 * Resumes background monitoring after a reboot or an app update, which both end the process without
 * a restart. Android allows a foreground-service start from these two broadcasts.
 */
class HermesBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (WebappGatewayCoordinator.wantsHeadlessStart(context)) HermesKeepAliveService.start(context)
    }
}
