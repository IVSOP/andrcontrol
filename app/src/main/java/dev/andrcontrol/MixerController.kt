package dev.andrcontrol

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Owns real Android volume and effect handles. All calls run on the main thread. */
class MixerController(private val context: Context) {
    val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("mixer", Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<() -> Unit>()
    private val sessions = linkedMapOf<Int, PlayerSession>()
    private var globalEffect: LoudnessEnhancer? = null
    private var globalAttenuator: DynamicsProcessing? = null
    private var expectedMediaIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    private var lastAudiblePercent = 50
    private var sample: MediaPlayer? = null
    private var sampleFocus: android.media.AudioFocusRequest? = null
    private val stopSample = Runnable { stopTestSound() }
    private var observedVolumes = volumeSnapshot()

    var mediaPercent = readPercent(AudioManager.STREAM_MUSIC)
        private set
    var boostEnabled = false
        private set
    var preferGlobal = preferences.getBoolean("global", true)
        private set
    var testPlaying = false
        private set
    var message: String? = null
        private set

    data class PlayerSession(
        val id: Int,
        val packageName: String,
        val name: String,
        var effect: LoudnessEnhancer? = null,
        var attenuator: DynamicsProcessing? = null,
        var unavailable: Boolean = false
    )

    val players: List<PlayerSession> get() = sessions.values.toList()
    val globalAvailable: Boolean get() = globalEffect?.let { runCatching { it.hasControl() }.getOrDefault(false) } == true
    val controlledSessionCount: Int get() = sessions.values.count {
        it.effect?.let { effect -> runCatching { effect.hasControl() }.getOrDefault(false) } == true
    }
    val boostAvailable: Boolean get() = boostEnabled && (globalAvailable || controlledSessionCount > 0)
    val fixedVolume: Boolean get() = audio.isVolumeFixed
    val unmutePercent: Int get() = lastAudiblePercent
    val fineRequested: Boolean get() = needsAttenuation(mediaPercent)
    val attenuationDb: Float get() = VolumeMath.attenuationDb(mediaPercent,
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamMinVolume(AudioManager.STREAM_MUSIC))
    val globalFineAvailable: Boolean get() = globalAttenuator?.let {
        runCatching { it.hasControl() }.getOrDefault(false)
    } == true
    val fineSessionCount: Int get() = sessions.values.count { session ->
        session.attenuator?.let { runCatching { it.hasControl() }.getOrDefault(false) } == true
    }
    // Automatic mode promises a shared output reduction. A session effect alone
    // must not unmute all other apps at the unattenuated first hardware step.
    val fineAvailable: Boolean get() = boostEnabled &&
        (globalFineAvailable || (!preferGlobal && fineSessionCount > 0))
    val fineApplied: Boolean get() = fineRequested && fineAvailable &&
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0

    fun needsAttenuation(percent: Int): Boolean = VolumeMath.needsAttenuation(percent,
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamMinVolume(AudioManager.STREAM_MUSIC))

    init {
        if (mediaPercent > 0) lastAudiblePercent = mediaPercent
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { refresh() }
            })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> acceptSession(intent)
                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                        // Only close the session registered for this player package.
                        if (sessions[id]?.packageName == intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)) {
                            closeSession(id)
                        }
                    }
                    else -> refresh()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerLegacyReceiver(receiver, filter)
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { changed() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { changed() }
        }, handler)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacyReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        // Explicit export flags were added in API 33. The guarded legacy branch
        // must receive session broadcasts from other media-player applications.
        check(Build.VERSION.SDK_INT < 33)
        context.registerReceiver(receiver, filter)
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }

    fun readPercent(stream: Int): Int = VolumeMath.systemPercent(
        audio.getStreamVolume(stream), audio.getStreamMaxVolume(stream))

    private fun volumeSnapshot(): List<Int> = listOf(
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM
    ).map { audio.getStreamVolume(it) }

    fun refresh() {
        val snapshot = volumeSnapshot()
        if (snapshot == observedVolumes) return
        observedVolumes = snapshot
        val index = snapshot.first()
        if (index != expectedMediaIndex) {
            expectedMediaIndex = index
            mediaPercent = readPercent(AudioManager.STREAM_MUSIC)
            if (mediaPercent > 0) lastAudiblePercent = mediaPercent
            applyGain()
        }
        changed()
    }

    fun setMediaPercent(percent: Int) {
        val target = VolumeMath.clamp(percent)
        if (fixedVolume) {
            message = "This device uses fixed volume. Use its own volume controls."
            changed()
            return
        }
        val quiet = needsAttenuation(target)
        var index = VolumeMath.mediaIndex(target, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMinVolume(AudioManager.STREAM_MUSIC))
        try {
            if (quiet) {
                // Configure the negative gain before bringing the hardware above
                // mute. This also covers the interval while the service starts.
                mediaPercent = target
                applyGain()
                if (!fineAvailable) index = 0
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            // Read back: safety limits and device policy can reject a requested index.
            expectedMediaIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            mediaPercent = if (expectedMediaIndex == index) target else readPercent(AudioManager.STREAM_MUSIC)
            if (mediaPercent > 0) lastAudiblePercent = mediaPercent
            message = when {
                quiet && !fineAvailable -> "Kept muted: waiting for output attenuation. No reduction is being applied yet."
                expectedMediaIndex != index -> "Android limited the volume on this output."
                else -> null
            }
            applyGain()
        } catch (_: SecurityException) {
            message = "Android blocked this volume change. Check your sound settings."
            refresh()
        }
        changed()
    }

    fun setStreamPercent(stream: Int, percent: Int): Boolean {
        return try {
            if (fixedVolume) {
                message = "This device uses fixed volume."
                changed()
                false
            } else {
                val target = VolumeMath.systemIndex(percent.coerceIn(0, 100),
                    audio.getStreamMaxVolume(stream), audio.getStreamMinVolume(stream))
                audio.setStreamVolume(stream, target, 0)
                message = if (audio.getStreamVolume(stream) != target) "Android limited this volume change." else null
                changed()
                true
            }
        } catch (_: SecurityException) {
            message = "Do Not Disturb or device policy blocked this change. Open Android sound settings to adjust it."
            changed()
            false
        }
    }

    fun toggleMute() { setMediaPercent(if (mediaPercent == 0) lastAudiblePercent else 0) }

    fun enableBoost() {
        boostEnabled = true
        rebuildEffects()
    }

    fun disableBoost() {
        // Removing an attenuator at step 1 would make quiet audio jump louder.
        // Mute first, including when Stop is pressed in the notification.
        if (fineRequested) {
            holdQuietOutputMuted()
            mediaPercent = 0
        }
        boostEnabled = false
        releaseEffects()
        stopTestSound()
        if (mediaPercent > 100) mediaPercent = readPercent(AudioManager.STREAM_MUSIC)
        lastAudiblePercent = lastAudiblePercent.coerceAtMost(100)
        changed()
    }

    fun setGlobalMode(enabled: Boolean) {
        preferGlobal = enabled
        preferences.edit().putBoolean("global", enabled).apply()
        if (boostEnabled) rebuildEffects() else changed()
    }

    fun retryEffects() { if (boostEnabled) rebuildEffects() }

    private fun rebuildEffects() {
        val quietTarget = mediaPercent.takeIf { fineRequested }
        if (quietTarget != null) holdQuietOutputMuted()
        releaseEffects()
        if (preferGlobal) {
            globalEffect = createEffect(0)
            globalAttenuator = createAttenuator(0)
        }
        sessions.values.forEach { attach(it) }
        applyGain()
        if (quietTarget != null) setMediaPercent(quietTarget)
        changed()
    }

    private fun holdQuietOutputMuted() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        expectedMediaIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        observedVolumes = volumeSnapshot()
    }

    private fun createAttenuator(id: Int): DynamicsProcessing? {
        var effect: DynamicsProcessing? = null
        return try {
            // No EQ, compression or automatic gain: a uniform reduction only.
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2,
                false, 0, false, 0, false, 0, false)
                .setInputGainAllChannelsTo(attenuationDb).build()
            effect = DynamicsProcessing(0, id, config)
            check(effect.hasControl())
            effect.setInputGainAllChannelsTo(attenuationDb)
            check(effect.setEnabled(boostEnabled && fineRequested) == AudioEffect.SUCCESS)
            check(kotlin.math.abs(effect.getInputGainByChannelIndex(0) - attenuationDb) < .05f)
            effect.setControlStatusListener { _, controlled ->
                if (!controlled && fineRequested) holdQuietOutputMuted()
                changed()
            }
            effect
        } catch (_: RuntimeException) {
            runCatching { effect?.release() }
            null
        }
    }

    private fun createEffect(id: Int): LoudnessEnhancer? {
        var effect: LoudnessEnhancer? = null
        return try {
            effect = LoudnessEnhancer(id)
            if (!effect.hasControl()) {
                effect.release()
                null
            } else {
                effect.setTargetGain(VolumeMath.gainMillibels(mediaPercent))
                if (effect.setEnabled(mediaPercent > 100) != AudioEffect.SUCCESS) {
                    effect.release()
                    null
                } else {
                    effect.setControlStatusListener { _, _ -> changed() }
                    effect
                }
            }
        } catch (_: RuntimeException) {
            runCatching { effect?.release() }
            null
        }
    }

    private fun attach(session: PlayerSession) {
        if (globalEffect == null) session.effect = createEffect(session.id)
        if (globalAttenuator == null) session.attenuator = createAttenuator(session.id)
        session.unavailable = session.effect == null && session.attenuator == null
    }

    fun acceptSession(intent: Intent) {
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
        openSession(id, packageName)
    }

    private fun openSession(id: Int, packageName: String) {
        if (id <= 0 || packageName.isBlank() || sessions.containsKey(id) || sessions.size >= 64) return
        val name = if (packageName == context.packageName) "AndrControl test sound" else runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
        val session = PlayerSession(id, packageName, name)
        sessions[id] = session
        if (boostEnabled) {
            attach(session)
            if (fineRequested) setMediaPercent(mediaPercent)
        }
        changed()
    }

    private fun closeSession(id: Int) {
        val session = sessions.remove(id) ?: return
        if (fineRequested && !globalFineAvailable) holdQuietOutputMuted()
        runCatching { session.effect?.release() }
        runCatching { session.attenuator?.release() }
        if (fineRequested) setMediaPercent(mediaPercent)
        changed()
    }

    private fun applyGain() {
        val gain = VolumeMath.gainMillibels(mediaPercent)
        fun update(effect: LoudnessEnhancer?): Boolean {
            if (effect == null) return false
            return runCatching {
                if (!effect.hasControl()) return@runCatching false
                effect.setTargetGain(gain)
                effect.setEnabled(boostEnabled && gain > 0) == AudioEffect.SUCCESS
            }.getOrDefault(false)
        }
        if (globalEffect != null && !update(globalEffect)) {
            runCatching { globalEffect?.release() }
            globalEffect = null
            if (boostEnabled) sessions.values.forEach { if (it.effect == null) attach(it) }
        }
        sessions.values.forEach {
            if (it.effect != null && !update(it.effect)) {
                runCatching { it.effect?.release() }
                it.effect = null
                it.unavailable = true
            }
        }
        fun updateAttenuator(effect: DynamicsProcessing?): Boolean {
            if (effect == null) return false
            return runCatching {
                if (!effect.hasControl()) return@runCatching false
                effect.setInputGainAllChannelsTo(attenuationDb)
                check(kotlin.math.abs(effect.getInputGainByChannelIndex(0) - attenuationDb) < .05f)
                effect.setEnabled(boostEnabled && fineRequested) == AudioEffect.SUCCESS
            }.getOrDefault(false)
        }
        if (globalAttenuator != null && !updateAttenuator(globalAttenuator)) {
            if (fineRequested) holdQuietOutputMuted()
            runCatching { globalAttenuator?.release() }
            globalAttenuator = null
        }
        sessions.values.forEach {
            if (it.attenuator != null && !updateAttenuator(it.attenuator)) {
                if (fineRequested && !globalFineAvailable) holdQuietOutputMuted()
                runCatching { it.attenuator?.release() }
                it.attenuator = null
            }
        }
    }

    private fun releaseEffects() {
        runCatching { globalEffect?.release() }
        globalEffect = null
        runCatching { globalAttenuator?.release() }
        globalAttenuator = null
        sessions.values.forEach {
            runCatching { it.effect?.release() }
            it.effect = null
            runCatching { it.attenuator?.release() }
            it.attenuator = null
            it.unavailable = false
        }
    }

    /** A quiet packaged chime verifies the gain on our own known audio session. */
    fun toggleTestSound() {
        if (testPlaying) { stopTestSound(); return }
        val focus = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopTestSound()
            }.build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            message = "Another app has audio focus. Try the test sound when it finishes."
            changed()
            return
        }
        sampleFocus = focus
        try {
            val player = MediaPlayer.create(context, R.raw.test_chime)
                ?: throw IllegalStateException("Cannot create test player")
            sample = player
            openSession(player.audioSessionId, context.packageName)
            player.setOnCompletionListener { stopTestSound() }
            player.setOnErrorListener { _, _, _ ->
                message = "The test sound could not play on this output."
                stopTestSound()
                true
            }
            player.start()
            testPlaying = true
            handler.postDelayed(stopSample, 5000)
            changed()
        } catch (_: RuntimeException) {
            message = "The test sound could not play on this output."
            stopTestSound()
        }
    }

    fun stopTestSound() {
        handler.removeCallbacks(stopSample)
        sample?.let {
            runCatching { closeSession(it.audioSessionId) }
            runCatching { it.release() }
        }
        sample = null
        sampleFocus?.let { audio.abandonAudioFocusRequest(it) }
        sampleFocus = null
        testPlaying = false
        changed()
    }

    fun connectedOutput(): Pair<String, String> {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val externalTypes = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        if (Build.VERSION.SDK_INT >= 31) externalTypes.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        val external = devices.firstOrNull { it.type in externalTypes }
        return if (external != null) {
            external.productName.toString() to "Connected output · Android controls routing"
        } else "This device" to "Built-in audio · Android controls routing"
    }
}

