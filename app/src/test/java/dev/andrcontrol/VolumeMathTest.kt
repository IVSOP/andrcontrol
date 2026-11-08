package dev.andrcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMathTest {
    @Test fun mediaVolumeMapsToHardwareSteps() {
        assertEquals(0, VolumeMath.systemIndex(0, 15))
        assertEquals(8, VolumeMath.systemIndex(50, 15))
        assertEquals(15, VolumeMath.systemIndex(100, 15))
        assertEquals(15, VolumeMath.systemIndex(200, 15))
        assertEquals(53, VolumeMath.systemPercent(8, 15))
    }

    @Test fun respectsHardwareMinimumAndZeroStepDevices() {
        assertEquals(1, VolumeMath.systemIndex(0, 7, 1))
        assertEquals(7, VolumeMath.systemIndex(200, 7, 1))
        assertEquals(0, VolumeMath.systemIndex(100, 0))
        assertEquals(0, VolumeMath.systemPercent(0, 0))
    }

    @Test fun clipsOutOfRangeRequests() {
        assertEquals(0, VolumeMath.clamp(-100))
        assertEquals(200, VolumeMath.clamp(1000))
        assertEquals(0, VolumeMath.systemIndex(-1, 15))
        assertEquals(15, VolumeMath.systemIndex(Int.MAX_VALUE, 15))
        assertEquals(100, VolumeMath.systemPercent(20, 15))
    }

    @Test fun doublingSignalAmplitudeIsSixDecibels() {
        assertEquals(0, VolumeMath.gainMillibels(0))
        assertEquals(0, VolumeMath.gainMillibels(100))
        assertEquals(352, VolumeMath.gainMillibels(150))
        assertEquals(602, VolumeMath.gainMillibels(200))
        assertEquals(602, VolumeMath.gainMillibels(201))
    }

    @Test fun increasingPercentNeverDecreasesVolumeOrGain() {
        for (percent in 0 until 200) {
            assertTrue(VolumeMath.systemIndex(percent, 25) <= VolumeMath.systemIndex(percent + 1, 25))
            assertTrue(VolumeMath.gainMillibels(percent) <= VolumeMath.gainMillibels(percent + 1))
        }
    }

    @Test fun everyPositiveValueBelowFirstStepUsesHardwareStepOne() {
        for (percent in 1..6) {
            assertTrue(VolumeMath.needsAttenuation(percent, 15))
            assertEquals(1, VolumeMath.mediaIndex(percent, 15))
            assertTrue(VolumeMath.attenuationDb(percent, 15) < 0)
        }
        assertEquals(0, VolumeMath.mediaIndex(0, 15))
        assertEquals(false, VolumeMath.needsAttenuation(0, 15))
        assertEquals(false, VolumeMath.needsAttenuation(7, 15))
        assertEquals(1, VolumeMath.mediaIndex(7, 15))
    }

    @Test fun quietValuesActuallyReduceSignalBelowTheFirstStep() {
        assertEquals(-16.48f, VolumeMath.attenuationDb(1, 15), .01f)
        assertEquals(-10.46f, VolumeMath.attenuationDb(2, 15), .01f)
        assertEquals(-6.94f, VolumeMath.attenuationDb(3, 15), .01f)
        assertEquals(-.92f, VolumeMath.attenuationDb(6, 15), .01f)
        assertEquals(0f, VolumeMath.attenuationDb(7, 15), 0f)
    }

    @Test fun fineRangeAdaptsToHardwareStepCount() {
        assertTrue(VolumeMath.needsAttenuation(13, 7))
        assertEquals(false, VolumeMath.needsAttenuation(15, 7))
        assertTrue(VolumeMath.needsAttenuation(4, 20))
        assertEquals(false, VolumeMath.needsAttenuation(5, 20))
        assertEquals(false, VolumeMath.needsAttenuation(1, 0))
        assertEquals(0f, VolumeMath.attenuationDb(0, 15), 0f)
        assertEquals(0f, VolumeMath.attenuationDb(-1, 15), 0f)
        assertEquals(0f, VolumeMath.attenuationDb(200, 15), 0f)
    }

    @Test fun attenuationIncreasesSmoothlyAndRespectsNonzeroHardwareMinimum() {
        var previous = VolumeMath.attenuationDb(1, 15)
        for (percent in 2..7) {
            val current = VolumeMath.attenuationDb(percent, 15)
            assertTrue(current > previous)
            previous = current
        }
        assertEquals(2, VolumeMath.mediaIndex(1, 15, 2))
        assertEquals(-22.50f, VolumeMath.attenuationDb(1, 15, 2), .01f)
    }
}

