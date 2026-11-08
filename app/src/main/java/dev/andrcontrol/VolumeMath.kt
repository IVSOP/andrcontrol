package dev.andrcontrol

import kotlin.math.log10
import kotlin.math.roundToInt

/** Hardware steps plus software attenuation below the first audible step. */
object VolumeMath {
    fun clamp(percent: Int) = percent.coerceIn(0, 200)

    fun systemIndex(percent: Int, maxIndex: Int, minIndex: Int = 0): Int {
        require(maxIndex >= minIndex && minIndex >= 0)
        return (clamp(percent).coerceAtMost(100) * maxIndex / 100f)
            .roundToInt().coerceIn(minIndex, maxIndex)
    }

    fun systemPercent(index: Int, maxIndex: Int): Int =
        if (maxIndex <= 0) 0 else (index.coerceIn(0, maxIndex) * 100f / maxIndex).roundToInt()

    // AudioEffect uses millibels: 200% amplitude = +6.02 dB = 602 mB.
    fun gainMillibels(percent: Int): Int =
        if (percent <= 100) 0 else (2000 * log10(clamp(percent) / 100.0)).roundToInt()

    fun needsAttenuation(percent: Int, maxIndex: Int, minIndex: Int = 0): Boolean =
        percent > 0 && maxIndex > 0 && clamp(percent) * maxIndex < maxOf(1, minIndex) * 100

    fun mediaIndex(percent: Int, maxIndex: Int, minIndex: Int = 0): Int =
        if (needsAttenuation(percent, maxIndex, minIndex)) maxOf(1, minIndex)
        else systemIndex(percent, maxIndex, minIndex)

    /** Relative to the first audible hardware step, not the speaker's acoustic maximum. */
    fun attenuationDb(percent: Int, maxIndex: Int, minIndex: Int = 0): Float =
        if (!needsAttenuation(percent, maxIndex, minIndex)) 0f
        else (20 * log10(clamp(percent) * maxIndex / (maxOf(1, minIndex) * 100.0))).toFloat()
}

