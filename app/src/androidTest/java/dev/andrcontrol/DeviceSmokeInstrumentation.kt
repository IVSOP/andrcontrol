package dev.andrcontrol

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar

/** Exercises only our own views; no cross-app input injection or extra permissions. */
class DeviceSmokeInstrumentation : Instrumentation() {
    private lateinit var activity: MainActivity
    private lateinit var mixer: MixerController
    private var originalIndex = -1
    private var originalGlobalMode = true
    private val report = StringBuilder()
    private var attenuationProbe = false
    private var quietOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        attenuationProbe = arguments?.getString("probe") == "attenuation"
        quietOnly = arguments?.getString("quiet") == "true"
        start()
    }

    override fun onStart() {
        if (attenuationProbe) {
            probeAttenuation()
            return
        }
        var succeeded = false
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            onMain {
                mixer = (activity.application as MixerApplication).mixer
                originalIndex = mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                originalGlobalMode = mixer.preferGlobal
                check(!mixer.fixedVolume) { "Device uses fixed volume" }
                check(!mixer.boostEnabled) { "Stop existing boost before running the device test" }
            }
            if (quietOnly) {
                verifyQuietRange()
                succeeded = true
            } else {
            setSlider(50)
            onMain {
                check(mixer.mediaPercent == 50)
                check(mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC) ==
                    VolumeMath.systemIndex(50, mixer.audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
            }
            pass("50% slider updates the actual system media volume")

            click("Increase media volume by one percent")
            onMain { check(mixer.mediaPercent == 51) }
            setSlider(37)
            onMain {
                check(mixer.mediaPercent == 37)
                check(mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC) ==
                    VolumeMath.systemIndex(37, mixer.audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
            }
            pass("One-percent adjustment and exact slider percentage")

            click("Mute media")
            onMain { check(mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) }
            click("Restore previous media volume")
            onMain { check(mixer.mediaPercent == 37) }
            pass("Mute and unmute restore the requested media level")

            // Start the service from our visible activity. Notification permission
            // remains the user's choice; the test does not grant or change it.
            onMain { activity.startForegroundService(Intent(activity, BoostService::class.java)) }
            await { mixer.boostEnabled }
            setSlider(200)
            onMain {
                check(mixer.mediaPercent == 200)
                check(mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC) ==
                    mixer.audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }
            pass("Foreground boost service and 200% target")
            onMain {
                pass(if (mixer.globalAvailable) "Device accepted the shared output effect"
                    else "Shared output effect unavailable; player-session fallback selected")
                mixer.setGlobalMode(false)
                mixer.toggleTestSound()
                check(mixer.testPlaying) { "Test chime did not start" }
                val session = mixer.players.single { it.packageName == activity.packageName }
                val effect = checkNotNull(session.effect) { "Device rejected the chime's session effect" }
                check(effect.hasControl())
                check(effect.enabled)
                check(effect.targetGain.toInt() == 602) { "Incorrect gain: ${effect.targetGain}" }
            }
            pass("Real player-session LoudnessEnhancer enabled at +6.02 dB during the quiet chime")
            onMain {
                mixer.audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                mixer.refresh()
                check(mixer.mediaPercent < 100)
                check(VolumeMath.gainMillibels(mixer.mediaPercent) == 0)
                mixer.players.forEach { check(it.effect?.enabled != true) }
                mixer.stopTestSound()
            }
            pass("System volume-down removes extra gain and follows the hardware level")

            onMain { check(activity.moveTaskToBack(true)) }
            SystemClock.sleep(500)
            onMain { check(mixer.boostEnabled) }
            pass("Boost service survives putting the app in the background")
            succeeded = true
            }
        } catch (failure: Throwable) {
            report.appendLine("FAIL: ${failure.stackTraceToString()}")
        } finally {
            if (::mixer.isInitialized && originalIndex >= 0) {
                try {
                    onMain {
                        mixer.stopTestSound()
                        activity.stopService(Intent(activity, BoostService::class.java))
                        mixer.disableBoost()
                        mixer.setGlobalMode(originalGlobalMode)
                        mixer.audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalIndex, 0)
                        mixer.refresh()
                    }
                    pass("Original media volume and compatibility preference restored; boost stopped")
                } catch (failure: Throwable) {
                    succeeded = false
                    report.appendLine("RESTORE FAILED: ${failure.stackTraceToString()}")
                }
            }
        }
        finish(if (succeeded) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", "\n$report")
                putBoolean("passed", succeeded)
            })
    }

    private fun verifyQuietRange() {
        onMain { activity.startForegroundService(Intent(activity, BoostService::class.java)) }
        await { mixer.boostEnabled }
        onMain { check(mixer.globalFineAvailable) { "Shared output attenuation is unavailable" } }
        pass("Shared output attenuation is available")
        for (percent in 1..6) {
            setSlider(percent)
            onMain {
                check(mixer.mediaPercent == percent)
                check(mixer.fineApplied)
                check(mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 1)
                check(mixer.attenuationDb < 0f)
            }
            pass("$percent% uses negative signal gain at hardware step 1")
        }
        setSlider(3)
        onMain {
            mixer.toggleTestSound()
            check(mixer.testPlaying)
            mixer.stopTestSound()
            activity.stopService(Intent(activity, BoostService::class.java))
            mixer.disableBoost()
            check(mixer.audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
        }
        pass("Stopping quiet control mutes before removing attenuation")
    }

    private fun probeAttenuation() {
        var supported = false
        try {
            onMain {
                val player = checkNotNull(MediaPlayer.create(targetContext, R.raw.test_chime))
                try {
                    for (id in listOf(0, player.audioSessionId)) {
                        var dynamics: DynamicsProcessing? = null
                        try {
                            val config = DynamicsProcessing.Config.Builder(
                                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2,
                                false, 0, false, 0, false, 0, false).build()
                            dynamics = DynamicsProcessing(0, id, config)
                            dynamics.setInputGainAllChannelsTo(-16.48f)
                            check(dynamics.setEnabled(true) == 0 && dynamics.hasControl())
                            val gain = dynamics.getInputGainByChannelIndex(0)
                            check(kotlin.math.abs(gain + 16.48f) < .05f)
                            pass("DynamicsProcessing session=$id accepts ${gain} dB, channels=${dynamics.channelCount}")
                            if (id == 0) supported = true
                        } catch (failure: RuntimeException) {
                            report.appendLine("UNAVAILABLE: DynamicsProcessing session=$id: $failure")
                        } finally {
                            runCatching { dynamics?.release() }
                        }
                        var loudness: LoudnessEnhancer? = null
                        try {
                            loudness = LoudnessEnhancer(id)
                            loudness.setTargetGain(-1648)
                            check(loudness.setEnabled(true) == 0 && loudness.hasControl())
                            pass("LoudnessEnhancer session=$id accepts ${loudness.targetGain} mB")
                        } catch (failure: RuntimeException) {
                            report.appendLine("UNAVAILABLE: LoudnessEnhancer session=$id: $failure")
                        } finally {
                            runCatching { loudness?.release() }
                        }
                    }
                } finally { player.release() }
            }
        } catch (failure: Throwable) { report.appendLine("FAIL: ${failure.stackTraceToString()}") }
        finish(Activity.RESULT_OK, Bundle().apply {
            putBoolean("sharedAttenuation", supported)
            putString("stream", "\n$report")
        })
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun click(description: String) = onMain {
        val view = descendants(activity.window.decorView).single { it.contentDescription?.toString() == description }
        check(view.performClick()) { "Click failed: $description" }
    }

    private fun setSlider(percent: Int) = onMain {
        val slider = descendants(activity.window.decorView).filterIsInstance<SeekBar>()
            .single { it.contentDescription?.toString() == "Media volume, from zero to two hundred percent" }
        check(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, percent.toFloat()) }))
    }

    private fun await(condition: () -> Boolean) {
        repeat(50) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for foreground boost")
    }

    private fun pass(message: String) {
        report.appendLine("PASS: $message")
        sendStatus(0, Bundle().apply { putString("stream", "PASS: $message\n") })
    }
}
