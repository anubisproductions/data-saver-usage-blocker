package com.anubisproductions.datagate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.BidiFormatter

/**
 * Measure, then block, in one place.
 *
 * Every competitor does one or the other. The monitors (GlassWire, My Data Manager) show
 * you the numbers and cannot act on them; the firewalls (NetGuard, the clones) let you
 * block an app but will never tell you which one is the problem. The whole point of this
 * screen is that the ranking and the switch are the same row.
 */
class MainActivity : Activity() {

    // Below API 33 a per-app language has to be applied by hand, before any resource is
    // resolved. See LocalePrefs.
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(LocalePrefs.wrap(newBase))

    private companion object {
        const val REQUEST_CONSENT = 1
        const val REQUEST_NOTIFY = 2
        const val FILTER_ALL = 0
        const val FILTER_MOBILE = 1
        const val FILTER_BACKGROUND = 2
    }

    // A ScrollView since v6 - only its visibility is touched here, so View is the right type.
    private lateinit var onboarding: View
    private lateinit var dashboard: LinearLayout
    private lateinit var cycleUsed: TextView
    private lateinit var cycleSub: TextView
    private lateinit var bundleBar: ProgressBar
    private lateinit var master: Switch
    private lateinit var masterState: TextView
    private lateinit var backgroundTotal: TextView
    private lateinit var savedTotal: TextView
    private lateinit var filter: Spinner
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var bulk: Button
    private lateinit var empty: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerBody: TextView
    private lateinit var bannerAction: Button

    private var report: UsageReport? = null
    private var shown: List<AppUsage> = emptyList()

    /**
     * What a full row of the usage bar represents, in bytes.
     *
     * Recomputed whenever the filter changes, from the whole report rather than the visible
     * rows, so filtering the list never changes how long a given app's bar is. See F4.
     */
    private var barMax: Long = 0L
    private var blocked: MutableSet<String> = HashSet()

