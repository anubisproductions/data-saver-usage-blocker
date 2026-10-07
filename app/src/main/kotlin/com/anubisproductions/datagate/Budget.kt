package com.anubisproductions.datagate

import android.content.Context
import android.os.SystemClock

/**
 * The bundle, the cycle, and the savings ledger.
 *
 * The savings figure is the one thing that proves the app did anything. Every competitor
 * shows what an app *used*; none shows what blocking it *saved*, because none of them
 * records the before. We record the app's own recent daily average at the moment it is
 * blocked, and compare.
 */
object Budget {

    private const val PREFS = "datagate_budget"
    private const val KEY_BUNDLE_MB = "bundle_mb"
    private const val KEY_RESET_DAY = "reset_day"
    private const val PREFIX_BLOCKED_AT = "blocked_at_"
    private const val PREFIX_DAILY_AVG = "daily_avg_"
    private const val PREFIX_ENFORCED_MS = "enforced_ms_"
    private const val PREFIX_SEGMENT_START = "segment_start_"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Monthly mobile allowance in MB. 0 means "not set", and the UI hides the gauge. */
    fun bundleMb(ctx: Context): Int = prefs(ctx).getInt(KEY_BUNDLE_MB, 0)

    fun setBundleMb(ctx: Context, mb: Int) = prefs(ctx).edit().putInt(KEY_BUNDLE_MB, mb).apply()

    /** Day of the month the bundle renews. */
    fun resetDay(ctx: Context): Int = prefs(ctx).getInt(KEY_RESET_DAY, 1)

    fun setResetDay(ctx: Context, day: Int) =
        prefs(ctx).edit().putInt(KEY_RESET_DAY, day.coerceIn(1, 28)).apply()

