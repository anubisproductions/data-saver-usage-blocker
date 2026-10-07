package com.anubisproductions.datagate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.anubisproductions.datagate.net.Packet
import com.anubisproductions.datagate.net.Proto
import com.anubisproductions.datagate.net.Responder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The block engine.
 *
 * Package-list mode: the tunnel's *allowed application* set is our
 * *blocked* set, so only blocked apps' packets ever reach us. Every other app bypasses
 * the interface entirely and runs at native speed. We never forward a packet - there is
 * no NAT table, no socket pool and no userspace TCP stack in this service, which is why
 * the app needs no INTERNET permission.
 */
class BlockVpnService : VpnService() {

    // The chosen language must reach every component that shows text - the service
    // included, because the ongoing notification is text. No-op on API 33+.
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(LocalePrefs.wrap(newBase))


    companion object {
        const val ACTION_START = "com.anubisproductions.datagate.action.START"
        const val ACTION_STOP = "com.anubisproductions.datagate.action.STOP"
        /*
         * Two ids: the original, and its replacement.
         *
         * v6 briefly shipped a second channel at IMPORTANCE_MIN so the user could minimise
         * the ongoing notification. Measured on device, Android refuses it - a channel
         * carrying a foreground-service notification is raised back to IMPORTANCE_LOW
         * (requested=1, effective=2 in `dumpsys notification`), because an FGS notification
         * has to stay visible. The setting was therefore doing nothing and was replaced by a
         * link to Android's own channel screen, where the user does have real control.
         *
         * The original id is still deleted: it was created before this comment existed and
         * would otherwise linger in the app's notification settings.
         */
        private const val CHANNEL_LEGACY = "datagate_engine"
        const val ENGINE_CHANNEL = "datagate_engine_v2"
        private const val NOTIFICATION_ID = 1

        /** How long poll() waits before the loop re-checks [running]. */
        private const val POLL_TIMEOUT_MS = 250

        // Link-local addresses for the tun endpoint. Never leave the device.
        private const val TUN_V4 = "10.111.222.1"
        private const val TUN_V6 = "fd00:6e67:6174:6500::1"

        @Volatile var isRunning: Boolean = false
            private set(value) {
                val changed = field != value
                field = value
                if (changed) onStateChange?.invoke()
            }

        /**
         * Notified whenever the engine starts or stops.
         *
         * Without this the UI only samples isRunning while it happens to be drawing, and
         * the engine changes state on its own schedule - START_STICKY restarts, a network
         * transition rebuilding the tunnel, the system revoking the VPN. Observed live: the
         * service came back after the Activity had already rendered, so the screen said
         * "not active" while the tunnel was up and the savings figure showed a dash.
         */
        @Volatile var onStateChange: (() -> Unit)? = null

        /**
         * Always via startForegroundService on O+.
         *
         * Callers are frequently in the background - a broadcast receiver, later a boot
         * receiver or a Quick Settings tile - and plain startService() throws
         * BackgroundServiceStartNotAllowedException there.
         */
        private fun launch(ctx: Context, action: String) {
            val intent = Intent(ctx, BlockVpnService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun start(ctx: Context) = launch(ctx, ACTION_START)

        fun stop(ctx: Context) = launch(ctx, ACTION_STOP)

    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    /** Packages the tunnel is currently carrying, for the enforcement-time ledger. */
    private var enforcing: List<String> = emptyList()

    @Volatile private var running = false

    /** uid -> label, resolved lazily so the read loop never touches PackageManager. */
    private val uidLabels = HashMap<Int, String>()
    private lateinit var connectivity: ConnectivityManager

    /**
     * The transport actually carrying traffic, ignoring our own tunnel.
     *
     * Once the VPN is up it becomes the active network and reports TRANSPORT_VPN, so asking
     * the active network for its transport would answer "VPN" and per-network rules would
     * never resolve. The request below is pinned to NOT_VPN so the callback only ever sees
     * the real underlying network.
     */
    @Volatile private var netKind: NetKind? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        watchNetwork()
    }

    private fun watchNetwork() {
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                val kind = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                        NetKind.WIFI
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        NetKind.MOBILE
                    else -> null
                } ?: return

                if (kind != netKind) {
                    val previous = netKind
                    netKind = kind
                    Log.i(AttemptLog.TAG, "NET-CHANGE $previous -> $kind")
                    // The allowed-application list is fixed once the interface is
                    // established, so a transport change means rebuilding it with the set
                    // that applies to the new network.
                    if (running) restart()
                }
            }
        }
        netCallback = cb
        runCatching { connectivity.registerNetworkCallback(request, cb) }
            .onFailure { Log.w(AttemptLog.TAG, "network callback failed: ${it.message}") }
    }

    /**
     * Falls back to mobile when the transport is not yet known. Assuming the metered
     * network is the safer default for a data saver: erring towards restriction costs the
     * user nothing, erring the other way costs them money.
     */
    private fun currentKind(): NetKind = netKind ?: NetKind.MOBILE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() gives us only a few seconds to call startForeground(),
        // and the deadline applies even on paths that bail out early (empty rule set,
        // uninstalled packages, a STOP request). Go foreground first, decide afterwards.
        startForegroundCompat(getString(R.string.notif_starting))

        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> restart()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(AttemptLog.TAG, "VPN revoked by the system or another VPN app")
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        netCallback?.let { cb -> runCatching { connectivity.unregisterNetworkCallback(cb) } }
        netCallback = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun restart() {
        teardown()

        val kind = currentKind()
        val blocked = Rules.blockedOn(this, kind).filter { it != packageName }.toSet()
        val mode = Rules.mode(this)

        if (blocked.isEmpty()) {
            // Establishing with an empty allowed-application set would capture *every*
            // app, which is the opposite of what an empty rule set means.
            //
            // Note this is per transport: rules may exist for the other network and simply
            // not apply right now. That is correct behaviour, not an error.
            Log.i(AttemptLog.TAG, "no rules for $kind; engine idle")
            stopForegroundCompat()
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("DataGate")
            .setMtu(1500)
            .addAddress(TUN_V4, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUN_V6, 128)
            .addRoute("::", 0)
            // Route DNS into the tunnel so queries from blocked apps reach us rather than
            // resolving out of band.
            .addDnsServer("10.111.222.53")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        /*
         * The protection is re-checked here rather than trusted from the UI. Rules live in
         * SharedPreferences and outlive any version of the screen that wrote them, so a rule
         * saved before a package became protected would otherwise still be enforced - and
         * for a system-uid package that means capturing all of uid 1000, which is most of
         * Android. The screen decides what is offered; this decides what is applied.
         */
        val guarded = Protected.setFor(this)
        val pm = packageManager

        val applied = ArrayList<String>()
        for (pkg in blocked) {
            if (pkg in guarded || Protected.isSystemUid(pm, pkg)) {
                Log.w(AttemptLog.TAG, "refusing to block protected package: $pkg")
                continue
            }
            try {
                builder.addAllowedApplication(pkg)
                applied += pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // The rule outlived the app. Harmless; M2 prunes these on PACKAGE_REMOVED.
                Log.w(AttemptLog.TAG, "rule for uninstalled package ignored: $pkg")
            }
        }
        if (applied.isEmpty()) {
            Log.w(AttemptLog.TAG, "every blocked package is uninstalled; engine idle")
            stopSelf()
            return
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(AttemptLog.TAG, "establish() failed", e)
            null
        }
        if (fd == null) {
            Log.e(AttemptLog.TAG, "establish() returned null - is consent granted?")
            stopSelf()
            return
        }

        tun = fd
        running = true
        isRunning = true
        // Savings are credited per millisecond of enforcement, so the window opens here -
        // when the tunnel is actually carrying these packages - and not when the rule was
        // written. See Budget.estimatedSaved.
        Budget.engineStarted(this, applied)
        enforcing = ArrayList(applied)
        startForegroundCompat(
            resources.getQuantityString(
                if (kind == NetKind.WIFI) R.plurals.notif_active_wifi
                else R.plurals.notif_active_mobile,
                applied.size,
                applied.size,
            )
        )
        AttemptLog.reset()
        Log.i(
            AttemptLog.TAG,
            "ENGINE-UP net=$kind mode=$mode blocked=${applied.size} packages=${applied.joinToString(",")}"
        )

        worker = Thread({ readLoop(fd, mode) }, "datagate-tun").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun teardown() {
        running = false
        isRunning = false
        // Bank the enforcement window before anything else. teardown() runs on revoke, on
        // stop, and on every network change that rebuilds the tunnel, so missing it here
        // would leave a window open forever and savings would keep accruing off a stale
        // start time - the bug this whole ledger exists to prevent.
        Budget.engineStopped(this, enforcing)
        enforcing = emptyList()
        worker?.interrupt()
        worker = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        uidLabels.clear()
        stopForegroundCompat()
    }

    // ------------------------------------------------------------------ read loop

    private fun readLoop(descriptor: ParcelFileDescriptor, mode: BlockMode) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buf = ByteArray(32_767)

        // Wait for readability rather than re-entering read() immediately. The previous
        // shape - `if (n <= 0) continue` - turned every no-data and every EOF into an
        // unbounded spin: measured at 101.8% of a core on a Redmi 12 and 105.9% on a
        // Galaxy A26, which drained one handset from 79% to 11% in under six hours.
        // The poll timeout is what makes teardown responsive: interrupt() does not
        // unblock a FileInputStream read, so the loop has to come up for air and
        // re-check `running` on its own.
        val pollFd = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }

        Log.i(AttemptLog.TAG, "read loop started (mode=$mode)")
        try {
            while (running) {
                val ready = try {
                    Os.poll(arrayOf(pollFd), POLL_TIMEOUT_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    if (running) Log.w(AttemptLog.TAG, "tun poll failed: ${e.message}")
                    break
                }

                // Descriptor closed or errored under us - teardown, or the framework
                // revoking the tunnel. Either way there is nothing left to read.
                val revents = pollFd.revents.toInt()
                if (revents and (OsConstants.POLLHUP or OsConstants.POLLERR or
                        OsConstants.POLLNVAL) != 0
                ) {
                    Log.i(AttemptLog.TAG, "tun descriptor closed (revents=$revents)")
                    break
                }
                if (ready == 0) continue

                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    if (running) Log.w(AttemptLog.TAG, "tun read failed: ${e.message}")
                    break
                }
                // -1 is EOF: the interface is gone and no amount of retrying brings it
                // back. 0 cannot happen after a successful poll, but treat it as a
                // spurious wake-up rather than a reason to spin.
                if (n < 0) break
                if (n == 0) continue

                val packet = Packet.parse(buf, n) ?: continue
                record(packet)

                val reply = when (mode) {
                    BlockMode.DROP -> null
                    BlockMode.REFUSE -> refuse(packet)
                    BlockMode.NXDOMAIN ->
                        if (packet.isDnsQuery) Responder.dnsNxdomain(packet) ?: refuse(packet)
                        else refuse(packet)
                }

                if (reply != null) {
                    try {
                        output.write(reply)
                    } catch (e: Exception) {
                        if (running) Log.w(AttemptLog.TAG, "tun write failed: ${e.message}")
                    }
                }
            }
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
            }
            try {
                output.close()
            } catch (_: Exception) {
            }
            Log.i(AttemptLog.TAG, "read loop stopped")
        }
    }

    private fun refuse(p: Packet): ByteArray? = when {
        p.isTcp -> Responder.tcpReset(p)
        p.isUdp -> Responder.portUnreachable(p)
        else -> null
    }

    // ---------------------------------------------------------------- attribution

    private fun record(p: Packet) {
        val app = attribute(p)
        val dest = "${Packet.addrToString(p.dstAddr)}:${p.dstPort}"
        AttemptLog.record(app, p.protocol, p.isDnsQuery, dest)
    }

    /**
     * Best-effort mapping from a captured packet back to the app that sent it.
     *
     * Package-list mode guarantees every packet here came from *some* blocked app, but not
     * which one. [ConnectivityManager.getConnectionOwnerUid] closes that gap on API 29+.
     * On older releases, or for packets whose socket has already gone, we fall back to a
     * marker rather than guessing.
     */
    private fun attribute(p: Packet): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !p.hasPorts) return "unknown"

        val proto = if (p.isTcp) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
        val uid = try {
            connectivity.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByAddress(p.srcAddr), p.srcPort),
                InetSocketAddress(InetAddress.getByAddress(p.dstAddr), p.dstPort),
            )
        } catch (e: Exception) {
            android.os.Process.INVALID_UID
        }
        if (uid == android.os.Process.INVALID_UID) return "unknown"

        uidLabels[uid]?.let { return it }
        val label = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        uidLabels[uid] = label
        return label
    }

    // --------------------------------------------------------------- notification

    private fun startForegroundCompat(status: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // Retire the channel shipped up to v5. Leaving it behind would put a dead entry
            // in the app's notification settings whose importance no longer controls
            // anything.
            runCatching { nm.deleteNotificationChannel(CHANNEL_LEGACY) }
            nm.createNotificationChannel(
                NotificationChannel(
                    ENGINE_CHANNEL,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ENGINE_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
