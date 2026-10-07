package com.shimonfiltser.smartwifianalyzer

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val SCAN_INTERVAL_MS = 4_000L
        const val SAMPLE_INTERVAL_MS = 1_500L
        const val HISTORY_MS = 120_000L

        const val BG = 0xFF121418.toInt()
        const val SURFACE = 0xFF1C1F26.toInt()
        const val SURFACE_HI = 0xFF2A2F3A.toInt()
        const val TEXT = 0xFFE6E8EB.toInt()
        const val TEXT_2 = 0xFF9AA3AE.toInt()
        const val ACCENT = 0xFF4FC3F7.toInt()
        const val WARN = 0xFFFFB74D.toInt()

        val PALETTE = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFFF8A65.toInt(), 0xFF81C784.toInt(), 0xFFBA68C8.toInt(),
            0xFFFFD54F.toInt(), 0xFFF06292.toInt(), 0xFF4DB6AC.toInt(), 0xFF9575CD.toInt(),
            0xFFAED581.toInt(), 0xFF64B5F6.toInt(), 0xFFFFB74D.toInt(), 0xFFE57373.toInt(),
            0xFF4DD0E1.toInt(), 0xFFDCE775.toInt(), 0xFFA1887F.toInt(), 0xFF90A4AE.toInt(),
        )
        val TAB_TITLES = listOf("Networks", "Channels", "Time", "Rating")

        const val AUTHOR = "Shimon Filtser"
        const val AUTHOR_EMAIL = "shimon.filtzer@gmail.com"
    }

    private lateinit var wifi: WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var paused = false
    private var permissionsAsked = false
    private var scanThrottled = false
    private var band = Band.GHZ_2_4
    private var tab = 0

    private var aps: List<AccessPoint> = emptyList()
    private val lastSeen = HashMap<String, AccessPoint>()
    private val history = HashMap<String, ArrayDeque<Sample>>()
    private val colors = HashMap<String, Int>()
    private var lastSampleAt = 0L

    private lateinit var status: TextView
    private lateinit var pauseButton: TextView
    private val bandChips = ArrayList<TextView>()
    private val tabButtons = ArrayList<TextView>()
    private lateinit var pages: List<View>
    private lateinit var listContainer: LinearLayout
    private lateinit var ratingContainer: LinearLayout
    private lateinit var channelGraph: ChannelGraphView
    private lateinit var timeGraph: TimeGraphView

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            if (!paused) {
                // Deprecated but still the only way to request a scan; Android throttles
                // foreground apps to 4 scans per 2 minutes unless disabled in developer options.
                @Suppress("DEPRECATION")
                scanThrottled = !wifi.startScan()
                refresh()
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        when {
            hasPermissions() -> start()
            !permissionsAsked -> {
                permissionsAsked = true
                requestPermissions(requiredPermissions(), REQ_PERMISSIONS)
            }
            else -> showPermissionHint()
        }
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasPermissions()) start() else showPermissionHint()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun showPermissionHint() {
        status.text = "Location and Nearby devices permissions are required — without them Android does not provide the list of networks. Tap to open app settings."
        status.setTextColor(WARN)
        status.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        }
    }

    private fun start() {
        if (running) return
        running = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(scanReceiver, filter)
        handler.post(scanLoop)
    }

    private fun stop() {
        if (!running) return
        running = false
        unregisterReceiver(scanReceiver)
        handler.removeCallbacks(scanLoop)
    }

    // ---------------------------------------------------------------- data

    @Suppress("DEPRECATION")
    private fun refresh() {
        if (paused) return
        val results: List<ScanResult> = try {
            wifi.scanResults
        } catch (_: SecurityException) {
            emptyList()
        }
        val info: WifiInfo? = wifi.connectionInfo
        val connectedBssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }

        aps = results.mapNotNull { toAccessPoint(it, connectedBssid) }.sortedByDescending { it.level }

        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
            lastSampleAt = now
            for (ap in aps) {
                history.getOrPut(ap.bssid) { ArrayDeque() }.addLast(Sample(now, ap.level))
                lastSeen[ap.bssid] = ap
            }
            val iter = history.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                while (e.value.isNotEmpty() && now - e.value.first().t > HISTORY_MS) e.value.removeFirst()
                if (e.value.isEmpty()) {
                    iter.remove()
                    lastSeen.remove(e.key)
                }
            }
        }

        updateStatus(info, connectedBssid)
        render()
    }

    @Suppress("DEPRECATION")
    private fun toAccessPoint(r: ScanResult, connectedBssid: String?): AccessPoint? {
        val band = WifiMath.bandOf(r.frequency) ?: return null
        val width = WifiMath.widthOf(r.channelWidth)
        val center = if (width > 20 && r.centerFreq0 > 0) r.centerFreq0 else r.frequency
        val rawSsid = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString() else r.SSID
        val ssid = rawSsid.orEmpty().removeSurrounding("\"").takeIf { it != WifiManager.UNKNOWN_SSID }.orEmpty()
        val bssid = r.BSSID.orEmpty()
        return AccessPoint(
            bssid = bssid,
            ssid = ssid,
            level = r.level,
            frequency = r.frequency,
            centerFrequency = center,
            widthMhz = width,
            channel = WifiMath.channelOf(r.frequency),
            band = band,
            security = WifiMath.securityOf(r.capabilities.orEmpty()),
            standard = if (Build.VERSION.SDK_INT >= 30) WifiMath.standardOf(r.wifiStandard, band) else "",
            connected = connectedBssid != null && bssid.equals(connectedBssid, ignoreCase = true),
            color = colors.getOrPut(bssid) { PALETTE[colors.size % PALETTE.size] },
        )
    }

    @Suppress("DEPRECATION")
    private fun updateStatus(info: WifiInfo?, connectedBssid: String?) {
        status.setOnClickListener(null)
        status.setTextColor(TEXT_2)
        val lm = getSystemService(LocationManager::class.java)
        when {
            !wifi.isWifiEnabled -> {
                status.text = "Wi-Fi is off. Turn it on to see networks."
                status.setTextColor(WARN)
            }
            !lm.isLocationEnabled -> {
                status.text = "Location is off — Android does not provide scan results without it. Tap to turn it on."
                status.setTextColor(WARN)
                status.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            else -> {
                val connected = aps.firstOrNull { it.connected }
                val head = if (connected != null && info != null) {
                    "Connected: ${connected.displayName} · ${info.rssi} dBm · channel ${connected.channel} · ${info.linkSpeed} Mbps"
                } else if (connectedBssid != null && info != null) {
                    "Connected: ${info.ssid.removeSurrounding("\"")} · ${info.rssi} dBm"
                } else {
                    "Not connected to Wi-Fi"
                }
                val counts = Band.entries.joinToString("  ") { b -> "${b.label}: ${aps.count { it.band == b }}" }
                val throttle = if (scanThrottled) "\nAndroid is throttling scans. For updates every 4 s, turn off \"Wi-Fi scan throttling\" in Developer options." else ""
                status.text = "$head\n$counts$throttle"
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        bandChips.forEachIndexed { i, chip -> styleChip(chip, Band.entries[i] == band) }
        tabButtons.forEachIndexed { i, b ->
            b.setTextColor(if (i == tab) ACCENT else TEXT_2)
            b.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        pages.forEachIndexed { i, page -> page.visibility = if (i == tab) View.VISIBLE else View.GONE }

        val inBand = aps.filter { it.band == band }
        when (tab) {
            0 -> renderList(inBand)
            1 -> channelGraph.setData(inBand, band)
            2 -> renderTime()
            3 -> renderRating(inBand)
        }
    }

    private fun renderList(list: List<AccessPoint>) {
        listContainer.removeAllViews()
        if (list.isEmpty()) {
            listContainer.addView(placeholder("No networks found in ${band.label}"))
            return
        }
        for (ap in list) listContainer.addView(networkRow(ap))
    }

    private fun renderTime() {
        val series = history.mapNotNull { (bssid, samples) ->
            val ap = lastSeen[bssid] ?: return@mapNotNull null
            if (ap.band != band || samples.isEmpty()) return@mapNotNull null
            SignalSeries(ap.displayName, ap.color, samples.toList(), ap.connected)
        }
        timeGraph.setData(series, SystemClock.elapsedRealtime(), band.label)
    }

    private fun renderRating(list: List<AccessPoint>) {
        ratingContainer.removeAllViews()
        val scores = WifiMath.ratingChannels(band).map { WifiMath.score(it, list) }
        val candidates = if (band == Band.GHZ_2_4) scores.filter { it.channel in listOf(1, 6, 11) } else scores
        val best = WifiMath.best(candidates)
        val connectedChannel = list.firstOrNull { it.connected }?.channel

        if (best != null) {
            val card = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            card.addView(text("Recommended channel", 13f, TEXT_2))
            card.addView(text("${best.channel}", 34f, ACCENT, bold = true))
            val why = buildString {
                if (band == Band.GHZ_2_4) append("Out of the non-overlapping channels 1, 6, 11. ")
                append(if (best.count == 0) "The channel is free." else "Networks on channel: ${best.count}, total interference ${best.interferenceDbm} dBm.")
                val overall = WifiMath.best(scores)
                if (band == Band.GHZ_2_4 && overall != null && overall.channel != best.channel && overall.stars > best.stars)
                    append("\nAmong all channels 1–13, channel ${overall.channel} has the least interference, but it overlaps its neighbours.")
                if (connectedChannel != null) append("\nYour network is on channel $connectedChannel.")
            }
            card.addView(text(why, 13f, TEXT))
            ratingContainer.addView(card)
        }

        for (s in scores) {
            val row = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val mine = if (s.channel == connectedChannel) "  ← your network" else ""
            row.addView(text("Channel ${s.channel}$mine", 15f, TEXT, bold = s.channel == best?.channel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val stars = "★".repeat(s.stars) + "☆".repeat(5 - s.stars)
            row.addView(text(stars, 16f, starColor(s.stars)).apply { setPadding(0, 0, dp(12), 0) })
            val detail = if (s.count == 0) "free" else "${s.count} · ${s.interferenceDbm} dBm"
            row.addView(text(detail, 12f, TEXT_2).apply {
                minWidth = dp(92)
                gravity = Gravity.END
            })
            ratingContainer.addView(row)
        }
    }

    private fun starColor(stars: Int) = when (stars) {
        5, 4 -> 0xFF66BB6A.toInt()
        3 -> 0xFFD4E157.toInt()
        2 -> 0xFFFFA726.toInt()
        else -> 0xFFEF5350.toInt()
    }

    private fun networkRow(ap: AccessPoint): View {
        val row = card().apply {
            orientation = LinearLayout.HORIZONTAL
            clipToOutline = true
        }
        row.addView(View(this).apply { setBackgroundColor(ap.color) }, LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        info.addView(
            if (ap.connected) text("${ap.displayName}  ● connected", 16f, ACCENT, bold = true)
            else text(ap.displayName, 16f, TEXT, bold = true)
        )
        info.addView(text("${ap.bssid} · ${ap.security}", 12f, TEXT_2))
        val details = listOf("Ch ${ap.channel}", "${ap.frequency} MHz", "${ap.widthMhz} MHz", ap.standard)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        info.addView(text(details, 12f, TEXT_2))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val signal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), dp(14), dp(10))
        }
        signal.addView(text("${ap.level}", 22f, WifiMath.levelColor(ap.level), bold = true).apply { gravity = Gravity.CENTER })
        signal.addView(text("dBm", 11f, TEXT_2).apply { gravity = Gravity.CENTER })
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(SURFACE_HI, 3)
            clipToOutline = true
        }
        val q = WifiMath.quality(ap.level)
        bar.addView(View(this).apply { setBackgroundColor(WifiMath.levelColor(ap.level)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, q.toFloat()))
        bar.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - q).toFloat()))
        signal.addView(bar, LinearLayout.LayoutParams(dp(56), dp(5)).apply { topMargin = dp(4) })
        row.addView(signal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return row
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(4))
        }
        header.addView(text(getString(R.string.app_name), 20f, TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val infoButton = text("i", 16f, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(SURFACE)
            }
            contentDescription = "About"
            setOnClickListener { showAbout() }
        }
        header.addView(infoButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
        pauseButton = text("Pause", 14f, ACCENT, bold = true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(SURFACE, 18)
            setOnClickListener { togglePause() }
        }
        header.addView(pauseButton)
        root.addView(header)

        status = text("Scanning…", 12f, TEXT_2).apply { setPadding(dp(16), dp(2), dp(16), dp(8)) }
        root.addView(status)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        for (b in Band.entries) {
            val chip = text(b.label, 14f, TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    band = b
                    render()
                }
            }
            bandChips += chip
            chips.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        root.addView(chips)

        val content = FrameLayout(this)
        listContainer = column()
        ratingContainer = column()
        channelGraph = ChannelGraphView(this)
        timeGraph = TimeGraphView(this, HISTORY_MS)
        pages = listOf(scroll(listContainer), padded(channelGraph), padded(timeGraph), scroll(ratingContainer))
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(text("by $AUTHOR", 11f, TEXT_2).apply {
            gravity = Gravity.CENTER
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(6))
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SURFACE)
        }
        TAB_TITLES.forEachIndexed { i, title ->
            val b = text(title, 14f, TEXT_2).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener {
                    tab = i
                    render()
                }
            }
            tabButtons += b
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        render()
        return root
    }

    private fun showAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(SURFACE, 20)
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }
        box.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        box.addView(text(getString(R.string.app_name), 20f, TEXT, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(text("Version $version", 13f, TEXT_2))
        box.addView(View(this).apply { setBackgroundColor(SURFACE_HI) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16)
            bottomMargin = dp(16)
        })
        box.addView(text("Developed by", 13f, TEXT_2))
        box.addView(text(AUTHOR, 17f, TEXT, bold = true))
        box.addView(text(AUTHOR_EMAIL, 15f, ACCENT).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { emailAuthor(version) }
        })
        box.addView(text("Close", 15f, ACCENT, bold = true).apply {
            setPadding(dp(24), dp(14), dp(24), dp(6))
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun emailAuthor(version: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$AUTHOR_EMAIL")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(AUTHOR_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} $version")
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No email app found. Write to $AUTHOR_EMAIL", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePause() {
        paused = !paused
        pauseButton.text = if (paused) "Resume" else "Pause"
        if (!paused) refresh()
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = rounded(if (selected) ACCENT else SURFACE, 18)
        chip.setTextColor(if (selected) BG else TEXT)
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), dp(12))
    }

    private fun scroll(child: View) = ScrollView(this).apply { addView(child) }

    private fun padded(child: View) = FrameLayout(this).apply {
        setPadding(dp(4), dp(4), dp(8), dp(8))
        addView(child)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 12)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun placeholder(msg: String) = text(msg, 15f, TEXT_2).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(48), dp(16), dp(48))
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
