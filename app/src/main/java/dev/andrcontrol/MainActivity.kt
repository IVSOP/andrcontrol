package dev.andrcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val mixer get() = (application as MixerApplication).mixer
    private val handler = Handler(Looper.getMainLooper())
    private var syncing = false
    private var startingBoost = false
    private lateinit var percentText: TextView
    private lateinit var appliedText: TextView
    private lateinit var mediaSlider: PercentSlider
    private lateinit var muteButton: TextView
    private lateinit var boostSwitch: Switch
    private lateinit var boostStatus: TextView
    private lateinit var boostDetail: TextView
    private lateinit var statusPill: TextView
    private lateinit var outputName: TextView
    private lateinit var outputDetail: TextView
    private lateinit var playerList: LinearLayout
    private lateinit var sampleButton: TextView
    private lateinit var feedback: TextView
    private val streamRows = mutableListOf<StreamRow>()
    private val presetButtons = linkedMapOf<Int, TextView>()
    private var playerSignature = ""
    private val listener: () -> Unit = { render() }
    private val poll = object : Runnable {
        override fun run() {
            mixer.refresh()
            handler.postDelayed(this, 750)
        }
    }

    private data class StreamRow(val stream: Int, val value: TextView, val slider: PercentSlider)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildScreen()
        mixer.acceptSession(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mixer.acceptSession(intent)
    }

    override fun onStart() {
        super.onStart()
        mixer.addListener(listener)
        render()
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        mixer.removeListener(listener)
        // The test sound belongs to this screen; audio effects belong to the service.
        mixer.stopTestSound()
        super.onStop()
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.background) }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val holder = FrameLayout(this)
        scroll.addView(holder, ViewGroup.LayoutParams(-1, -2))
        val column = vertical().apply { setPadding(dp(22), dp(20), dp(22), dp(28)) }
        // Comfortable on phones and centered on tablets / landscape displays.
        val availableWidth = resources.displayMetrics.widthPixels
        holder.addView(column, FrameLayout.LayoutParams(minOf(availableWidth, dp(600)), -2, Gravity.CENTER_HORIZONTAL))

        val header = horizontal()
        val brandIcon = MixerIcon(this, "mixer").apply {
            background = shape(Palette.darkMint, 14)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(brandIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val brand = vertical().apply { setPadding(dp(12), 0, 0, 0) }
        brand.addView(label("AndrControl", 19, Palette.text, true))
        brand.addView(label("A little more volume control", 12, Palette.secondary).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val help = iconButton("help", "How volume and boost work") { showHelp() }
        header.addView(help, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header)

        val titleRow = horizontal().apply { setPadding(0, dp(30), 0, dp(18)) }
        titleRow.addView(label("Volume control", 28, Palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = label("STANDARD", 10, Palette.secondary, true).apply {
            letterSpacing = .06f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = shape(Palette.raised, 20)
        }
        titleRow.addView(statusPill)
        column.addView(titleRow)

        val output = horizontal().apply {
            background = ripple(Palette.surface, 18)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "Connected audio output. Open Android sound settings"
            setOnClickListener { openSoundSettings() }
        }
        output.addView(MixerIcon(this, "headphones"), LinearLayout.LayoutParams(dp(24), dp(24)))
        val outputLabels = vertical().apply { setPadding(dp(13), 0, dp(6), 0) }
        outputName = label("This device", 14, Palette.text, true)
        outputDetail = label("Built-in audio", 11, Palette.secondary).apply { setPadding(0, dp(4), 0, 0) }
        outputLabels.addView(outputName)
        outputLabels.addView(outputDetail)
        output.addView(outputLabels, LinearLayout.LayoutParams(0, -2, 1f))
        output.addView(MixerIcon(this, "chevron", Palette.secondary), LinearLayout.LayoutParams(dp(18), dp(18)))
        column.addView(output)
        gap(column, 14)

        val media = card()
        val mediaHeader = horizontal()
        mediaHeader.addView(MixerIcon(this, "music"), LinearLayout.LayoutParams(dp(20), dp(20)))
        mediaHeader.addView(label("MEDIA VOLUME", 11, Palette.secondary, true).apply {
            letterSpacing = .12f
            setPadding(dp(9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        muteButton = smallButton("Mute") { setMedia(if (mixer.mediaPercent == 0) mixer.unmutePercent else 0) }
        mediaHeader.addView(muteButton)
        media.addView(mediaHeader)

        val numberRow = horizontal().apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        numberRow.addView(iconButton("minus", "Decrease media volume by one percent") { setMedia(mixer.mediaPercent - 1) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        percentText = label("0%", 70, Palette.mint, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(38, 70, 1, TypedValue.COMPLEX_UNIT_SP)
            isClickable = true
            isFocusable = true
            background = ripple(ColorTransparent, 18)
            setPadding(dp(4), dp(14), dp(4), dp(8))
            setOnClickListener { enterPercent("Media volume", mixer.mediaPercent, 200) { setMedia(it) } }
        }
        numberRow.addView(percentText, LinearLayout.LayoutParams(0, -2, 1f))
        numberRow.addView(iconButton("plus", "Increase media volume by one percent") { setMedia(mixer.mediaPercent + 1) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        media.addView(numberRow)
        appliedText = label("Tap the percentage to enter a value", 12, Palette.secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(8))
        }
        media.addView(appliedText)
        mediaSlider = PercentSlider(this).apply {
            contentDescription = "Media volume, from zero to two hundred percent"
            setOnSeekBarChangeListener(sliderListener { setMedia(it) })
        }
        media.addView(mediaSlider, LinearLayout.LayoutParams(-1, dp(56)))
        val scale = horizontal().apply { setPadding(dp(8), 0, dp(8), dp(18)) }
        listOf("0%", "100%", "200%").forEachIndexed { i, value ->
            scale.addView(label(value, 11, if (i == 2) Palette.amber else Palette.secondary).apply {
                gravity = when (i) { 0 -> Gravity.START; 1 -> Gravity.CENTER; else -> Gravity.END }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        media.addView(scale)
        val presets = horizontal()
        listOf(1, 3, 5, 100).forEachIndexed { index, value ->
            val button = label("$value%", 13, Palette.text, true).apply {
                gravity = Gravity.CENTER
                background = ripple(Palette.raised, 11)
                minimumHeight = dp(46)
                contentDescription = "Set media volume to $value percent"
                isClickable = true
                isFocusable = true
                setOnClickListener { setMedia(value) }
            }
            presetButtons[value] = button
            presets.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        media.addView(presets)
        column.addView(media)
        gap(column, 14)

        val boost = card()
        val boostRow = horizontal()
        boostRow.addView(MixerIcon(this, "bolt", Palette.amber).apply {
            background = shape(Palette.raised, 12)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val boostLabels = vertical().apply { setPadding(dp(12), 0, dp(8), 0) }
        boostLabels.addView(label("Fine volume & boost", 15, Palette.text, true))
        boostStatus = label("Precise control is off", 12, Palette.secondary).apply { setPadding(0, dp(5), 0, 0) }
        boostLabels.addView(boostStatus)
        boostRow.addView(boostLabels, LinearLayout.LayoutParams(0, -2, 1f))
        boostSwitch = Switch(this).apply {
            contentDescription = "Enable fine volume and boost"
            minimumHeight = dp(48)
            minimumWidth = dp(48)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Palette.mint, Palette.secondary))
            trackTintList = ColorStateList.valueOf(Palette.border)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    if (checked) enableBoost() else {
                        startingBoost = false
                        stopService(Intent(this@MainActivity, BoostService::class.java))
                        mixer.disableBoost()
                    }
                }
            }
        }
        boostRow.addView(boostSwitch)
        boost.addView(boostRow)
        boostDetail = label("Use 1–6% to go below the first hardware step. Fine volume reduces the signal instead of rounding up to 7%.", 12, Palette.secondary).apply {
            setPadding(0, dp(15), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        boost.addView(boostDetail)
        val boostActions = horizontal().apply { setPadding(0, dp(8), 0, 0) }
        boostActions.addView(smallButton("Compatibility") { showCompatibility() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        sampleButton = smallButton("▷  Test sound") { mixer.toggleTestSound() }
        boostActions.addView(sampleButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        boost.addView(boostActions)
        column.addView(boost)

        feedback = label("", 12, Palette.amber).apply { setPadding(dp(4), dp(12), dp(4), 0) }
        column.addView(feedback)

        sectionHeader(column, "System mixer", "Android levels")
        val system = card()
        addStream(system, AudioManager.STREAM_RING, "Ringtone", "bell")
        divider(system)
        addStream(system, AudioManager.STREAM_NOTIFICATION, "Notifications", "bell")
        divider(system)
        addStream(system, AudioManager.STREAM_ALARM, "Alarm", "alarm")
        system.addView(label("Some phones link ringtone and notification levels.", 11, Palette.secondary).apply {
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        column.addView(system)

        sectionHeader(column, "Connected players", "Audio effects")
        playerList = card()
        column.addView(playerList)
        val footer = smallButton("How does fine volume work?  ↗") { showHelp() }
        column.addView(footer, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        setContentView(root)
    }

    private fun addStream(parent: LinearLayout, stream: Int, title: String, icon: String) {
        val row = horizontal().apply { setPadding(0, dp(4), 0, 0) }
        row.addView(MixerIcon(this, icon, Palette.secondary), LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(label(title, 14, Palette.text).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = smallButton("0%") {
            enterPercent(title, mixer.readPercent(stream), 100) { mixer.setStreamPercent(stream, it) }
        }.apply { contentDescription = "Enter $title volume percentage" }
        row.addView(value, LinearLayout.LayoutParams(dp(64), dp(44)))
        parent.addView(row)
        val slider = PercentSlider(this, 100).apply {
            contentDescription = "$title volume, from zero to one hundred percent"
            setOnSeekBarChangeListener(sliderListener { mixer.setStreamPercent(stream, it) })
        }
        parent.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        streamRows.add(StreamRow(stream, value, slider))
    }

    private fun setMedia(percent: Int) {
        if ((percent > 100 || mixer.needsAttenuation(percent)) && !mixer.boostEnabled && !startingBoost && !enableBoost()) return
        mixer.setMediaPercent(percent)
    }

    private fun enableBoost(): Boolean {
        return try {
            startingBoost = true
            startForegroundService(Intent(this, BoostService::class.java))
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val prefs = getPreferences(MODE_PRIVATE)
                if (!prefs.getBoolean("notification_asked", false)) {
                    prefs.edit().putBoolean("notification_asked", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
            render()
            true
        } catch (_: RuntimeException) {
            startingBoost = false
            Toast.makeText(this, "Android could not start audio control. Keep the app open and try again.", Toast.LENGTH_LONG).show()
            render()
            false
        }
    }

    private fun render() {
        if (!::percentText.isInitialized) return
        syncing = true
        if (mixer.boostEnabled) startingBoost = false
        val percent = mixer.mediaPercent
        val boosted = percent > 100
        val quiet = mixer.fineRequested
        percentText.text = "$percent%"
        percentText.setTextColor(if (boosted) Palette.amber else Palette.mint)
        percentText.contentDescription = "$percent percent media volume. Tap to enter an exact percentage."
        mediaSlider.progress = percent
        mediaSlider.isEnabled = !mixer.fixedVolume
        if (Build.VERSION.SDK_INT >= 30) mediaSlider.stateDescription = "$percent percent requested"
        muteButton.text = if (percent == 0) "Unmute" else "Mute"
        muteButton.contentDescription = if (percent == 0) "Restore previous media volume" else "Mute media"
        val system = mixer.readPercent(AudioManager.STREAM_MUSIC)
        appliedText.text = when {
            mixer.fixedVolume -> "Fixed-volume output · use device controls"
            quiet && mixer.fineApplied -> "${String.format(Locale.getDefault(), "%.2f", mixer.attenuationDb)} dB below the first hardware step"
            quiet -> "Muted · waiting for output attenuation"
            boosted && mixer.globalAvailable -> "+${gainDb(percent)} dB requested · device effect enabled"
            boosted && mixer.controlledSessionCount > 0 -> "+${gainDb(percent)} dB on compatible players"
            boosted -> "$system% system volume · waiting for a supported player"
            system != percent -> "Android applies $system% · tap above to enter a value"
            else -> "Tap the percentage to enter a value"
        }
        appliedText.setTextColor(if ((boosted && !mixer.boostAvailable) || (quiet && !mixer.fineApplied)) Palette.amber else Palette.secondary)
        statusPill.text = when {
            quiet && mixer.fineApplied -> "QUIET"
            quiet -> "MUTED"
            boosted && mixer.boostAvailable -> "BOOST"
            boosted -> "WAITING"
            else -> "STANDARD"
        }
        statusPill.setTextColor(if (quiet && mixer.fineApplied) Palette.mint else if (boosted || quiet) Palette.amber else Palette.secondary)
        for ((value, button) in presetButtons) {
            val selected = percent == value
            button.setTextColor(if (selected) Palette.background else if (value > 100) Palette.amber else Palette.text)
            button.background = ripple(if (selected) (if (value > 100) Palette.amber else Palette.mint) else Palette.raised, 11)
            button.isSelected = selected
        }
        boostSwitch.isChecked = mixer.boostEnabled || startingBoost
        boostStatus.text = when {
            startingBoost -> "Starting audio control…"
            !mixer.boostEnabled -> "Precise control is off"
            mixer.globalFineAvailable -> "Output attenuation available"
            mixer.globalAvailable -> "Device effect enabled"
            mixer.controlledSessionCount > 0 -> "${mixer.controlledSessionCount} supported session${if (mixer.controlledSessionCount == 1) "" else "s"}"
            else -> "Waiting for a compatible player"
        }
        boostStatus.setTextColor(if (mixer.boostEnabled) Palette.mint else Palette.secondary)
        boostDetail.text = when {
            !mixer.boostEnabled -> "Use 1–6% to go below the first hardware step. Fine volume reduces the signal instead of rounding up to 7%."
            mixer.globalFineAvailable && quiet -> "The shared output effect reduces the signal at hardware step 1. Keep control running for quiet playback in other apps. Stopping control mutes media."
            mixer.globalFineAvailable -> "Output attenuation is ready for quiet playback. Choose 1%, 3%, or 5% below the first hardware step; values above 100% add boost."
            quiet -> "Shared output attenuation is unavailable. Media stays muted to avoid jumping to the loud first hardware step."
            mixer.globalAvailable -> "Your device accepted a shared audio effect. Some apps and Bluetooth paths may bypass it; use Test sound to check."
            mixer.controlledSessionCount > 0 -> "Extra gain is available for the connected players below. Other apps stay at Android’s normal volume."
            else -> "Start or restart playback in a player that supports Android audio effects, or try Test sound. Other apps stay at normal volume."
        }
        sampleButton.text = if (mixer.testPlaying) "□  Stop sound" else "▷  Test sound"
        sampleButton.contentDescription = if (mixer.testPlaying) "Stop test sound" else "Play a quiet four-second test chime"
        val output = mixer.connectedOutput()
        outputName.text = output.first
        outputDetail.text = output.second
        streamRows.forEach {
            val value = mixer.readPercent(it.stream)
            it.value.text = "$value%"
            if (!it.slider.isPressed) it.slider.progress = value
            it.slider.isEnabled = !mixer.fixedVolume
        }
        feedback.text = mixer.message
        feedback.visibility = if (mixer.message == null) View.GONE else View.VISIBLE
        renderPlayers()
        syncing = false
    }

    private fun renderPlayers() {
        val signature = "${mixer.boostEnabled}:${mixer.globalAvailable}:${mixer.globalFineAvailable}:" + mixer.players.joinToString { "${it.id}:${it.effect != null}:${it.attenuator != null}:${it.unavailable}" }
        if (signature == playerSignature) return
        playerSignature = signature
        playerList.removeAllViews()
        if (mixer.players.isEmpty()) {
            val row = horizontal()
            row.addView(MixerIcon(this, "music", Palette.secondary), LinearLayout.LayoutParams(dp(24), dp(24)))
            val empty = vertical().apply { setPadding(dp(13), 0, 0, 0) }
            empty.addView(label("No players connected yet", 14, Palette.text, true))
            empty.addView(label("Compatible players appear when they open an audio-effects session. Try restarting playback.", 12, Palette.secondary).apply {
                setPadding(0, dp(7), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            row.addView(empty, LinearLayout.LayoutParams(0, -2, 1f))
            playerList.addView(row)
        } else mixer.players.forEachIndexed { index, player ->
            if (index > 0) divider(playerList)
            val row = horizontal().apply { setPadding(0, dp(8), 0, dp(8)) }
            row.addView(MixerIcon(this, "music"), LinearLayout.LayoutParams(dp(22), dp(22)))
            val labels = vertical().apply { setPadding(dp(12), 0, 0, 0) }
            labels.addView(label(player.name, 14, Palette.text, true))
            labels.addView(label(when {
                !mixer.boostEnabled -> "Connected · precise control off"
                mixer.globalFineAvailable -> "Shared output attenuation available"
                mixer.globalAvailable -> "Using device effect · check audibility"
                player.attenuator != null -> "Player attenuation available · sessions-only mode"
                player.effect != null -> "Session boost available"
                else -> "This player or device rejected the effect"
            }, 12, Palette.secondary).apply { setPadding(0, dp(5), 0, 0) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            playerList.addView(row)
        }
    }

    private fun enterPercent(title: String, current: Int, maximum: Int, apply: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setText(current.toString())
            selectAll()
            hint = "0–$maximum"
            contentDescription = "$title percentage, zero to $maximum"
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setMessage("Enter a percentage from 0 to $maximum.")
            .setView(container).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        fun commit() {
            val value = input.text.toString().trim().toIntOrNull()
            if (value == null || value !in 0..maximum) input.error = "Enter a whole number from 0 to $maximum"
            else { apply(value); dialog.dismiss() }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { commit() }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { commit(); true } else false
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun showCompatibility() {
        val choices = arrayOf("Automatic · try device effect, then player sessions", "Player sessions only · for device-effect issues")
        AlertDialog.Builder(this).setTitle("Boost compatibility")
            .setSingleChoiceItems(choices, if (mixer.preferGlobal) 0 else 1) { dialog, which ->
                mixer.setGlobalMode(which == 0)
                dialog.dismiss()
            }
            .setNeutralButton("Retry effects") { _, _ -> mixer.retryEffects() }
            .setNegativeButton("Close", null).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("A bit more about volume")
            .setMessage("Android’s first hardware step is about 7% on a 15-step phone. Older versions of this app only selected those steps.\n\nFine volume uses a separate signal reduction below that first step. At 1%, the phone stays at hardware step 1 and the signal is reduced by about 16.48 dB. At 3%, the reduction is about 6.94 dB. 0% mutes media. The percentages below the first step describe signal amplitude relative to that step, not measured acoustic loudness.\n\nThe shared output effect can reduce audio from other apps on supported routes. Some protected, offloaded, or vendor audio paths can bypass shared effects. Use Test sound, then check playback in your own apps. If shared attenuation is unavailable, Automatic mode keeps quiet requests muted. Sessions-only mode applies to supported players only.\n\nKeep precise control running while using quiet playback. Stopping it mutes media before removing the reduction, so playback does not jump louder. Android can remove effects when a process is killed.\n\nHigher normal levels still use Android’s hardware steps. Above 100%, LoudnessEnhancer adds up to +6.02 dB. Ringtone, notifications, and alarms retain their normal controls; a shared effect may also reduce audio mixed into the same output.\n\nUse low values first. Boost can add distortion.")
            .setPositiveButton("Got it", null).show()
    }

    private fun openSoundSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            .onFailure { Toast.makeText(this, "Sound settings are unavailable on this device.", Toast.LENGTH_SHORT).show() }
    }

    private fun sliderListener(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser && !syncing) change(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) { render() }
    }

    private fun gainDb(percent: Int) = String.format(Locale.getDefault(), "%.2f", VolumeMath.gainMillibels(percent) / 100.0)
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(text: String, size: Int, color: Int = Palette.text, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun card() = vertical().apply {
        background = shape(Palette.surface, 22, Palette.border)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }
    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun ripple(color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x22ffffff), shape(color, radius), shape(-1, radius))
    private fun smallButton(text: String, action: () -> Unit) = label(text, 12, Palette.mint, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = ripple(ColorTransparent, 10)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }
    private fun iconButton(icon: String, description: String, action: () -> Unit) = MixerIcon(this, icon, Palette.secondary).apply {
        contentDescription = description
        background = ripple(ColorTransparent, 24)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(Palette.border) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(5); bottomMargin = dp(9)
        })
    }
    private fun sectionHeader(parent: LinearLayout, title: String, subtitle: String) {
        val row = horizontal().apply { setPadding(dp(2), dp(24), dp(2), dp(13)) }
        row.addView(label(title, 16, Palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(subtitle, 11, Palette.secondary))
        parent.addView(row)
    }
    companion object { private const val ColorTransparent = 0x00000000 }
}