    /**
     * The rule set as it stood before a change that is waiting on VPN consent.
     *
     * A tester found that cancelling the consent dialog left the switch on and the
     * rule written: the app had asked permission, been refused, and kept the change
     * anyway. Worse than the switch, the rule persisted, so the app would have been
     * blocked the next time the engine started for any other reason - under a rule
     * the user had explicitly declined to authorise.
     */
    private var revertOnRefusal: Set<String>? = null
    private var filterMode = FILTER_ALL

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_usage, parent, false)
            val a = shown[position]

            view.findViewById<ImageView>(R.id.icon).setImageDrawable(a.icon)
            view.findViewById<TextView>(R.id.label).text = a.label
            // The big number is whatever the current filter ranks by. It used to be the
            // all-network total in every mode, so "Mobile only" showed Truecaller at
            // 19.8 MB when it had spent 25 KB of mobile data - a figure that contradicted
            // both the filter the user had chosen and the order the rows were in.
            // FINDINGS.md F2.
            val headline = when (filterMode) {
                FILTER_MOBILE -> a.mobile
                FILTER_BACKGROUND -> a.background
                else -> a.total
            }
            view.findViewById<TextView>(R.id.total).text = UsageRepository.formatBytes(headline)

            // The bar used to show this app's background *share* - the fraction of its own
            // traffic that was background. That made a 3.5 MB app with mostly-background
            // traffic render a nearly full bar sitting beside a trivial number, reading as an
            // alarm. A ratio next to an absolute figure invites exactly that misreading.
            //
            // It now shows magnitude against a fixed reference, so bars are comparable down
            // the column and a small app looks small. FINDINGS.md F4.
            val metric = when (filterMode) {
                FILTER_MOBILE -> a.mobile
                FILTER_BACKGROUND -> a.background
                else -> a.total
            }
            val bar = view.findViewById<ProgressBar>(R.id.bg_bar)
            bar.progress = if (barMax <= 0L) 0
            else ((metric.toDouble() / barMax) * 100).toInt().coerceIn(0, 100)

            val detail = view.findViewById<TextView>(R.id.detail)
            val toggle = view.findViewById<Switch>(R.id.toggle)

            if (a.isProtected) {
                toggle.isChecked = false
                toggle.isEnabled = false
                detail.text = getString(R.string.protected_app)
                view.alpha = 0.6f
            } else {
                toggle.isEnabled = true
                toggle.isChecked = a.packageName in blocked
                val bg = UsageRepository.formatBytes(a.background)
                val mob = UsageRepository.formatBytes(a.mobile)

                // A rule is not the same thing as enforcement. Saying "Restricted" while
                // the engine is down - consent refused, another VPN holding the slot,
                // establish() failing - is the app lying about what it is doing, and it is
                // the complaint that earns one-star reviews in this category. Savings are
                // hidden for the same reason: they would be derived from a false premise.
                val engineUp = BlockVpnService.isRunning
                detail.text = if (a.packageName in blocked) {
                    if (!engineUp) {
                        getString(R.string.restricted_inactive)
                    } else {
                        val saved = Budget.estimatedSavedFor(this@MainActivity, a.packageName)
                        if (saved > 0) {
                            getString(R.string.row_restricted_saved,
                                getString(R.string.restricted),
                                UsageRepository.formatBytes(saved))
                        } else {
                            getString(R.string.restricted)
                        }
                    }
                } else {
                    getString(R.string.row_usage, mob, bg)
                }
                view.alpha = 1f
            }
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme carries the splash; swap to the real theme before inflating or
        // the splash background stays behind the content.
        setTheme(R.style.Theme_DataGate)
        super.onCreate(savedInstanceState)
        holdSplashForReview()
        setContentView(R.layout.activity_main)

        onboarding = findViewById(R.id.onboarding)
        dashboard = findViewById(R.id.dashboard)
        cycleUsed = findViewById(R.id.cycle_used)
        cycleSub = findViewById(R.id.cycle_sub)
        bundleBar = findViewById(R.id.bundle_bar)
        master = findViewById(R.id.master)
        masterState = findViewById(R.id.master_state)
        backgroundTotal = findViewById(R.id.background_total)
        savedTotal = findViewById(R.id.saved_total)
        filter = findViewById(R.id.filter)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        bulk = findViewById(R.id.bulk)
        empty = findViewById(R.id.empty)
        banner = findViewById(R.id.banner)
        bannerTitle = findViewById(R.id.banner_title)
        bannerBody = findViewById(R.id.banner_body)
        bannerAction = findViewById(R.id.banner_action)

        blocked = HashSet(Rules.blockedAny(this))

        findViewById<Button>(R.id.grant).setOnClickListener { openUsageAccess() }
        findViewById<View>(R.id.cycle_block).setOnClickListener { askBundle() }
        bulk.setOnClickListener { restrictWorstOffenders() }
        findViewById<Button>(R.id.open_blocking).setOnClickListener {
            startActivity(Intent(this, BlockingActivity::class.java))
        }

        setUpFilter()
        setUpList()
        setUpMaster()
    }

    /**
     * Keeps the splash on screen in the .splashtest variant so it can actually be looked at.
     *
     * It lasts roughly 150 ms in normal use - shorter than a screenshot round trip - which
     * made it impossible to review or capture. BuildConfig.SPLASH_HOLD_MS is 0 in release,
     * so this returns immediately there and the constant folds away.
     *
     * Holds by refusing the first draw rather than sleeping: blocking the main thread would
     * trip the ANR watchdog long before sixty seconds were up.
     */
    private fun holdSplashForReview() {
        val until = SystemClock.uptimeMillis() + BuildConfig.SPLASH_HOLD_MS
        if (BuildConfig.SPLASH_HOLD_MS <= 0L) return
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (SystemClock.uptimeMillis() < until) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // Re-render whenever the engine starts or stops, rather than only when we happen
        // to be drawing. Posted to the main thread: the callback fires from the service.
        BlockVpnService.onStateChange = {
            Handler(Looper.getMainLooper()).post {
                report?.let { renderHeader(it) }
                adapter.notifyDataSetChanged()
            }
        }
        // Bank any enforcement window left open by a reboot before anything reads the
        // savings figure. Cheap, and it has to happen whether or not the engine is up. F8.
        Budget.reconcile(this, blocked)
        if (UsageRepository.hasAccess(this)) {
            onboarding.visibility = View.GONE
            dashboard.visibility = View.VISIBLE
            reload()
        } else {
            onboarding.visibility = View.VISIBLE
            dashboard.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        BlockVpnService.onStateChange = null
        // Leaving the screen clears the query. Keeping it meant coming back to a filtered
        // list with no obvious reason, and typing again appended to what was already there -
        // "chromechrome", and an empty list. FINDINGS.md F7.
        search.text?.clear()
    }

    // ------------------------------------------------------------------ onboarding

    /**
     * Explains the hand-off before making it.
     *
     * Tapping the button used to drop the user straight into a system Settings list with no
     * warning, on a screen that looks nothing like this app. The predictable reaction is
     * that the app is broken or has thrown you out, and it is the first thing a
     * non-technical tester hits. One dialog naming the screen they are about to see, the
     * row to look for and the way back costs a tap and removes the confusion.
     */
    private fun openUsageAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.usage_handoff_title)
            .setMessage(getString(R.string.usage_handoff_body, getString(R.string.app_name)))
            .setPositiveButton(R.string.usage_handoff_go) { _, _ -> jumpToUsageAccess() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun jumpToUsageAccess() {
        val ok = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        ).any { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        if (!ok) Toast.makeText(this, R.string.toast_settings_failed, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        val start = UsageRepository.cycleStart(Budget.resetDay(this))
        val r = UsageRepository.load(this, start) ?: return
        report = r
        applyFilter()
        renderHeader(r)
    }

    private fun renderHeader(r: UsageReport) {
        cycleUsed.text = UsageRepository.formatBytes(r.totalMobile)

        // The headline is mobile only, because that is the number that costs money. But a
        // cycle with the SIM switched off reads as "0 B" and looks broken, so always say
        // what the Wi-Fi figure was and when the cycle started - the app is working, the
        // user simply hasn't spent anything meterable yet.
        val wifiPart = getString(R.string.plus_wifi, UsageRepository.formatBytes(r.totalWifi))
        val since = getString(
            R.string.since_date,
            // A formatted date is another left-to-right run inside translated text.
            BidiFormatter.getInstance().unicodeWrap(
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                    .format(java.util.Date(r.cycleStart))
            ),
        )

        val bundle = Budget.bundleMb(this)
        if (bundle > 0) {
            val bundleBytes = bundle * 1_048_576L
            val pct = ((r.totalMobile.toDouble() / bundleBytes) * 100).toInt().coerceIn(0, 100)
            bundleBar.visibility = View.VISIBLE
            bundleBar.progress = pct
            cycleSub.text = getString(R.string.of_bundle, UsageRepository.formatBytes(bundleBytes)) +
                " · " + UsageRepository.formatBytes((bundleBytes - r.totalMobile).coerceAtLeast(0)) +
                " left · " + wifiPart
        } else {
            bundleBar.visibility = View.GONE
            cycleSub.text = "$since · $wifiPart · ${getString(R.string.no_bundle_set)}"
        }

        backgroundTotal.text = UsageRepository.formatBytes(r.totalBackground)

        val on = BlockVpnService.isRunning
        savedTotal.text = if (on) {
            UsageRepository.formatBytes(Budget.totalEstimatedSaved(this, blocked))
        } else {
            "—"
        }

        master.isChecked = on
        masterState.setText(
            when {
                on -> R.string.saving_on
                blocked.isNotEmpty() -> R.string.saving_off_with_rules
                else -> R.string.saving_off
            }
        )
        renderBanner()
    }

    /**
     * The single next action, always on screen.
     *
     * Two testers installed the app, looked at it for twelve and fifty-four seconds
     * respectively, and left without ever switching blocking on (`FINDINGS.md` F9). Both
     * had engaged enough to send a considered bug report, so this was not indifference -
     * a dashboard of numbers with the engine off simply does not tell anyone what to do
     * next, and the product delivers nothing until they do it.
     *
     * Three states, in the order a user meets them, so there is never more than one thing
     * being asked at a time:
     *
     * 1. Nothing restricted - the app has rules for nobody, so offer the one-tap bulk pick.
     * 2. Rules but no engine - the decision is made and nothing is enforcing it.
     * 3. Engine up, notifications denied - blocking works but the app is invisible, and
     *    the user has no way to tell it is running (F10). The state is known at engine
     *    start, where the permission is requested; it just was never surfaced when the
     *    answer was no.
     */
    private fun renderBanner() {
        bulk.visibility = View.VISIBLE
        when {
            // The bulk button at the foot of the screen does exactly what this banner
            // offers, and two identical calls to action is worse than one: the point of
            // the banner is that there is a single obvious next step. Hidden only in this
            // state, where the duplication exists.
            blocked.isEmpty() -> {
                bulk.visibility = View.GONE
                showBanner(
                    R.string.banner_none_title,
                    R.string.banner_none_body,
                    R.string.banner_none_action,
                ) { restrictWorstOffenders() }
            }

            !BlockVpnService.isRunning -> showBanner(
                R.string.banner_off_title,
                R.string.banner_off_body,
                R.string.banner_off_action,
            ) { startEngine() }

            !notificationsOn() -> showBanner(
                R.string.banner_silent_title,
                R.string.banner_silent_body,
                R.string.banner_silent_action,
            ) { openNotificationSettings() }

            else -> banner.visibility = View.GONE
        }
    }

    private fun showBanner(title: Int, body: Int, action: Int, onClick: () -> Unit) {
        bannerTitle.setText(title)
        bannerBody.setText(body)
        bannerAction.setText(action)
        bannerAction.setOnClickListener { onClick() }
        banner.visibility = View.VISIBLE
    }

    /**
     * True when a notification we post would actually be seen.
     *
     * `areNotificationsEnabled` covers both ways it can be off - the user turning the app's
     * notifications off in system settings, and, on API 33+, POST_NOTIFICATIONS never being
     * granted. Defaults to true on failure: a wrong "all fine" is better than nagging
     * someone whose notifications work.
     */
    private fun notificationsOn(): Boolean = runCatching {
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true
    }.getOrDefault(true)

    private fun openNotificationSettings() {
        val candidates = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The channel screen first: it is the one with the controls that actually apply
            // to the ongoing notification - silence it, minimise it, or switch it off.
            candidates += Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, BlockVpnService.ENGINE_CHANNEL)
            candidates += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        // Every OEM has the app-details screen, and its notification row is one tap away.
        candidates += Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        val ok = candidates.any {
            runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (!ok) Toast.makeText(this, R.string.toast_settings_failed, Toast.LENGTH_SHORT).show()
    }

    private fun applyFilter() {
        val r = report ?: return
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()

        // A search looks through every app, not only what the usage filter left behind.
        // Under "Mobile only" an app that has spent nothing on mobile is filtered out -
        // which is precisely the app you are looking for when you want to block it
        // *before* it costs you anything. Searching for it returned a blank screen, so the
        // filter made the app useless for the one job it is named after. FINDINGS.md F3.
        val pool = if (q.isNotEmpty()) r.apps else when (filterMode) {
            FILTER_MOBILE -> r.apps.filter { it.mobile > 0 }
            FILTER_BACKGROUND -> r.apps.filter { it.background > 0 }
            else -> r.apps
        }

        // Sorted by the same quantity the row displays, in every mode. r.apps already
        // arrives sorted by total, which is what "All" wants.
        var apps = when (filterMode) {
            FILTER_MOBILE -> pool.sortedByDescending { it.mobile }
            FILTER_BACKGROUND -> pool.sortedByDescending { it.background }
            else -> pool
        }
        if (q.isNotEmpty()) {
            apps = apps.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
        }
        shown = apps

        // The reference for the bars. Under "Mobile only" a bundle the user has set is the
        // most meaningful full-bar there is - it answers "how much of my allowance did this
        // app take" - and it is the same unit as the metric. Under the other filters the
        // metric is not mobile bytes, so the bundle would be comparing two different things;
        // there the largest app in the whole report is the reference instead.
        val bundleBytes = Budget.bundleMb(this) * 1_048_576L
        barMax = if (filterMode == FILTER_MOBILE && bundleBytes > 0L) {
            bundleBytes
        } else {
            r.apps.maxOfOrNull {
                when (filterMode) {
                    FILTER_MOBILE -> it.mobile
                    FILTER_BACKGROUND -> it.background
                    else -> it.total
                }
            } ?: 0L
        }

        // Say which of the two reasons the list is empty for. Without this a filtered-out
        // app and an app that is not installed looked identical: nothing at all.
        empty.text = if (q.isNotEmpty()) {
            getString(R.string.empty_no_match, search.text?.toString()?.trim().orEmpty())
        } else {
            getString(R.string.empty_filter)
        }
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ interaction

    private fun setUpFilter() {
        filter.adapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            listOf(
                getString(R.string.filter_all),
                getString(R.string.filter_mobile),
                getString(R.string.filter_background),
            ),
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
        filter.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                filterMode = pos
                applyFilter()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        })
    }

    private fun setUpList() {
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ ->
            val a = shown[position]
            if (a.isProtected) {
                Toast.makeText(this, getString(R.string.protected_app), Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            toggle(a)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun toggle(a: AppUsage) {
        if (a.packageName in blocked) {
            blocked.remove(a.packageName)
            Budget.clearBlock(this, a.packageName)
        } else {
            blocked.add(a.packageName)
            // Capture the baseline now, while we still have the app's pre-block rate.
            val r = report
            val days = if (r != null) {
                ((r.cycleEnd - r.cycleStart).toDouble() / 86_400_000.0).coerceAtLeast(1.0)
            } else 1.0
            Budget.recordBlock(this, a.packageName, (a.total / days).toLong(),
                BlockVpnService.isRunning)
        }
        commit()
    }

    /**
     * Bulk action. A reviewer of a competing app complained that stopping connections one
     * by one "TAKES FOREVER"; this restricts everything spending most of its data in the
     * background in a single tap.
     */
    private fun restrictWorstOffenders() {
        val r = report ?: return
        // Rank by background bytes, which is what the button promises.
        //
        // The first version also required backgroundShare > 0.5, and on real data that
        // matched nothing: the Play Store had 189 MB of background traffic but only a 42%
        // share because its foreground use is large too, while a puzzle game had a 77%
        // share of just 16 MB. Share is a ratio, not a cost - 189 MB is the problem
        // regardless of what fraction of that app's total it represents.
        val candidates = r.apps
            .filter {
                !it.isProtected &&
                    it.packageName !in blocked &&
                    it.background > 10L * 1_048_576L
            }
            .sortedByDescending { it.background }
            .take(5)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.toast_nothing_background, Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.joinToString("\n") {
            "· ${it.label} — ${UsageRepository.formatBytes(it.background)}"
        }
        val total = UsageRepository.formatBytes(candidates.sumOf { it.background })
        AlertDialog.Builder(this)
            .setTitle("Restrict ${candidates.size} app(s)?")
            .setMessage("These spent the most data in the background this cycle — $total between them:\n\n$names")
            .setPositiveButton("Restrict") { _, _ ->
                val days = ((r.cycleEnd - r.cycleStart).toDouble() / 86_400_000.0).coerceAtLeast(1.0)
                candidates.forEach {
                    blocked.add(it.packageName)
                    Budget.recordBlock(this, it.packageName, (it.total / days).toLong(),
                        BlockVpnService.isRunning)
                }
                commit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askBundle() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 5000"
            val current = Budget.bundleMb(this@MainActivity)
            if (current > 0) setText(current.toString())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_bundle_title)
            .setMessage("Size in MB. Leave empty to hide the gauge.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Budget.setBundleMb(this, input.text.toString().toIntOrNull() ?: 0)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setUpMaster() {
        master.setOnClickListener {
            if (master.isChecked) {
                if (blocked.isEmpty()) {
                    master.isChecked = false
                    Toast.makeText(this, R.string.toast_restrict_first, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startEngine()
            } else {
                BlockVpnService.stop(this)
                postRefresh()
            }
        }
    }

    private fun commit() {
        // The usage list has one switch per app, so it restricts on both transports.
        // Per-network control lives in the Blocking screen.
        val previous = Rules.blockedAny(this)
        (previous - blocked).forEach { Rules.setBoth(this, it, false) }
        (blocked - previous).forEach { Rules.setBoth(this, it, true) }
        adapter.notifyDataSetChanged()
        if (blocked.isEmpty()) {
            BlockVpnService.stop(this)
            postRefresh()
        } else {
            startEngine(revertTo = previous)
        }
    }

    private fun startEngine(revertTo: Set<String>? = null) {
        askForNotificationsOnce()
        val consent = VpnService.prepare(this)
        if (consent != null) {
            // Only meaningful while the dialog is up. Cleared either way in onActivityResult.
            revertOnRefusal = revertTo
            startActivityForResult(consent, REQUEST_CONSENT)
        } else {
            BlockVpnService.start(this)
            postRefresh()
        }
    }

    /**
     * On API 33+ POST_NOTIFICATIONS is a runtime permission and starts denied, so the
     * foreground-service notification is silently suppressed: the engine runs with nothing
     * in the shade to say so, and the only clue is the system key icon.
     *
     * That matters beyond tidiness. The listing and the privacy policy both tell the user
     * an ongoing notification will be there whenever blocking is active, and without this
     * request that statement is untrue on most current devices.
     *
     * Asked at the moment the engine starts rather than at launch, so the prompt has a
     * visible reason. Declining is fine - blocking still works, it is just quieter.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
    }

    @Deprecated("Single consent call; not worth a dependency for the result contract API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return

        val revertTo = revertOnRefusal
        revertOnRefusal = null

        if (resultCode == RESULT_OK) {
            BlockVpnService.start(this)
        } else {
            // Refused. Undo the change that prompted the request, so the switch returns to
            // where the user left it and no unauthorised rule is left in storage.
            if (revertTo != null) {
                val current = Rules.blockedAny(this)
                (current - revertTo).forEach { Rules.setBoth(this, it, false) }
                (revertTo - current).forEach { Rules.setBoth(this, it, true) }
                blocked = HashSet(revertTo)
                adapter.notifyDataSetChanged()
            }
            master.isChecked = false
            Toast.makeText(this, R.string.toast_vpn_required, Toast.LENGTH_LONG).show()
        }
        postRefresh()
    }

    private fun postRefresh() {
        Handler(Looper.getMainLooper()).postDelayed({ report?.let { renderHeader(it) } }, 700)
    }

    // ------------------------------------------------------------------ settings menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_settings) return super.onOptionsItemSelected(item)
        showSettings()
        return true
    }

    private fun showSettings() {
        // The version row is not a control, it is there to be read out. During the closed
        // test the commonest question is "which version is that?", and until now nothing in
        // the app could answer it - FINDINGS.md F15. app_name is already localised, so this
        // needs no new string.
        val version = getString(R.string.app_name) +
            " " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        val labels = arrayOf(
            getString(R.string.settings_language) + "\n" + currentLanguageLabel(),
            getString(R.string.settings_notification),
            getString(R.string.settings_check_update),
            version,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showLanguagePicker()
                    // The ongoing notification cannot be hidden by the app - a foreground
                    // service must have one, and Android raises the channel back to
                    // IMPORTANCE_LOW if we ask for less. The controls that do work are the
                    // system's own, so this hands the user straight to them.
                    1 -> openNotificationSettings()
                    2 -> openStoreListing()
                    // 3 is the version line: readable, not actionable.
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun currentLanguageLabel(): String {
        val tag = LocalePrefs.current(this)
        return if (tag.isEmpty()) getString(R.string.language_system)
        else LocalePrefs.ENDONYMS[tag] ?: tag
    }

    private fun showLanguagePicker() {
        val tags = listOf("") + LocalePrefs.SUPPORTED
        // Endonyms, so the list stays readable no matter what the app is currently showing.
        val labels = tags.map {
            if (it.isEmpty()) getString(R.string.language_system) else LocalePrefs.ENDONYMS[it] ?: it
        }.toTypedArray()
        val checked = tags.indexOf(LocalePrefs.current(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                if (tags[which] == LocalePrefs.current(this)) return@setSingleChoiceItems
                LocalePrefs.set(this, tags[which])
                // The ongoing notification was built in the old language and nothing
                // re-posts it on its own, so the engine keeps announcing itself in a
                // language the user just changed away from. Nudging the service re-runs
                // startForegroundCompat(), which rebuilds the text and refreshes the
                // channel name too - Android only updates a channel's name when the
                // channel is re-created.
                if (BlockVpnService.isRunning) BlockVpnService.start(this)
                // On 33+ the framework restarts us; below it, nothing will.
                if (LocalePrefs.needsManualRestart()) recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Hands the update check to the Play Store rather than doing it ourselves.
     *
     * The in-app update library would talk to Play from inside this process, and Play
     * Billing was measured to merge android.permission.INTERNET into the manifest
     * (KNOWN_ISSUES #9). An intent costs nothing and cannot touch the permission set,
     * which is the one claim this app cannot afford to lose.
     */
    private fun openStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
        )
        val chosen = if (market.resolveActivity(packageManager) != null) market else web
        runCatching { startActivity(chosen) }.onFailure {
            Toast.makeText(this, R.string.toast_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