    /**
     * Records the baseline at the moment an app is restricted: when, and what it had been
     * spending per day. Without this there is nothing to compare against later.
     */
    fun recordBlock(ctx: Context, pkg: String, bytesPerDayBefore: Long, engineUp: Boolean) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putLong(PREFIX_BLOCKED_AT + pkg, now)
            .putLong(PREFIX_DAILY_AVG + pkg, bytesPerDayBefore)
            .putLong(PREFIX_ENFORCED_MS + pkg, 0L)
            .putLong(PREFIX_SEGMENT_START + pkg, if (engineUp) now else 0L)
            .apply()
    }

    /**
     * Opens an enforcement window for each package the tunnel is now actually carrying.
     *
     * Savings are credited per millisecond of enforcement, not per millisecond of wall
     * clock. A rule can sit in SharedPreferences for days with the engine down - consent
     * refused, another VPN holding the slot, the user simply turning it off - and crediting
     * that time invented savings the app never made.
     */
    fun engineStarted(ctx: Context, pkgs: Collection<String>) {
        reconcile(ctx, pkgs)
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val e = p.edit()
        for (pkg in pkgs) {
            if (p.getLong(PREFIX_SEGMENT_START + pkg, 0L) == 0L) {
                e.putLong(PREFIX_SEGMENT_START + pkg, now)
            }
        }
        e.apply()
    }

    /** Closes the open enforcement window and banks it. */
    fun engineStopped(ctx: Context, pkgs: Collection<String>) {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val e = p.edit()
        for (pkg in pkgs) {
            val start = p.getLong(PREFIX_SEGMENT_START + pkg, 0L)
            if (start != 0L) {
                e.putLong(PREFIX_ENFORCED_MS + pkg,
                    p.getLong(PREFIX_ENFORCED_MS + pkg, 0L) + (now - start))
                e.putLong(PREFIX_SEGMENT_START + pkg, 0L)
            }
        }
        e.apply()
    }

    /**
     * Wall-clock time the device last booted.
     *
     * `elapsedRealtime` counts from boot and keeps counting in sleep, so subtracting it from
     * the current time gives the moment the device came up. Cheap, and it needs no permission.
     */
    private fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Close any enforcement window left open by a process that died without saying so.
     *
     * [engineStopped] banks the open window, but it only runs if something calls it. A reboot
     * does not: the process is killed outright, the window stays open, and every later read of
     * [enforcedMs] adds the whole time since - including hours when the phone was off and
     * nothing was being enforced at all. The savings figure then climbs on its own, which is
     * `KNOWN_ISSUES` #5, and the same open window is why the figure looked wrong across a
     * reboot in `FINDINGS.md` F8.
     *
     * The window is banked up to the **boot** rather than to now, because that is the last
     * moment enforcement was genuinely happening. Anything after it belongs to a new window,
     * opened only when the engine actually comes back.
     *
     * Safe to call repeatedly: a window opened since the last boot is left alone.
     */
    fun reconcile(ctx: Context, pkgs: Collection<String>) {
        val boot = bootTime()
        val p = prefs(ctx)
        val e = p.edit()
        var changed = false
        for (pkg in pkgs) {
            val start = p.getLong(PREFIX_SEGMENT_START + pkg, 0L)
            if (start != 0L && start < boot) {
                e.putLong(
                    PREFIX_ENFORCED_MS + pkg,
                    p.getLong(PREFIX_ENFORCED_MS + pkg, 0L) + (boot - start),
                )
                e.putLong(PREFIX_SEGMENT_START + pkg, 0L)
                changed = true
            }
        }
        if (changed) e.apply()
    }

    /** Banked enforcement time plus whatever the currently open window has accrued. */
    fun enforcedMs(ctx: Context, pkg: String): Long {
        val p = prefs(ctx)
        val banked = p.getLong(PREFIX_ENFORCED_MS + pkg, 0L)
        val start = p.getLong(PREFIX_SEGMENT_START + pkg, 0L)
        return if (start == 0L) banked else banked + (System.currentTimeMillis() - start)
    }

    fun clearBlock(ctx: Context, pkg: String) {
        prefs(ctx).edit()
            .remove(PREFIX_BLOCKED_AT + pkg)
            .remove(PREFIX_DAILY_AVG + pkg)
            .remove(PREFIX_ENFORCED_MS + pkg)
            .remove(PREFIX_SEGMENT_START + pkg)
            .apply()
    }

    fun blockedAt(ctx: Context, pkg: String): Long = prefs(ctx).getLong(PREFIX_BLOCKED_AT + pkg, 0L)

    private fun dailyAvgBefore(ctx: Context, pkg: String): Long =
        prefs(ctx).getLong(PREFIX_DAILY_AVG + pkg, 0L)

    /**
     * Estimated bytes not spent because [pkg] has been restricted.
     *
     * Deliberately an estimate and labelled as one in the UI: it assumes the app would have
     * carried on at its prior daily rate. [actualSince] is subtracted so an app that still
     * leaks some traffic is not credited with saving it.
     *
     * The elapsed term is enforcement time, not wall clock. Using wall clock meant a rule
     * left in place with the engine switched off kept earning savings the app never made,
     * which is the one number here a user could catch us being wrong about.
     */
    fun estimatedSaved(ctx: Context, pkg: String, actualSince: Long): Long {
        if (blockedAt(ctx, pkg) == 0L) return 0L
        val avg = dailyAvgBefore(ctx, pkg)
        if (avg == 0L) return 0L
        val days = enforcedMs(ctx, pkg).toDouble() / 86_400_000.0
        if (days <= 0) return 0L
        return ((avg * days).toLong() - actualSince).coerceAtLeast(0L)
    }

    /**
     * Estimated saving for one app, measuring its actual usage since it was restricted.
     *
     * Callers used to pass the app's cycle total here, which made the subtraction
     * meaningless and pinned every result to zero.
     */
    fun estimatedSavedFor(ctx: Context, pkg: String): Long {
        val since = blockedAt(ctx, pkg)
        if (since == 0L) return 0L
        return estimatedSaved(ctx, pkg, UsageRepository.bytesSince(ctx, pkg, since))
    }

    fun totalEstimatedSaved(ctx: Context, blocked: Set<String>): Long =
        blocked.sumOf { estimatedSavedFor(ctx, it) }
}
