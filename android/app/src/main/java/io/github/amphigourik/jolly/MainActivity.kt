// SPDX-FileCopyrightText: 2026 amphigourik
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.amphigourik.jolly

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Looper
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val TAG = "Jolly"
private const val REQ_SCAN = 42
private const val SOURCE_URL = "https://github.com/amphigourik/jolly"
private const val BAR_HIDE_MS = 4000L
private const val NOTE_MS = 4000L

private enum class Tab(val labelKey: String) { DRIVING("tab_driving"), DRONES("tab_drones"), GAMEPAD("tab_gamepad"), ABOUT("tab_about") }

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var video: ImageView
    private lateinit var joysticks: JoystickView
    private lateinit var infoText: TextView

    // Game screen: status pill, action bar and its panels
    private lateinit var pill: LinearLayout
    private lateinit var pillEye: ImageView
    private lateinit var pillName: TextView
    private lateinit var pillBattery: TextView
    private lateinit var pillPad: ImageView
    private lateinit var pillKid: TextView
    private lateinit var noteView: TextView
    private lateinit var recPill: TextView
    private lateinit var tray: LinearLayout
    private lateinit var handle: View
    private lateinit var tooltip: TextView
    private lateinit var volPop: LinearLayout
    private lateinit var volBar: SeekBar
    private lateinit var volLabel: TextView
    private lateinit var movesPanel: LinearLayout
    private lateinit var postureChips: FlowLayout
    private lateinit var btnConnect: IconBtn
    private lateinit var btnCam: IconBtn
    private lateinit var btnRec: IconBtn
    private lateinit var btnVol: IconBtn
    private lateinit var btnJl: IconBtn
    private lateinit var btnJh: IconBtn
    private lateinit var btnMoves: IconBtn
    private lateinit var btnSet: IconBtn
    private var barUp = false
    private var trayHeight = 0
    private var rootW = 0
    private var rootH = 0
    private var trayDisconnected = false
    private var eyeLit = false
    private var transientNote: String? = null
    private val hideBar = Runnable { if (!panelOpen() && !barPinned) setBar(false) }
    private var blinking = false
    private val eyeBlink = object : Runnable {
        override fun run() {
            if (!trying()) { blinking = false; return }
            eyeLit = !eyeLit
            pillEye.setImageResource(if (eyeLit) R.drawable.ic_eye_on else R.drawable.ic_eye_off)
            mainHandler.postDelayed(this, 450)
        }
    }
    private val clearNote = Runnable { transientNote = null; refreshHud() }
    private val hideTooltip = Runnable { tooltip.visibility = View.GONE }

    private val conn = ConnectionMachine()
    private val state get() = conn.state
    private var currentNetwork: Network? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attemptRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val attemptRunnable = Runnable { attemptReconnect() }

    // Extra settings
    private var jumpsDisabled = false
    private var vibrationOn = true
    private var barPinned = false
    private var kid = KidState()
    private var pendingName: String? = null
    private var syncingUi = false
    private var lastToast: Toast? = null
    private val noVibratorLogged = HashSet<Int>()
    private lateinit var maxSpeedBar: SeekBar
    private lateinit var jumpsSwitch: Switch
    private lateinit var kidBtn: Button
    private lateinit var pickerList: LinearLayout
    private lateinit var pickerStatus: TextView
    private lateinit var nameField: EditText
    private lateinit var renameBtn: Button
    private lateinit var renameBox: View
    private var droneState = DroneState()
    private var posture = 0
    private var drone: JumpingDrone? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var decoding = false
    @Volatile private var resumed = false
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var recorder: VideoRecorder? = null
    private var recStart = 0L

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var maxSpeed = 100
    private var turnSens = 60
    private var expo = false
    private var drones = emptyList<String>()
    /** The active drone's Wi-Fi name: head of the saved list (most recently used first). */
    private val ssid get() = SavedDrones.active(drones).orEmpty()
    private var settingsOpen = false
    private var tab = Tab.DRIVING
    private lateinit var settingsOverlay: View
    private lateinit var card: LinearLayout
    private lateinit var tabViews: Map<Tab, View>
    private lateinit var tabButtons: Map<Tab, TextView>
    private val pickerOpen get() = settingsOpen && tab == Tab.DRONES
    private var nets = emptyList<WifiNet>()
    private var scanning = false
    private var scanPermissionDenied = false
    private var scanSecurityError = false
    private var scanReceiver: BroadcastReceiver? = null
    private var videoOn = true
    private lateinit var camNote: TextView
    private var errorText: String? = null

    // Gamepad
    private val pad = GamepadLogic(Protocol.ANIMATIONS.size)
    private var padProfile = PadProfile.JOYSTICKS
    private var persoMapping: PadMapping = Presets.JOYSTICKS
    private var remapOpen = false
    private var capture: CaptureSession? = null
    private var captureTarget: Owner? = null
    private var lastValues: Map<Int, Float>? = null
    private lateinit var remapPanel: View
    private lateinit var padMain: View
    private lateinit var remapList: LinearLayout
    private lateinit var remapStatus: TextView
    private lateinit var profileGroup: android.widget.RadioGroup
    private var syncingRadios = false
    private var touchS = 0f
    private var touchT = 0f
    private var padS = 0f
    private var padT = 0f
    private var padCount = 0
    private var lastButton = "—"
    private lateinit var padStatus: TextView
    private var padText: TextView? = null // debug builds only
    private lateinit var legendText: TextView
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { Log.i(TAG, "gamepad: added ${InputDevice.getDevice(id)?.name}"); scanPads() }
        override fun onInputDeviceRemoved(id: Int) { Log.i(TAG, "gamepad: removed (id $id)"); scanPads() }
        override fun onInputDeviceChanged(id: Int) { scanPads() }
    }

    private val texts = Texts { key, args ->
        val id = resources.getIdentifier(key, "string", packageName)
        if (id == 0) key else getString(id, *args)
    }
    private fun s(key: String, vararg args: Any?) = texts.t(key, *args)

    private fun dp(v: Int) = px(v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        maxSpeed = prefs.getInt("max_speed", 100)
        turnSens = prefs.getInt("turn_sens", 60)
        expo = prefs.getBoolean("expo", false)
        drones = SavedDrones.parse(prefs.getString("drones", null))
        if (drones.isEmpty()) prefs.getString("ssid", null)?.let { drones = SavedDrones.use(drones, it) } // migrate the old single-SSID setting
        videoOn = prefs.getBoolean("video_on", true)
        jumpsDisabled = prefs.getBoolean("jumps_disabled", false)
        vibrationOn = prefs.getBoolean("vibration", true)
        barPinned = prefs.getBoolean("bar_pinned", false)
        kid = if (prefs.getBoolean("kid_active", false))
            KidState(true, PilotSettings(prefs.getInt("kid_saved_max", 100), prefs.getBoolean("kid_saved_jumps", false)))
        else KidState()
        val (prof, mapping, perso) = PadProfiles.resolve(prefs.getString("pad_profile", null), prefs.getString("pad_perso", null))
        padProfile = prof; persoMapping = perso; pad.mapping = mapping
        buildUi()
        val im = getSystemService(InputManager::class.java)
        im.registerInputDeviceListener(inputListener, Handler(Looper.getMainLooper()))
        scanPads()
        refreshHud()
        refreshVideoUi()
        refreshKidUi()
        if (ssid.isEmpty()) window.decorView.post { openSettings(Tab.DRONES) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(video, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        camNote = TextView(this).apply {
            text = s("cam_off_note"); setTextColor(Color.GRAY); textSize = 14f; visibility = View.GONE
        }
        root.addView(camNote, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        joysticks = JoystickView(this).apply {
            onChange = { s, t -> touchS = s; touchT = t; applyControl() }
            onTap = { onVideoTap() }
            onGrab = { if (panelOpen()) closePanels() }
        }
        root.addView(joysticks, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Status pill (top left) and the temporary line under it
        pillEye = ImageView(this).apply { setImageResource(R.drawable.ic_eye_off) }
        pillName = TextView(this).apply { setTextColor(Pal.WHITE); textSize = 14f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(120) }
        pillBattery = TextView(this).apply { setTextColor(Pal.WHITE); textSize = 14f }
        pillPad = ImageView(this).apply { setImageResource(R.drawable.ic_gamepad); visibility = View.GONE }
        pillKid = TextView(this).apply {
            text = s("kid_badge"); setTextColor(Pal.INK); textSize = 11f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = shape(Pal.LIME, dp(10).toFloat()); setPadding(dp(8), dp(1), dp(8), dp(1)); visibility = View.GONE
        }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(0xB31E2126.toInt(), dp(18).toFloat())
            setPadding(dp(10), dp(4), dp(12), dp(4))
            addView(pillEye, LinearLayout.LayoutParams(dp(26), dp(16)))
            addView(pillName, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            addView(pillBattery, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            addView(pillPad, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(10) })
            addView(pillKid, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            setOnClickListener { onPillTap() }
        }
        noteView = TextView(this).apply {
            setTextColor(Pal.WHITE); textSize = 13f; visibility = View.GONE
            background = shape(0xB31E2126.toInt(), dp(12).toFloat()); setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pill, LinearLayout.LayoutParams(-2, -2))
            addView(noteView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }, FrameLayout.LayoutParams(dp(300), -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(8), 0, 0) })

        recPill = TextView(this).apply {
            setTextColor(Pal.RED); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = shape(0xB31E2126.toInt(), dp(16).toFloat()); setPadding(dp(14), dp(5), dp(14), dp(5)); visibility = View.GONE
        }
        root.addView(recPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        // Action bar: hidden below the screen edge, a short handle stays as a hint
        handle = View(this).apply { background = shape(0x66E8EAED, dp(2).toFloat()) }
        root.addView(handle, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        btnConnect = IconBtn(this, R.drawable.ic_eye_off, s("btn_connect")).apply { setOnClickListener { touchBar(); connect() } }
        btnCam = IconBtn(this, R.drawable.ic_cam, s("tip_photo")).apply { setOnClickListener { if (on) { touchBar(); takePhoto() } } }
        btnRec = IconBtn(this, R.drawable.ic_rec, s("tip_record")).apply { setOnClickListener { if (on) { touchBar(); toggleRecording() } } }
        btnVol = IconBtn(this, R.drawable.ic_vol, s("tip_volume")).apply { setOnClickListener { if (on) togglePanel(volPop) } }
        btnJl = IconBtn(this, R.drawable.ic_jl, s("btn_long_jump")).apply { setOnClickListener { if (on) { touchBar(); doJump(false) } } }
        btnJh = IconBtn(this, R.drawable.ic_jh, s("btn_high_jump")).apply { setOnClickListener { if (on) { touchBar(); doJump(true) } } }
        btnMoves = IconBtn(this, R.drawable.ic_moves, s("tip_moves")).apply { setOnClickListener { if (on) togglePanel(movesPanel) } }
        btnSet = IconBtn(this, R.drawable.ic_set, s("act_settings")).apply { setOnClickListener { touchBar(); toggleSettings() } }
        for (b in listOf(btnConnect, btnCam, btnRec, btnVol, btnJl, btnJh, btnMoves, btnSet)) b.setOnLongClickListener { showTip(b); true }
        tray = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(0x99101215.toInt(), dp(30).toFloat())
            isClickable = true
        }
        root.addView(tray, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })

        volPop = buildVolumePopover().apply { visibility = View.GONE }
        root.addView(volPop, FrameLayout.LayoutParams(dp(220), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        movesPanel = buildMovesPanel().apply { visibility = View.GONE }
        root.addView(movesPanel, FrameLayout.LayoutParams(dp(300), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        tooltip = TextView(this).apply {
            setTextColor(Pal.WHITE); textSize = 12f; visibility = View.GONE
            background = shape(0xE61E2126.toInt(), dp(8).toFloat()); setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        root.addView(tooltip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        settingsOverlay = buildSettings().apply { visibility = View.GONE }
        root.addView(settingsOverlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) onRootSized(r - l, b - t)
        }
        setContentView(root)
    }

    // --- Action bar ----------------------------------------------------------------
    private fun panelOpen() = volPop.visibility == View.VISIBLE || movesPanel.visibility == View.VISIBLE

    /** Lays the tray out in the gap between the joysticks: grab zones if the buttons stay >= 34 dp, else only clear of the visible bases. */
    private fun onRootSized(w: Int, h: Int) {
        rootW = w; rootH = h
        layoutTray()
        val c = card.layoutParams as FrameLayout.LayoutParams
        card.layoutParams = c.apply { width = w * 72 / 100; height = h * 84 / 100 }
    }

    private fun layoutTray() {
        val w = rootW; val h = rootH
        if (w == 0) return
        trayDisconnected = state == ConnState.DISCONNECTED
        val items = if (trayDisconnected) listOf(btnConnect, btnSet)
            else listOf(btnCam, btnRec, btnVol, null, btnJl, btnJh, btnMoves, null, btnSet)
        val n = items.count { it != null }
        val dividers = items.size - n
        val pad = dp(8); val gap = dp(4); val div = dp(9)
        val band = (h - dp(8) - dp(56)).toFloat() to (h - dp(8)).toFloat()
        fun sizeFor(zone: Float): Int {
            val (l, r) = joysticks.gap(band.first, band.second, zone)
            return ((r - l - dp(16) - 2 * pad - (n - 1) * gap - dividers * div) / n).toInt()
        }
        var size = sizeFor(1.8f)
        if (size < dp(34)) size = sizeFor(1.2f).coerceAtLeast(dp(30))
        size = size.coerceAtMost(dp(44))
        tray.removeAllViews()
        tray.setPadding(pad, pad, pad, pad)
        for (b in items) {
            if (b == null) {
                tray.addView(View(this).apply { setBackgroundColor(0x33E8EAED) }, LinearLayout.LayoutParams(dp(1), size * 2 / 3).apply { setMargins(dp(4), 0, dp(4), 0) })
            } else {
                b.size(size)
                tray.addView(b, LinearLayout.LayoutParams(size, size).apply { if (b !== items.first()) leftMargin = gap })
            }
        }
        trayHeight = size + 2 * pad
        val (pl, pr) = joysticks.gap(h * 0.72f, h * 0.72f, 1.2f)
        val panelW = (pr - pl - dp(16)).toInt().coerceIn(dp(180), dp(360))
        val above = dp(8) + trayHeight + dp(8)
        for (v in listOf(volPop, movesPanel)) v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (v === volPop) panelW.coerceAtMost(dp(240)) else panelW; bottomMargin = above
        }
        setBar(barUp, animate = false)
    }

    private fun setBar(up: Boolean, animate: Boolean = true) {
        val show = up || state == ConnState.DISCONNECTED || barPinned
        barUp = show
        mainHandler.removeCallbacks(hideBar)
        if (!show) { closePanels(); tooltip.visibility = View.GONE }
        val y = if (show) 0f else (trayHeight + dp(16)).toFloat()
        if (animate) tray.animate().translationY(y).setDuration(180).start() else tray.translationY = y
        handle.animate().alpha(if (show) 0f else 1f).setDuration(if (animate) 180 else 0).start()
        if (show && state != ConnState.DISCONNECTED && !panelOpen()) mainHandler.postDelayed(hideBar, BAR_HIDE_MS)
    }

    /** An interaction with the bar: keep it up for another 4 s (paused while a panel is open). */
    private fun touchBar() {
        tooltip.visibility = View.GONE
        mainHandler.removeCallbacks(hideBar)
        if (barUp && state != ConnState.DISCONNECTED && !panelOpen()) mainHandler.postDelayed(hideBar, BAR_HIDE_MS)
    }

    private fun onVideoTap() {
        if (panelOpen()) closePanels()
        setBar(true)
    }

    private fun togglePanel(p: View) {
        val open = p.visibility == View.VISIBLE
        closePanels()
        if (!open) {
            if (p === movesPanel) refreshPostureChips()
            if (p === volPop) droneState.audioVolume?.let { volBar.progress = it }
            p.visibility = View.VISIBLE
            (if (p === volPop) btnVol else btnMoves).ring = Pal.LIME
            mainHandler.removeCallbacks(hideBar)
        } else touchBar()
        tooltip.visibility = View.GONE
    }

    private fun closePanels() {
        volPop.visibility = View.GONE; movesPanel.visibility = View.GONE
        btnVol.ring = 0; btnMoves.ring = 0
        touchBar()
    }

    private fun showTip(b: IconBtn) {
        touchBar()
        tooltip.text = b.contentDescription
        tooltip.visibility = View.VISIBLE
        tooltip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); val rl = IntArray(2)
        b.getLocationInWindow(loc); root.getLocationInWindow(rl)
        val x = (loc[0] - rl[0] + b.width / 2 - tooltip.measuredWidth / 2).coerceIn(dp(4), root.width - tooltip.measuredWidth - dp(4))
        tooltip.layoutParams = (tooltip.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = x; topMargin = (loc[1] - rl[1] - tooltip.measuredHeight - dp(6)).coerceAtLeast(0)
        }
        tooltip.removeCallbacks(hideTooltip)
        tooltip.postDelayed(hideTooltip, 1800)
    }

    private fun buildVolumePopover(): LinearLayout {
        volLabel = TextView(this).apply { setTextColor(Pal.WHITE); textSize = 13f; gravity = Gravity.CENTER; text = s("slider_pct", 0) }
        volBar = SeekBar(this).apply {
            max = 100; styleSeek(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { volLabel.text = s("slider_pct", p) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val v = sb.progress
                    Log.i(TAG, "audio: volume -> $v")
                    sendAsync { it.send(Protocol.audioVolume(v)) }
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xF21E2126.toInt(), dp(16).toFloat()); setPadding(dp(14), dp(10), dp(14), dp(8))
            isClickable = true
            addView(volLabel); addView(volBar)
        }
    }

    private fun buildMovesPanel(): LinearLayout {
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Pal.LIGHT_GREY); textSize = 12f; setPadding(0, 0, 0, dp(4)) }
        postureChips = FlowLayout(this, dp(6), dp(6))
        val tricks = FlowLayout(this, dp(6), dp(6))
        Protocol.ANIMATIONS.forEach { (id, name) ->
            tricks.addView(chip(this, s("trick_$name")).apply { setOnClickListener { launchAnimation(id); closePanels() } })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xF21E2126.toInt(), dp(16).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            addView(label(s("moves_posture"))); addView(postureChips)
            addView(label(s("moves_tricks")).apply { setPadding(0, dp(10), 0, dp(4)) }); addView(tricks)
        }
    }

    private fun refreshPostureChips() {
        val cur = droneState.posture?.takeIf { it in Protocol.POSTURES.indices } ?: posture
        postureChips.removeAllViews()
        Protocol.POSTURES.forEachIndexed { i, key ->
            postureChips.addView(chip(this, s("posture_$key").replaceFirstChar { it.uppercase() }, selected = i == cur).apply {
                setOnClickListener {
                    posture = i
                    sendAsync { it.posture(i) }
                    closePanels(); refreshHud()
                }
            })
        }
    }

    private fun onPillTap() {
        if (state == ConnState.DISCONNECTED) return
        val m = android.widget.PopupMenu(this, pill)
        m.menu.add(s(if (state == ConnState.CONNECTED) "btn_disconnect" else "btn_cancel"))
        m.setOnMenuItemClickListener { disconnect(); true }
        m.show()
    }

    /** A short line under the pill (animation notes...), cleared after 4 s. */
    private fun showNote(m: String) {
        transientNote = m
        mainHandler.removeCallbacks(clearNote)
        mainHandler.postDelayed(clearNote, NOTE_MS)
        refreshHud()
    }

    // --- Settings card ------------------------------------------------------
    private fun label(t: CharSequence = "", size: Float = 14f, color: Int = Pal.WHITE) =
        TextView(this).apply { text = t; setTextColor(color); textSize = size }
    private fun heading(t: String) = label(t, 12f, Pal.LIGHT_GREY).apply { setPadding(0, dp(10), 0, dp(4)); isAllCaps = true }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** Two columns of a tab; scrolls only if a very small screen cannot hold them. */
    private fun twoCols(left: View, right: View, wLeft: Float = 1f): View = ScrollView(this).apply {
        isFillViewport = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -2, wLeft).apply { rightMargin = dp(12) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        })
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text; isChecked = checked; styleSwitch(this)
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun buildSettings(): View {
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true; setOnClickListener { closeSettings() } }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xFF1A1D22.toInt(), dp(22).toFloat()); setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true // swallows taps so they do not reach the dimmed overlay
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val buttons = LinkedHashMap<Tab, TextView>()
        Tab.values().forEach { t ->
            buttons[t] = chip(this, s(t.labelKey)).apply { setOnClickListener { selectTab(t) } }
            tabRow.addView(buttons[t], LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        tabButtons = buttons
        tabRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabRow.addView(TextView(this).apply {
            text = "✕"; setTextColor(Pal.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = shape(Pal.PANEL, 0f, oval = true); isClickable = true
            contentDescription = s("btn_close")
            setOnClickListener { closeSettings() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        card.addView(tabRow)
        val content = FrameLayout(this)
        tabViews = mapOf(Tab.DRIVING to drivingTab(), Tab.DRONES to dronesTab(), Tab.GAMEPAD to gamepadTab(), Tab.ABOUT to aboutTab())
        tabViews.values.forEach { content.addView(it.apply { visibility = View.GONE }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        card.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        overlay.addView(card, FrameLayout.LayoutParams(dp(520), dp(300), Gravity.CENTER))
        return overlay
    }

    private fun drivingTab(): View {
        val left = column(); val right = column()
        fun slider(col: LinearLayout, name: String, initial: Int, onChange: (Int) -> Unit): SeekBar {
            val l = label()
            val sb = SeekBar(this).apply {
                max = 80; progress = initial.coerceIn(20, 100) - 20; styleSeek(this)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { l.text = s("slider_fmt", name, p + 20); onChange(p + 20) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            l.text = s("slider_fmt", name, initial)
            col.addView(l); col.addView(sb)
            return sb
        }
        maxSpeedBar = slider(left, s("max_speed"), maxSpeed) { maxSpeed = it; prefs.edit().putInt("max_speed", it).apply() }
        slider(left, s("turn_sens"), turnSens) { turnSens = it; prefs.edit().putInt("turn_sens", it).apply() }
        left.addView(switchRow(s("progressive"), expo) { expo = it; prefs.edit().putBoolean("expo", it).apply() })
        jumpsSwitch = switchRow(s("disable_jumps"), jumpsDisabled) {
            jumpsDisabled = it; prefs.edit().putBoolean("jumps_disabled", it).apply()
            if (!syncingUi) refreshJumpUi()
        }
        right.addView(jumpsSwitch)
        right.addView(switchRow(s("vibration"), vibrationOn) { vibrationOn = it; prefs.edit().putBoolean("vibration", it).apply() })
        right.addView(switchRow(s("bar_pinned"), barPinned) { barPinned = it; prefs.edit().putBoolean("bar_pinned", it).apply(); setBar(barUp) })
        right.addView(heading(s("kid_title")))
        kidBtn = PillButton(this, "", red = true).apply { setOnClickListener { toggleKid() } }
        right.addView(kidBtn, LinearLayout.LayoutParams(-2, -2))
        right.addView(label(s("kid_sub"), 12f, Pal.LIGHT_GREY).apply { setPadding(0, dp(4), 0, 0) })
        return twoCols(left, right)
    }

    private fun dronesTab(): View {
        val left = column(); val right = column()
        pickerStatus = label("", 13f, Pal.LIME)
        left.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(pickerStatus, LinearLayout.LayoutParams(0, -2, 1f))
            addView(PillButton(context, s("picker_rescan")).apply { setOnClickListener { if (hasScanPermission()) startScan() else startPicker() } })
        })
        pickerList = column()
        left.addView(ScrollView(this).apply { addView(pickerList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(heading(s("picker_manual")))
        val field = EditText(this).apply { hint = s("picker_hint"); setTextColor(Pal.WHITE); setHintTextColor(Pal.GREY); setSingleLine() }
        right.addView(field)
        right.addView(PillButton(this, s("picker_add")).apply {
            setOnClickListener { field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::chooseDrone) }
        })
        right.addView(heading(s("sec_video")))
        right.addView(switchRow(s("video_stream"), videoOn) { setVideoOn(it) })
        right.addView(label(s("video_stream_sub"), 12f, Pal.LIGHT_GREY))
        nameField = EditText(this).apply { setTextColor(Pal.WHITE); setSingleLine() }
        renameBtn = PillButton(this, s("btn_rename")).apply { setOnClickListener { renameDrone() } }
        renameBox = column().apply {
            addView(heading(s("drone_name"))); addView(nameField); addView(renameBtn)
            visibility = View.GONE
        }
        right.addView(renameBox)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, -1, 1.2f).apply { rightMargin = dp(12) })
            addView(ScrollView(context).apply { addView(right) }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
        }
    }

    private fun gamepadTab(): View {
        val left = column(); val right = column()
        padStatus = label("", 14f, Pal.LIME)
        left.addView(padStatus)
        left.addView(heading(s("pad_profile_label")))
        profileGroup = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        PadProfile.values().forEach { p ->
            profileGroup.addView(android.widget.RadioButton(this).apply {
                text = s("profile_radio", s(p.labelKey)); setTextColor(Pal.WHITE); id = View.generateViewId(); tag = p
                buttonTintList = android.content.res.ColorStateList.valueOf(Pal.LIME)
                isChecked = padProfile == p
            })
        }
        profileGroup.setOnCheckedChangeListener { g, id ->
            if (!syncingRadios) setProfile(g.findViewById<View>(id).tag as PadProfile)
        }
        left.addView(profileGroup)
        left.addView(PillButton(this, s("pad_setup")).apply { setOnClickListener { openRemap() } }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        legendText = label("", 12f, Pal.LIGHT_GREY)
        right.addView(legendText)
        if (BuildConfig.DEBUG) {
            right.addView(heading(s("sec_pad_test")))
            padText = label("", 12f, Pal.LIGHT_GREY).also { right.addView(it) }
        }
        refreshPadText()
        padMain = twoCols(left, right)
        remapPanel = buildRemapPanel().apply { visibility = View.GONE }
        return FrameLayout(this).apply {
            addView(padMain, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(remapPanel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion() = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }

    private fun aboutTab(): View {
        val left = column(); val right = column()
        left.addView(heading(s("sec_info")))
        infoText = label("", 13f, Pal.WHITE)
        left.addView(infoText)
        right.addView(heading(s("sec_app")))
        right.addView(label(s("about_version", appVersion())))
        right.addView(label(s("about_license")).apply { setPadding(0, dp(6), 0, 0) })
        right.addView(label(SOURCE_URL, 14f, Pal.LIME).apply {
            setPadding(0, dp(10), 0, dp(10)); paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) } catch (_: Exception) {} }
        })
        refreshInfo()
        return twoCols(left, right)
    }

    private fun selectTab(t: Tab, force: Boolean = false) {
        if (t == tab && !force) return
        if (tab == Tab.GAMEPAD && t != Tab.GAMEPAD && remapOpen) closeRemap()
        if (tab == Tab.DRONES && t != Tab.DRONES) stopScanReceiver()
        tab = t
        tabViews.forEach { (k, v) -> v.visibility = if (k == t) View.VISIBLE else View.GONE }
        tabButtons.forEach { (k, b) ->
            b.setTextColor(if (k == t) Pal.INK else Pal.WHITE)
            b.background = shape(if (k == t) Pal.LIME else Pal.PANEL, dp(16).toFloat())
        }
        if (t == Tab.DRONES) startPicker()
        if (t == Tab.ABOUT) refreshInfo()
    }

    private fun openSettings(t: Tab = tab) {
        closePanels()
        settingsOpen = true
        settingsOverlay.visibility = View.VISIBLE
        selectTab(t, force = true)
    }

    private fun closeSettings() {
        if (remapOpen) closeRemap()
        stopScanReceiver()
        settingsOpen = false
        settingsOverlay.visibility = View.GONE
    }

    private fun toggleSettings() { if (settingsOpen) closeSettings() else openSettings() }

    private fun refreshInfo() {
        if (!::infoText.isInitialized) return
        val st = droneState
        fun v(x: Any?) = x?.toString() ?: "?"
        infoText.text = listOf(
            s("info_name", v(st.name)),
            s("info_firmware", v(st.softwareVersion)),
            s("info_hardware", v(st.hardwareVersion)),
            s("info_serial", v(st.serial)),
            s("info_battery", st.battery?.let { "$it %" } ?: "?"),
            s("info_wifi", st.wifiRssi?.let { "$it dBm" } ?: "?"),
            s("info_speed", DisplayText.realSpeed(st.realSpeed)),
            s("info_posture", postureName()),
            s("info_alert", DisplayText.alertName(st.alert, texts) ?: "?"),
            s("info_volume", st.audioVolume?.let { "$it %" } ?: "?"),
            s("info_wifi_band", st.wifiBand?.let { b -> "${s(if (b == 1) "band_5" else "band_24")} / ${st.wifiChannel}" } ?: "?"),
            s("info_link", st.linkQuality?.let { "$it/5" } ?: "?"),
            s("info_video", st.videoEnabled?.let { s(if (it != 0) "val_on" else "val_off") } ?: "?"),
        ).joinToString("\n")
    }

    private fun postureName(): String =
        DisplayText.postureName(droneState.posture, texts) ?: s(DroneStateDecoder.POSTURE_KEYS[posture])

    private fun onDroneState(ds: DroneState) {
        if (ds.disconnected && state == ConnState.CONNECTED) {
            Log.w(TAG, "the drone sent 0/1/0 Disconnection")
            droneState = ds.copy(disconnected = false)
            rumble(RumbleLogic.onLinkLost())
            dispatch(ConnEvent.DroneShutdown)
            return
        }
        RumbleLogic.onState(droneState, ds).firstOrNull()?.let(::rumble)
        ds.audioVolume?.let { if (!volBar.isPressed) volBar.progress = it }
        ds.name?.let { n ->
            if (!nameField.hasFocus() && nameField.text.toString() != n) nameField.setText(n)
            if (pendingName != null && n == pendingName) { toast(s("name_changed", n)); pendingName = null }
        }
        droneState = ds
        ds.posture?.let { if (it in Protocol.POSTURES.indices) posture = it }
        refreshHud()
        if (settingsOpen && tab == Tab.ABOUT) refreshInfo()
    }

    // --- Photo / video -------------------------------------------------------------
    private fun setVideoOn(on: Boolean) {
        if (on == videoOn) return
        Log.i(TAG, "video stream: ${if (on) "enabled" else "disabled"}")
        videoOn = on
        prefs.edit().putBoolean("video_on", on).apply()
        if (!on) { stopRecording(); latestJpeg = null }
        sendAsync { it.video(on) }
        refreshVideoUi()
    }

    private fun refreshVideoUi() {
        camNote.visibility = if (videoOn) View.GONE else View.VISIBLE
        if (!videoOn) video.setImageDrawable(null)
        btnCam.dim = !videoOn
        btnRec.dim = !videoOn
    }

    private fun takePhoto() {
        if (!videoOn) return toast(s("toast_cam_off"))
        val jpeg = latestJpeg ?: return toast(s("toast_no_frame"))
        Thread {
            try {
                PhotoSaver.save(this, jpeg)
                runOnUiThread { toast(s("toast_photo_saved")) }
            } catch (e: Exception) {
                runOnUiThread { toast(s("toast_photo_fail", e.message ?: e.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun toggleRecording() {
        Log.i(TAG, "toggleRecording: entered (recording=${recorder != null}, latestJpeg=${latestJpeg?.size}, resumed=$resumed, state=$state)")
        if (recorder != null) { Log.i(TAG, "toggleRecording: stop requested"); stopRecording(); return }
        if (!videoOn) return toast(s("toast_cam_off"))
        val jpeg = latestJpeg
        if (jpeg == null) {
            Log.w(TAG, "toggleRecording: exit, no frame received")
            toast(s("toast_no_frame_connect"))
            return
        }
        try {
            recorder = VideoRecorder.start(this, jpeg,
                onDone = { runOnUiThread { Log.i(TAG, "toggleRecording: video finished"); toast(s("toast_rec_saved")) } },
                onError = { msg -> runOnUiThread { Log.e(TAG, "toggleRecording: recorder error: $msg"); toast(s("toast_rec_fail", msg)); recordingStopped() } })
        } catch (e: Exception) {
            Log.e(TAG, "toggleRecording: VideoRecorder.start failed", e)
            toast(s("toast_rec_impossible", e.message ?: e.javaClass.simpleName))
            return
        }
        Log.i(TAG, "toggleRecording: recording started")
        recStart = System.currentTimeMillis()
        btnRec.ring = Pal.RED
        recPill.visibility = View.VISIBLE
        tickRec()
    }

    private fun tickRec() {
        if (recorder == null) return
        val sec = (System.currentTimeMillis() - recStart) / 1000
        recPill.text = s("rec_label", sec / 60, sec % 60)
        recPill.postDelayed(::tickRec, 500)
    }

    private fun stopRecording() {
        recorder?.stop()
        recordingStopped()
    }

    private fun recordingStopped() {
        recorder = null
        btnRec.ring = 0
        recPill.visibility = View.GONE
    }

    private fun toast(m: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, m, Toast.LENGTH_SHORT).also { it.show() }
    }

    // --- Gamepad ------------------------------------------------------------------
    private fun applyControl() {
        val d = drone ?: return
        d.speed = ControlMapping.map(GamepadLogic.combine(padS, touchS), maxSpeed, expo)
        d.turn = ControlMapping.map(GamepadLogic.combine(padT, touchT), turnSens, expo)
    }

    private fun isPad(dev: InputDevice?): Boolean {
        val s = dev?.sources ?: return false
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun scanPads() {
        padCount = InputDevice.getDeviceIds().count { isPad(InputDevice.getDevice(it)) }
        Log.i(TAG, "gamepad: $padCount connected")
        refreshHud()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            if (resumed) handleAxes(ev) else { padS = 0f; padT = 0f }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private var rawZ = 0f
    private var rawRX = 0f
    private var lastRes: GamepadLogic.AxesResult? = null

    private fun handleAxes(ev: MotionEvent) {
        val dev = ev.device
        val src = ev.source
        fun flat(axis: Int) = dev?.getMotionRange(axis, src)?.flat ?: 0f
        val zRight = dev?.getMotionRange(MotionEvent.AXIS_Z, src) != null
        val rx = if (zRight) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
        val ry = if (zRight) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
        rawZ = ev.getAxisValue(MotionEvent.AXIS_Z)
        rawRX = ev.getAxisValue(MotionEvent.AXIS_RX)
        val values = mapOf(
            PadAxis.X to ev.getAxisValue(MotionEvent.AXIS_X),
            PadAxis.Y to ev.getAxisValue(MotionEvent.AXIS_Y),
            PadAxis.RIGHT_X to ev.getAxisValue(rx),
            PadAxis.RY to ev.getAxisValue(ry),
            PadAxis.R2 to maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)),
            PadAxis.L2 to maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)),
            PadAxis.HAT_X to ev.getAxisValue(MotionEvent.AXIS_HAT_X),
            PadAxis.HAT_Y to ev.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        if (remapOpen) {
            // Setup screen: no actions, the drone does not move.
            padS = 0f; padT = 0f; applyControl()
            capture?.onAxes(values)?.let(::onCaptured)
            lastValues = values
            return
        }
        lastValues = values
        val flats = mapOf(PadAxis.X to flat(MotionEvent.AXIS_X), PadAxis.Y to flat(MotionEvent.AXIS_Y),
            PadAxis.RIGHT_X to flat(rx), PadAxis.RY to flat(ry))
        val res = pad.onAxes(AxisSnapshot(values, flats), System.currentTimeMillis())
        applyPadResult(res)
        res.actions.forEach(::doPadAction)
    }

    private fun applyPadResult(res: GamepadLogic.AxesResult) {
        lastRes = res
        padS = res.speed
        padT = res.turn
        applyControl()
        refreshPadText()
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val fromPad = ev.isFromSource(InputDevice.SOURCE_GAMEPAD) || ev.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (!fromPad || ev.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(ev)
        // Consume everything (down and up) before the views: A must not "click" the focused button.
        if (!resumed || ev.action == KeyEvent.ACTION_MULTIPLE) return true
        val down = ev.action == KeyEvent.ACTION_DOWN
        if (remapOpen) {
            if (down && ev.repeatCount == 0) capture?.onKey(ev.keyCode)?.let(::onCaptured)
            return true
        }
        val now = System.currentTimeMillis()
        if (pad.onTriggerKey(ev.keyCode, down)) {
            val res = pad.current(now)
            applyPadResult(res)
            res.actions.forEach(::doPadAction)
        }
        if (down && ev.repeatCount == 0) {
            lastButton = KeyEvent.keyCodeToString(ev.keyCode)
            refreshPadText()
            pad.onKey(ev.keyCode, now)?.let(::doPadAction)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (remapOpen) { if (capture != null) cancelCapture() else closeRemap(); return }
        if (settingsOpen) { closeSettings(); return }
        if (panelOpen()) { closePanels(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun speedSourceName(src: SpeedSource) = when {
        src == SpeedSource.STICK -> s("src_stick")
        src == SpeedSource.TRIGGERS -> s("src_triggers")
        touchS != 0f -> s("src_touch")
        else -> "—"
    }

    private fun refreshPadText() {
        if (!::padStatus.isInitialized) return
        val r = lastRes
        val m = pad.mapping
        padStatus.text = s(if (padCount > 0) "pad_detected" else "pad_none") + " · " + s("pad_profile_line", s(padProfile.labelKey))
        if (::legendText.isInitialized) legendText.text = PadLabels.legend(m, texts)
        val pt = padText ?: return // debug builds only
        val sb = StringBuilder()
        if (r == null) sb.append(s("pad_move")).append("\n") else {
            for (a in AxisAction.values()) {
                val rd = r.readings[a] ?: continue
                sb.append(s("pad_axis_line", s(a.labelKey), PadLabels.axis(m.axes[a], texts), rd.raw, rd.value)).append("\n")
            }
            sb.append(s("pad_triggers", r.r2, r.l2, rawZ, rawRX)).append("\n")
            sb.append(s("pad_speed_by", speedSourceName(r.source))).append("\n")
            sb.append(s("pad_commands",
                ControlMapping.map(GamepadLogic.combine(padS, touchS), maxSpeed, expo),
                ControlMapping.map(GamepadLogic.combine(padT, touchT), turnSens, expo))).append("\n")
        }
        sb.append(s("pad_last", lastButton))
        pt.text = sb
    }

    // --- Gamepad profiles and setup -----------------------------------
    private fun savePad() {
        prefs.edit().putString("pad_profile", padProfile.name).putString("pad_perso", persoMapping.serialize()).apply()
    }

    private fun syncRadios() {
        syncingRadios = true
        for (i in 0 until profileGroup.childCount) {
            val b = profileGroup.getChildAt(i) as android.widget.RadioButton
            b.isChecked = b.tag == padProfile
        }
        syncingRadios = false
    }

    private fun setProfile(p: PadProfile) {
        padProfile = p
        pad.mapping = if (p == PadProfile.PERSO) persoMapping else PadProfiles.preset(p)
        padS = 0f; padT = 0f; lastRes = null; applyControl()
        savePad()
        refreshPadText()
        if (::remapList.isInitialized) rebuildRemap()
    }

    /** Any edit starts from the active mapping (copied) and switches to CUSTOM. */
    private fun editMapping(m: PadMapping) {
        persoMapping = m
        padProfile = PadProfile.PERSO
        pad.mapping = m
        savePad()
        syncRadios()
        refreshPadText()
        rebuildRemap()
    }

    private fun openRemap() {
        remapOpen = true
        padS = 0f; padT = 0f; applyControl()
        padMain.visibility = View.GONE
        remapPanel.visibility = View.VISIBLE
        remapStatus.text = s("remap_hint")
        rebuildRemap()
    }

    private fun closeRemap() {
        cancelCapture()
        remapOpen = false
        remapPanel.visibility = View.GONE
        padMain.visibility = View.VISIBLE
    }

    private val captureTimeout = Runnable {
        if (capture != null) { cancelCapture(); toast(s("remap_timeout")) }
    }

    private fun startCapture(target: Owner) {
        captureTarget = target
        capture = CaptureSession(wantAxis = target is Owner.Axis, baseline = lastValues)
        remapStatus.text = s(if (target is Owner.Axis) "remap_capture_axis" else "remap_capture_button", PadLabels.owner(target, texts))
        remapPanel.removeCallbacks(captureTimeout)
        remapPanel.postDelayed(captureTimeout, 8000)
    }

    private fun cancelCapture() {
        capture = null; captureTarget = null
        remapPanel.removeCallbacks(captureTimeout)
        remapStatus.text = s("remap_hint")
    }

    private fun onCaptured(c: Captured) {
        val target = captureTarget ?: return
        Log.i(TAG, "capture: $target <- $c")
        cancelCapture()
        val m = pad.mapping
        val conflict = m.findConflict(target, c)
        if (conflict == null) { editMapping(m.assign(target, c, false)); return }
        AlertDialog.Builder(this)
            .setMessage(s("remap_conflict", PadLabels.owner(conflict, texts)))
            .setPositiveButton(s("btn_swap")) { _, _ -> editMapping(m.assign(target, c, true)) }
            .setNegativeButton(s("btn_cancel"), null)
            .show()
    }

    private fun buildRemapPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(PillButton(context, "‹ " + s("btn_done"), accent = true).apply { setOnClickListener { closeRemap() } })
            addView(TextView(context).apply { text = s("remap_title"); setTextColor(Pal.WHITE); textSize = 18f; setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        remapStatus = TextView(this).apply { setTextColor(Pal.LIME); setPadding(0, dp(6), 0, dp(6)) }
        col.addView(remapStatus)
        remapList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(remapList)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun starter(labelKey: String, presetLabelKey: String, preset: PadMapping) = addView(PillButton(context, s(labelKey)).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(s("remap_overwrite_msg", s(presetLabelKey)))
                        .setPositiveButton(s("btn_overwrite")) { _, _ -> editMapping(preset) }
                        .setNegativeButton(s("btn_cancel"), null).show()
                }
            })
            starter("remap_from_joysticks", "profile_joysticks", Presets.JOYSTICKS)
            starter("remap_from_course", "profile_course", Presets.COURSE)
        })
        return ScrollView(this).apply { addView(col) }
    }

    private fun rebuildRemap() {
        if (!::remapList.isInitialized) return
        val m = pad.mapping
        remapList.removeAllViews()
        fun header(t: String) = remapList.addView(TextView(this).apply {
            text = t; setTextColor(Pal.WHITE); textSize = 16f; setPadding(0, dp(12), 0, dp(4))
        })
        fun row(owner: Owner, name: String, binding: String, axisAction: AxisAction?) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = shape(Pal.PANEL, dp(12).toFloat()); setPadding(dp(10), dp(4), dp(8), dp(4))
                isClickable = true
                setOnClickListener { startCapture(owner) }
            }
            line.addView(TextView(this).apply {
                text = "$name\n$binding"; setTextColor(Pal.WHITE); isClickable = false
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (axisAction != null) line.addView(PillButton(this, s("btn_invert")).apply {
                isEnabled = m.axes[axisAction] != null
                setOnClickListener { editMapping(pad.mapping.invert(axisAction)) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            line.addView(PillButton(this, s("btn_none")).apply { setOnClickListener { editMapping(pad.mapping.unbind(owner)) } })
            remapList.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        header(s("remap_moves"))
        AxisAction.values().forEach { row(Owner.Axis(it), s(it.labelKey), PadLabels.axis(m.axes[it], texts), it) }
        header(s("remap_actions"))
        PadAction.values().forEach { row(Owner.Button(it), PadLabels.actionName(it, texts), PadLabels.buttons(m.buttons[it], texts), null) }
    }

    private fun showAnimNote() {
        val n = Protocol.ANIMATIONS[pad.animIndex]
        showNote(s("anim_note", pad.animIndex + 1, Protocol.ANIMATIONS.size, s("trick_${n.second}")))
    }

    private fun doPadAction(a: PadAction) {
        Log.i(TAG, "gamepad: action $a")
        when (a) {
            PadAction.JUMP_LONG -> doJump(false)
            PadAction.JUMP_HIGH -> doJump(true)
            PadAction.PHOTO -> takePhoto()
            PadAction.RECORD -> toggleRecording()
            PadAction.ANIM_PREV, PadAction.ANIM_NEXT -> showAnimNote()
            PadAction.ANIM_LAUNCH -> {
                val id = Protocol.ANIMATIONS[pad.animIndex].first
                showAnimNote()
                launchAnimation(id)
            }
            PadAction.POSTURE_NEXT, PadAction.POSTURE_PREV -> if (drone != null) {
                val n = Protocol.POSTURES.size
                posture = (posture + if (a == PadAction.POSTURE_NEXT) 1 else n - 1) % n
                val p = posture
                sendAsync { it.posture(p) }
                refreshHud()
            }
            PadAction.TOGGLE_CONNECT -> if (state == ConnState.DISCONNECTED) connect() else disconnect()
            PadAction.TOGGLE_SETTINGS -> toggleSettings()
        }
    }

    /** UDP sends must not happen on the main thread. */
    private fun sendAsync(f: (JumpingDrone) -> Unit) {
        val d = drone ?: return
        Thread { try { f(d) } catch (_: Exception) {} }.start()
    }

    /** Refreshes the status pill, the line under it and the enabled state of the action bar. */
    private fun refreshHud() {
        if (!::pill.isInitialized) return
        val connected = state == ConnState.CONNECTED
        if (rootW > 0 && (state == ConnState.DISCONNECTED) != trayDisconnected) layoutTray()
        if (trying()) {
            if (!blinking) { blinking = true; eyeBlink.run() }
        } else {
            blinking = false
            mainHandler.removeCallbacks(eyeBlink)
            pillEye.setImageResource(if (connected) R.drawable.ic_eye_on else R.drawable.ic_eye_off)
        }
        pillName.text = droneState.name ?: ssid.ifEmpty { s("drone_none") }
        val sev = DisplayText.batterySeverity(droneState)
        pillBattery.visibility = if (connected || droneState.battery != null) View.VISIBLE else View.GONE
        val bat = (droneState.battery?.let { "$it %" } ?: "?") + if (sev > 0) " " + s(if (sev == 2) "battery_critical" else "battery_low") else ""
        pillBattery.text = bat
        pillBattery.setTextColor(when (sev) { 2 -> Pal.RED; 1 -> Pal.ORANGE; else -> Pal.WHITE })
        pillBattery.setTypeface(null, if (sev == 2) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        pillPad.visibility = if (padCount > 0) View.VISIBLE else View.GONE
        pillKid.visibility = if (kid.active) View.VISIBLE else View.GONE
        val note = listOfNotNull(errorText, transientNote).joinToString("\n")
        noteView.text = note
        noteView.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        for (b in listOf(btnCam, btnRec, btnVol, btnJl, btnJh, btnMoves)) b.on = connected
        if (!connected && panelOpen()) closePanels()
        renameBox.visibility = if (connected) View.VISIBLE else View.GONE
        renameBtn.isEnabled = connected
    }

    // --- Drone picker ----------------------------------------------------------------
    private fun saveDrones(list: List<String>) {
        drones = list
        prefs.edit().putString("drones", SavedDrones.serialize(list)).apply()
        refreshHud()
        if (::pickerList.isInitialized) rebuildPicker()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Permissions needed to scan: nearby devices (Android 13+) and location (fine or coarse, which Android also requires). */
    private fun missingScanPermissions(): Array<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.NEARBY_WIFI_DEVICES")) l.add("android.permission.NEARBY_WIFI_DEVICES")
        if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) l.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (!granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)) l.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return l.toTypedArray()
    }

    private fun hasScanPermission(): Boolean {
        val nearby = Build.VERSION.SDK_INT < 33 || granted("android.permission.NEARBY_WIFI_DEVICES")
        return nearby && (granted(android.Manifest.permission.ACCESS_FINE_LOCATION) || granted(android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun approximateOnly() = !granted(android.Manifest.permission.ACCESS_FINE_LOCATION) && granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun locationOff(): Boolean = try {
        !getSystemService(android.location.LocationManager::class.java).isLocationEnabled
    } catch (_: Exception) { false }

    /** Drones tab shown: list the saved drones and scan for nearby networks. */
    private fun startPicker() {
        scanPermissionDenied = false
        scanSecurityError = false
        rebuildPicker() // shows the rationale line before the system dialog
        if (hasScanPermission()) startScan() else {
            val missing = missingScanPermissions()
            Log.i(TAG, "picker: requesting ${missing.joinToString()}")
            requestPermissions(missing, REQ_SCAN)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_SCAN) return
        if (hasScanPermission()) startScan()
        else { Log.i(TAG, "picker: scan permission denied"); scanPermissionDenied = true }
        rebuildPicker()
    }

    private fun stopScanReceiver() {
        scanReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        scanReceiver = null
    }

    private fun readScanResults(wm: WifiManager) {
        nets = try {
            @Suppress("DEPRECATION")
            DroneList.sortNets(wm.scanResults.map { WifiNet(it.SSID.orEmpty(), it.BSSID.orEmpty(), it.level) })
        } catch (e: SecurityException) {
            Log.e(TAG, "picker: SecurityException in getScanResults (location permission missing or approximate only?)", e)
            scanSecurityError = true; emptyList()
        }
    }

    private fun startScan() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        stopScanReceiver()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                scanning = false
                readScanResults(wm)
                Log.i(TAG, "picker: scan finished, ${nets.size} networks")
                rebuildPicker()
            }
        }
        scanReceiver = rcv
        scanSecurityError = false
        ContextCompat.registerReceiver(this, rcv, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        readScanResults(wm) // cached results first
        @Suppress("DEPRECATION")
        scanning = try { wm.startScan() } catch (e: SecurityException) {
            Log.e(TAG, "picker: SecurityException in startScan", e)
            scanSecurityError = true; false
        }
        Log.i(TAG, "picker: startScan -> $scanning, ${nets.size} cached networks")
        rebuildPicker()
    }

    private fun chooseDrone(name: String) {
        Log.i(TAG, "picker: active drone -> $name")
        saveDrones(SavedDrones.use(drones, name))
        closeSettings()
    }

    private fun rebuildPicker() {
        if (!::pickerList.isInitialized) return
        val locOff = hasScanPermission() && locationOff()
        pickerStatus.text = when {
            scanPermissionDenied -> s("picker_perm_denied")
            !hasScanPermission() -> s("picker_why")
            locOff -> s("picker_location_off")
            scanSecurityError || (approximateOnly() && nets.isEmpty() && !scanning) -> s("picker_precise_hint")
            scanning -> s("picker_scanning")
            else -> ""
        }
        pickerList.removeAllViews()
        if (locOff) pickerList.addView(PillButton(this, s("picker_open_location")).apply {
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        })
        fun header(t: String) = pickerList.addView(heading(t))
        fun row(text: CharSequence, onClick: () -> Unit, extra: (LinearLayout) -> Unit = {}) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = shape(Pal.PANEL, dp(12).toFloat()); setPadding(dp(10), dp(6), dp(8), dp(6)); isClickable = true
                setOnClickListener { onClick() }
            }
            line.addView(TextView(this).apply { this.text = text; setTextColor(Pal.WHITE); textSize = 15f }, LinearLayout.LayoutParams(0, -2, 1f))
            extra(line)
            pickerList.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        if (drones.isNotEmpty()) {
            header(s("picker_saved"))
            drones.forEachIndexed { i, d ->
                row(if (i == 0) s("picker_active_mark", d) else d, { chooseDrone(d) }) { line ->
                    line.addView(PillButton(this, s("picker_remove")).apply { setOnClickListener { saveDrones(SavedDrones.remove(drones, d)) } })
                    line.setOnLongClickListener { saveDrones(SavedDrones.remove(drones, d)); true }
                }
            }
        }
        header(s("picker_nearby"))
        if (hasScanPermission()) {
            if (nets.isEmpty() && !scanning) pickerList.addView(label(s("picker_none_found"), 14f, Pal.GREY))
            for (n in nets) row(n.ssid, { chooseDrone(n.ssid) }) { line ->
                if (n.parrot) line.addView(TextView(this).apply {
                    text = s("picker_badge"); setTextColor(Pal.INK); background = shape(Pal.LIME, dp(10).toFloat()); setPadding(dp(8), dp(2), dp(8), dp(2))
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                line.addView(label(s("picker_signal", n.level), 13f, Pal.LIGHT_GREY))
            }
        }
    }

    // --- Jumps lock, kid mode, rumble, rename -----------------------------------------
    private fun doJump(high: Boolean) {
        if (jumpsDisabled) { toast(s("jumps_disabled_toast")); return }
        if (drone == null) return
        sendAsync { it.jump(high) }
        rumble(RumbleLogic.onJump())
    }

    private fun launchAnimation(id: Int) {
        if (JumpPolicy.blocksAnimation(jumpsDisabled, id)) { toast(s("jumps_disabled_toast")); return }
        sendAsync { it.animation(id) }
    }

    private fun refreshJumpUi() {
        btnJl.dim = jumpsDisabled
        btnJh.dim = jumpsDisabled
    }

    private fun toggleKid() {
        val (k, applied) = kid.toggle(PilotSettings(maxSpeed, jumpsDisabled))
        Log.i(TAG, "kid mode: ${if (k.active) "on" else "off"} -> $applied")
        kid = k
        prefs.edit().putBoolean("kid_active", k.active)
            .putInt("kid_saved_max", k.saved?.maxSpeed ?: 100).putBoolean("kid_saved_jumps", k.saved?.jumpsDisabled ?: false).apply()
        syncingUi = true
        maxSpeed = applied.maxSpeed
        maxSpeedBar.progress = applied.maxSpeed.coerceIn(20, 100) - 20
        jumpsSwitch.isChecked = applied.jumpsDisabled
        syncingUi = false
        jumpsDisabled = applied.jumpsDisabled
        prefs.edit().putInt("max_speed", maxSpeed).putBoolean("jumps_disabled", jumpsDisabled).apply()
        refreshKidUi()
    }

    private fun refreshKidUi() {
        kidBtn.text = s(if (kid.active) "kid_exit" else "kid_enter")
        maxSpeedBar.isEnabled = !kid.active
        jumpsSwitch.isEnabled = !kid.active
        refreshJumpUi()
        refreshHud()
    }

    private fun rumble(r: Rumble) {
        if (!vibrationOn) return
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (!isPad(dev)) continue
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) dev.vibratorManager.defaultVibrator
            else @Suppress("DEPRECATION") dev.vibrator
            if (vib == null || !vib.hasVibrator()) {
                if (noVibratorLogged.add(id)) Log.i(TAG, "gamepad: ${dev.name} has no vibrator")
                continue
            }
            try { vib.vibrate(VibrationEffect.createWaveform(r.timings, -1)) } catch (e: Exception) { Log.w(TAG, "rumble failed", e) }
        }
    }

    private fun renameDrone() {
        val name = nameField.text.toString().trim()
        if (!NameValidator.isValid(name)) { toast(s("name_invalid")); return }
        Log.i(TAG, "rename: sending ProductName \"$name\" (0/2/2)")
        pendingName = name
        sendAsync { it.send(Protocol.productName(name)) }
        // The next connection targets the new SSID: update the saved entry (old name -> new name).
        val old = ssid
        saveDrones(if (old.isEmpty()) SavedDrones.use(drones, name) else SavedDrones.rename(drones, old, name))
        toast(s("name_sent"))
    }

    // --- Connection --------------------------------------------------------------
    private fun setError(e: Throwable?, fallback: String = "") {
        val kind = e?.let { ErrorText.kind(it) } ?: ErrorText.Kind.OTHER
        val m = e?.let { ErrorText.describe(it, texts) } ?: fallback
        errorText = m.take(130).ifEmpty { null }?.let { if (kind != ErrorText.Kind.OTHER || e == null) it else s("err_prefix", it) }
        refreshHud()
    }

    private fun nowMs() = System.currentTimeMillis()

    /** UI thread only. */
    private fun dispatch(e: ConnEvent) {
        val before = state
        val actions = conn.onEvent(e, nowMs())
        if (before != state) Log.i(TAG, "connection: $before -> $state on $e")
        perform(actions)
    }

    private fun perform(actions: List<ConnAction>) {
        for (a in actions) when (a) {
            ConnAction.StartConnect -> requestNetwork()
            is ConnAction.Teardown -> teardown(a.releaseNetwork)
            is ConnAction.ScheduleAttempt -> {
                Log.i(TAG, "reconnect: attempt ${conn.attempt} in ${a.delayMs} ms")
                mainHandler.removeCallbacks(attemptRunnable)
                mainHandler.postDelayed(attemptRunnable, a.delayMs)
            }
            ConnAction.ShowError -> {}
            ConnAction.GiveUp -> { Log.w(TAG, "reconnect: giving up"); errorText = s("err_connection_lost") }
        }
        if (state == ConnState.RECONNECTING) {
            errorText = if (conn.droneShutdown) s("err_reconnecting_shutdown") else s("err_reconnecting", conn.attempt)
        }
        refreshHud()
    }

    private fun teardown(releaseNetwork: Boolean) {
        mainHandler.removeCallbacks(attemptRunnable)
        stopRecording()
        joysticks.reset()
        padS = 0f; padT = 0f
        drone?.let { it.speed = 0; it.turn = 0; stopDrone(it) }
        drone = null
        latestJpeg = null
        if (releaseNetwork) {
            netCallback?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
            netCallback = null
            currentNetwork = null
            netRequestPending = false
        }
    }

    /** User pressed Connect. */
    private fun connect() {
        if (ssid.isEmpty()) { toast(s("drone_none")); openSettings(Tab.DRONES); return }
        if (!applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled) {
            Log.w(TAG, "connect: Wi-Fi is off")
            setError(null, s("err_wifi_off"))
            try { startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI)) } catch (_: Exception) {}
            return
        }
        dispatch(ConnEvent.UserConnect)
    }

    private fun requestNetwork() {
        val name = ssid
        droneState = DroneState()
        errorText = null
        val cm = getSystemService(ConnectivityManager::class.java)
        val vpn = try {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        } catch (_: Exception) { false }
        if (vpn) { Log.w(TAG, "VPN detected: binding to the drone may fail (EPERM)"); errorText = s("err_vpn") }
        refreshHud()
        // If the phone is already on a Wi-Fi network that reaches the drone, use it: no system dialog.
        Thread {
            val direct = findReachableWifi(cm)
            runOnUiThread {
                if (state != ConnState.CONNECTING) return@runOnUiThread
                if (direct != null) startDirect(cm, direct) else startSpecifier(cm, name)
            }
        }.start()
    }

    /** A Wi-Fi network on which the drone's discovery port answers within 1 s, or null. */
    private fun findReachableWifi(cm: ConnectivityManager): Network? {
        val candidates = ArrayList<Network>()
        cm.activeNetwork?.let { if (cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) candidates.add(it) }
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            if (n !in candidates && cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) candidates.add(n)
        }
        for (n in candidates) {
            try {
                java.net.Socket().use { sock ->
                    n.bindSocket(sock)
                    sock.connect(java.net.InetSocketAddress("192.168.2.1", Protocol.DISCOVERY_PORT), 1000)
                }
                Log.i(TAG, "network: $n reaches 192.168.2.1:${Protocol.DISCOVERY_PORT}")
                return n
            } catch (e: Exception) {
                Log.i(TAG, "network: $n does not reach the drone (${e.message})")
            }
        }
        return null
    }

    private fun startDirect(cm: ConnectivityManager, n: Network) {
        Log.i(TAG, "network: already on a Wi-Fi network that reaches the drone ($n), skipping the system dialog")
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // While reconnecting, any Wi-Fi network coming back is worth a try.
                runOnUiThread {
                    if (netCallback === this && state == ConnState.RECONNECTING && network != n) {
                        currentNetwork = network
                        mainHandler.removeCallbacks(attemptRunnable)
                        startAttempt(network)
                    }
                }
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "network: onLost $network (direct)")
                runOnUiThread {
                    if (netCallback !== this) return@runOnUiThread
                    if (currentNetwork == network) currentNetwork = null
                    onLinkLost(null)
                }
            }
        }
        netCallback = cb
        currentNetwork = n
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        startAttempt(n)
    }

    private fun startSpecifier(cm: ConnectivityManager, name: String) {
        // The drone Wi-Fi (open network, no internet) is requested through WifiNetworkSpecifier:
        // the system shows a "connect to …?" dialog and keeps the network alive for the app.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(name).build())
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network: onAvailable $network")
                netRequestPending = false
                if (netCallback !== this) return
                currentNetwork = network
                runOnUiThread {
                    if (netCallback !== this) return@runOnUiThread
                    if (state == ConnState.RECONNECTING) {
                        // The network is back: try right away instead of waiting for the next tick.
                        mainHandler.removeCallbacks(attemptRunnable)
                        startAttempt(network)
                    } else if (state == ConnState.CONNECTING) startAttempt(network)
                }
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "network: onLost $network")
                runOnUiThread {
                    if (netCallback !== this) return@runOnUiThread
                    if (currentNetwork == network) currentNetwork = null
                    onLinkLost(null)
                }
            }
            override fun onUnavailable() {
                Log.w(TAG, "network: onUnavailable")
                netRequestPending = false
                runOnUiThread {
                    if (netCallback === this && state == ConnState.CONNECTING) {
                        setError(null, s("err_wifi_not_found", name))
                        dispatch(ConnEvent.ConnectFailed)
                    }
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                Log.i(TAG, "network: capabilities $network wifi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            }
        }
        netCallback = cb
        Log.i(TAG, "network: requestNetwork ssid=$name")
        // 60 s: the user has to accept the system dialog.
        lastNetworkRequestMs = nowMs()
        netRequestPending = true
        cm.requestNetwork(req, cb, 60_000)
    }

    /** The link dropped (network lost, I/O error, silence). UI thread. */
    private fun onLinkLost(e: Throwable?) {
        val was = state
        if (e != null && state == ConnState.CONNECTING) setError(e)
        else if (state == ConnState.CONNECTING) setError(null, s("err_network_lost"))
        dispatch(ConnEvent.LinkLost)
        if (was == ConnState.CONNECTED) rumble(RumbleLogic.onLinkLost())
    }

    /** Scheduled reconnect tick. UI thread. */
    private fun attemptReconnect() {
        if (state != ConnState.RECONNECTING) return
        val n = currentNetwork
        if (n == null) {
            Log.i(TAG, "reconnect: no network yet")
            // A WifiNetworkSpecifier request is not re-joined by the system once lost: ask again, at most every ~15 s.
            // Never cancel a request still pending: the system dialog would show "Something came up".
            if (!netRequestPending && nowMs() - lastNetworkRequestMs > 15_000) renewNetworkRequest()
            dispatch(ConnEvent.ConnectFailed)
        } else startAttempt(n)
    }

    private var lastNetworkRequestMs = 0L
    @Volatile private var netRequestPending = false

    private fun renewNetworkRequest() {
        Log.i(TAG, "reconnect: renewing the network request")
        lastNetworkRequestMs = nowMs()
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
        netRequestPending = false
        val name = ssid
        Thread {
            val direct = findReachableWifi(cm)
            runOnUiThread {
                if (state != ConnState.RECONNECTING || netCallback != null) return@runOnUiThread
                if (direct != null) startDirect(cm, direct) else startSpecifier(cm, name)
            }
        }.start()
    }

    private fun startAttempt(network: Network) {
        if (!attemptRunning.compareAndSet(false, true)) return
        Thread { try { startDrone(network) } finally { attemptRunning.set(false) } }.start()
    }

    private fun trying() = state == ConnState.CONNECTING || state == ConnState.RECONNECTING

    private fun startDrone(network: Network) {
        val d = JumpingDrone(bindTcp = { network.bindSocket(it) }, bindUdp = { network.bindSocket(it) })
        d.onState = { st -> runOnUiThread { onDroneState(st) } }
        d.onFrame = ::onFrame
        d.onError = { e ->
            // After a network loss or a deliberate stop, I/O errors are expected.
            Log.w(TAG, "drone onError (ignored if already stopped)", e)
            runOnUiThread { if (drone === d) onLinkLost(e) }
        }
        var attempt = 0
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            attempt++
            try {
                Log.i(TAG, "startDrone: attempt $attempt")
                d.connect()
                d.video(videoOn)
                break
            } catch (e: Exception) {
                Log.e(TAG, "startDrone: connection failed (attempt $attempt)", e)
                val giveUp = !ErrorText.isRetryable(e) || System.currentTimeMillis() + 1500 > deadline || !trying()
                if (giveUp) {
                    runOnUiThread {
                        if (state == ConnState.CONNECTING) setError(e)
                        dispatch(ConnEvent.ConnectFailed)
                    }
                    return
                }
                if (state == ConnState.CONNECTING) runOnUiThread { errorText = s("err_drone_starting", attempt); refreshHud() }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return }
                if (!trying()) return
            }
        }
        Log.i(TAG, "startDrone: connected")
        runOnUiThread {
            if (!trying()) { stopDrone(d); return@runOnUiThread }
            drone = d
            errorText = null
            dispatch(ConnEvent.ConnectSucceeded)
            applyControl()
        }
    }

    /** Receive thread: never block. */
    private fun onFrame(jpeg: ByteArray) {
        if (!resumed || !videoOn) return // in the background or camera off: no decoding, display or recording
        latestJpeg = jpeg
        recorder?.offer(jpeg)
        if (decoding) return // skip frames when decoding falls behind
        decoding = true
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        runOnUiThread { decoding = false; if (resumed && bmp != null) video.setImageBitmap(bmp) }
    }

    private fun stopDrone(d: JumpingDrone) {
        Thread { try { d.close() } catch (_: Exception) {} }.start()
    }

    private fun disconnect(keepError: Boolean = false) {
        Log.i(TAG, "disconnect (state $state)")
        if (!keepError) errorText = null
        dispatch(ConnEvent.UserDisconnect)
    }

    override fun onResume() {
        super.onResume()
        if (pickerOpen) { rebuildPicker(); if (hasScanPermission() && !locationOff()) startScan() }
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        // The connection stays open (the PCMD loop keeps sending zeros); only the activity is paused.
        resumed = false
        padS = 0f; padT = 0f
        joysticks.reset() // speed = turn = 0 via onChange
        drone?.let { it.speed = 0; it.turn = 0 }
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener)
        // Even when not finishing, the activity is recreated without a drone reference, so tear down anyway.
        stopScanReceiver()
        dispatch(ConnEvent.Finishing)
    }
}
