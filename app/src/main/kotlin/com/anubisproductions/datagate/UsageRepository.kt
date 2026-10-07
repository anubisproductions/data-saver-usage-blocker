package com.anubisproductions.datagate

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import androidx.core.text.BidiFormatter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.Calendar

/**
 * Per-app data accounting.
 *
 * The foreground/background split is the point. Foreground bytes are the user's own doing -
 * they were looking at the screen. Background bytes are what an app spent without being
 * asked, and that is the only number a person can actually act on. Samsung Max was built
 * around this distinction and nothing in the current firewall category surfaces it.
 */
data class AppUsage(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val mobileFg: Long,
    val mobileBg: Long,
    val wifiFg: Long,
    val wifiBg: Long,
    val isProtected: Boolean,
) {
    val mobile get() = mobileFg + mobileBg
    val wifi get() = wifiFg + wifiBg
    val total get() = mobile + wifi
    val background get() = mobileBg + wifiBg

    /** Share of this app's traffic that it spent on its own, 0..1. */
    val backgroundShare: Float
        get() = if (total == 0L) 0f else background.toFloat() / total.toFloat()
}

data class UsageReport(
    val apps: List<AppUsage>,
    /** Traffic that could not be attributed to an installed package. */
    val otherMobile: Long,
    val otherWifi: Long,
    val cycleStart: Long,
    val cycleEnd: Long,
) {
    val totalMobile get() = apps.sumOf { it.mobile } + otherMobile
    val totalWifi get() = apps.sumOf { it.wifi } + otherWifi
    val totalBackground get() = apps.sumOf { it.background }
}

object UsageRepository {

    private const val TAG = "DataGate"

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Start of the current billing cycle, given the day of month the bundle renews. */
    fun cycleStart(resetDay: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val day = resetDay.coerceIn(1, 28)
        if (cal.get(Calendar.DAY_OF_MONTH) < day) cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun load(ctx: Context, start: Long, end: Long = System.currentTimeMillis()): UsageReport? {
        if (!hasAccess(ctx)) return null
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return null
        val pm = ctx.packageManager

        // uid -> [mobileFg, mobileBg, wifiFg, wifiBg]
        val acc = HashMap<Int, LongArray>()

        @Suppress("DEPRECATION")
        val types = listOf(ConnectivityManager.TYPE_MOBILE to 0, ConnectivityManager.TYPE_WIFI to 2)

        for ((type, offset) in types) {
            // Returns null rather than throwing for types with no queryable template.
            val stats = try {
                nsm.querySummary(type, null, start, end)
            } catch (e: Exception) {
                Log.e(TAG, "querySummary(type=$type) failed: ${e.message}")
                null
            } ?: continue

            val bucket = NetworkStats.Bucket()
            try {
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    // Tagged buckets are a breakdown of the untagged ones; counting both
                    // would double every byte.
                    if (bucket.tag != NetworkStats.Bucket.TAG_NONE) continue

                    val bytes = bucket.rxBytes + bucket.txBytes
                    if (bytes == 0L) continue

                    val fg = bucket.state == NetworkStats.Bucket.STATE_FOREGROUND
                    val slot = offset + if (fg) 0 else 1
                    acc.getOrPut(bucket.uid) { LongArray(4) }[slot] += bytes
                }
            } finally {
                stats.close()
            }
        }

        val guarded = Protected.setFor(ctx)
        val apps = ArrayList<AppUsage>()
        var otherMobile = 0L
        var otherWifi = 0L

        for ((uid, v) in acc) {
            if (uid == NetworkStats.Bucket.UID_ALL) continue

            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            if (pkg == null) {
                // Shared or system UIDs with no single owning package. Surfacing these as
                // "Other" rather than dropping them is what keeps our total matching the
                // one Android's own Settings screen shows - if those disagree, nobody
                // trusts any number in the app.
                otherMobile += v[0] + v[1]
                otherWifi += v[2] + v[3]
                continue
            }

            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                otherMobile += v[0] + v[1]
                otherWifi += v[2] + v[3]
                continue
            }

            apps += AppUsage(
                packageName = pkg,
                label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg),
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                mobileFg = v[0],
                mobileBg = v[1],
                wifiFg = v[2],
                wifiBg = v[3],
                isProtected = pkg in guarded,
            )
        }

        return UsageReport(
            apps = apps.sortedByDescending { it.total },
            otherMobile = otherMobile,
            otherWifi = otherWifi,
            cycleStart = start,
            cycleEnd = end,
        )
    }

    /**
     * Bytes used by one package between [since] and now, across both transports.
     *
     * Needed because each restricted app has its own block timestamp, so the savings
     * estimate cannot reuse the cycle-wide query: comparing an app's expected usage over
     * nine hours against its total for the whole cycle is guaranteed to come out negative,
     * which is exactly why the figure was stuck at zero.
     */
    fun bytesSince(ctx: Context, pkg: String, since: Long): Long {
        if (!hasAccess(ctx) || since <= 0L) return 0L
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: return 0L
        val uid = runCatching {
            ctx.packageManager.getApplicationInfo(pkg, 0).uid
        }.getOrNull() ?: return 0L

        val end = System.currentTimeMillis()
        var total = 0L

        @Suppress("DEPRECATION")
        for (type in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            val stats = try {
                nsm.querySummary(type, null, since, end)
            } catch (e: Exception) {
                null
            } ?: continue
            val bucket = NetworkStats.Bucket()
            try {
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    if (bucket.uid != uid) continue
                    if (bucket.tag != NetworkStats.Bucket.TAG_NONE) continue
                    total += bucket.rxBytes + bucket.txBytes
                }
            } finally {
                stats.close()
            }
        }
        return total
    }

    /**
     * A measurement, ready to drop into translated text.
     *
     * The unicodeWrap is not decoration. "128.5 MB" is a left-to-right run, and Arabic and
     * Urdu strings interpolate two of them either side of a middot: "%1$s على الجوّال ·
     * %2$s في الخلفية". Without isolation the bidi algorithm reorders the neutrals between
     * them, the unit detaches from its number, and the two figures end up adjacent with a
     * single orphaned "MB" floating between the labels - on the one screen whose purpose is
     * telling mobile and background apart. Observed on device before this was added.
     *
     * Every caller is UI, so the isolate belongs here rather than at twelve call sites.
     */
    fun formatBytes(b: Long): String = BidiFormatter.getInstance().unicodeWrap(rawBytes(b))

    /** The same figure without bidi isolation, for logs and anything non-visual. */
    fun rawBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024L -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
