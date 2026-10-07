package com.anubisproductions.datagate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Restores blocking after a reboot, and after the app itself is updated.
 *
 * Without this the engine stops silently on restart and every restricted app quietly
 * goes back online - the user is not told, and would only notice by seeing an ad.
 *
 * VPN consent survives a reboot, so prepare() normally returns null here. If the user
 * revoked it, or another VPN app took over, we log and stay off rather than pestering
 * them with an Activity at boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED matters as much as boot: an update kills the process and
        // the tunnel with it, and the user is never told. It carries no extras and is
        // delivered only to the app being replaced, so it needs no further guarding.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val rules = Rules.blockedAny(context)
        if (rules.isEmpty()) {
            Log.i(AttemptLog.TAG, "${intent.action} no rules; staying off")
            return
        }

        if (VpnService.prepare(context) != null) {
            Log.w(AttemptLog.TAG, "${intent.action} consent missing; stays off until opened")
            return
        }

        Log.i(AttemptLog.TAG, "${intent.action} restoring ${rules.size} rule(s)")
        BlockVpnService.start(context)
    }
}
