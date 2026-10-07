package com.anubisproductions.datagate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import androidx.core.text.BidiFormatter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Per-network access control — the firewall half of the app.
 *
 * The usage screen answers "who is spending my data" with one switch per app. This screen
 * answers "which networks may this app use", which is a different question: on a metered
 * bundle the common request is "no mobile data, Wi-Fi is fine", and that cannot be expressed
 * with a single toggle.
 *
 * Kept as a secondary screen on purpose. Blocking is the commodity every competitor already
 * ships; the measurement is what nothing else has, so measurement leads.
 */
class BlockingActivity : Activity() {

    // The chosen language must reach every component that shows text - the service
    // included, because the ongoing notification is text. No-op on API 33+.
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(LocalePrefs.wrap(newBase))


    private val bidi = BidiFormatter.getInstance()

    private companion object {
        const val REQUEST_CONSENT = 2
    }

    private lateinit var status: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var modeHelp: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView

    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    private val wifiBlocked = HashSet<String>()
    private val mobileBlocked = HashSet<String>()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_blocking, parent, false)
            val a = shown[position]

            view.findViewById<ImageView>(R.id.icon).setImageDrawable(a.icon)
            view.findViewById<TextView>(R.id.label).text = a.label

            val wifi = view.findViewById<Switch>(R.id.block_wifi)
            val mobile = view.findViewById<Switch>(R.id.block_mobile)
            val sub = view.findViewById<TextView>(R.id.sub)

            // Detach listeners before setting state, or recycling a row fires the listener
            // for the previous app and silently rewrites its rule.
            wifi.setOnCheckedChangeListener(null)
            mobile.setOnCheckedChangeListener(null)

            if (a.isProtected) {
                wifi.isChecked = false
                mobile.isChecked = false
                wifi.isEnabled = false
                mobile.isEnabled = false
                sub.text = a.protectedReason
                view.alpha = 0.6f
            } else {
                wifi.isEnabled = true
                mobile.isEnabled = true
                wifi.isChecked = a.packageName in wifiBlocked
                mobile.isChecked = a.packageName in mobileBlocked
                sub.text = describe(a.packageName)
                view.alpha = 1f

                wifi.setOnCheckedChangeListener { _, checked ->
                    if (checked) wifiBlocked.add(a.packageName) else wifiBlocked.remove(a.packageName)
                    sub.text = describe(a.packageName)
                    commit()
                }
                mobile.setOnCheckedChangeListener { _, checked ->
                    if (checked) mobileBlocked.add(a.packageName) else mobileBlocked.remove(a.packageName)
                    sub.text = describe(a.packageName)
                    commit()
                }
            }
            return view
        }
    }

    private fun describe(pkg: String): String {
        val w = pkg in wifiBlocked
        val m = pkg in mobileBlocked
        return when {
            w && m -> getString(R.string.blocked_everywhere)
            m -> getString(R.string.blocked_mobile_only)
            w -> getString(R.string.blocked_wifi_only)
            else -> getString(R.string.allowed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_DataGate)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocking)
        title = getString(R.string.blocking_title)

        status = findViewById(R.id.blocking_status)
        modeSpinner = findViewById(R.id.mode)
        modeHelp = findViewById(R.id.mode_help)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)

        wifiBlocked.addAll(Rules.blockedOn(this, NetKind.WIFI))
        mobileBlocked.addAll(Rules.blockedOn(this, NetKind.MOBILE))

        setUpMode()
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        loadAsync()
    }

    override fun onPause() {
        super.onPause()
        // Same as the dashboard: the query does not survive leaving the screen. FINDINGS.md F7.
        search.text?.clear()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun loadAsync() {
        status.text = getString(R.string.loading_apps)
        Thread {
            val loaded = AppRepository.load(this)
            Handler(Looper.getMainLooper()).post {
                apps = loaded
                applyFilter()
                renderStatus()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        shown = if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
        adapter.notifyDataSetChanged()
    }

    private fun setUpMode() {
        val labels = listOf(
            getString(R.string.mode_drop),
            getString(R.string.mode_refuse),
            getString(R.string.mode_nxdomain),
        )
        val order = listOf(BlockMode.DROP, BlockMode.REFUSE, BlockMode.NXDOMAIN)
        modeSpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, labels)
                .apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
        modeSpinner.setSelection(order.indexOf(Rules.mode(this)).coerceAtLeast(0))
        showModeHelp(Rules.mode(this))

        modeSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Rules.setMode(this@BlockingActivity, order[pos])
                showModeHelp(order[pos])
                commit()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        })
    }

    private fun showModeHelp(mode: BlockMode) {
        modeHelp.setText(
            when (mode) {
                BlockMode.DROP -> R.string.mode_help_drop
                BlockMode.REFUSE -> R.string.mode_help_refuse
                BlockMode.NXDOMAIN -> R.string.mode_help_nxdomain
            }
        )
    }

    private fun commit() {
        Rules.setBlockedOn(this, NetKind.WIFI, wifiBlocked)
        Rules.setBlockedOn(this, NetKind.MOBILE, mobileBlocked)

        if (wifiBlocked.isEmpty() && mobileBlocked.isEmpty()) {
            BlockVpnService.stop(this)
        } else {
            val consent = VpnService.prepare(this)
            if (consent != null) startActivityForResult(consent, REQUEST_CONSENT)
            else BlockVpnService.start(this)
        }
        Handler(Looper.getMainLooper()).postDelayed({ renderStatus() }, 700)
    }

    @Deprecated("Single consent call; not worth a dependency for the result contract API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return
        if (resultCode == RESULT_OK) BlockVpnService.start(this)
        else Toast.makeText(this, R.string.consent_required, Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({ renderStatus() }, 700)
    }

    /** Says plainly whether rules are actually being enforced — see KNOWN_ISSUES 1. */
    private fun renderStatus() {
        val rules = wifiBlocked.size + mobileBlocked.size
        status.text = when {
            rules == 0 -> getString(R.string.blocking_none)
            BlockVpnService.isRunning ->
                getString(
                    R.string.blocking_active,
                    // Same reason as formatBytes: two numerals separated by neutrals
                    // inside an RTL string reorder without isolation.
                    bidi.unicodeWrap(wifiBlocked.size.toString()),
                    bidi.unicodeWrap(mobileBlocked.size.toString()),
                )
            else -> getString(R.string.blocking_inactive)
        }
    }
}
