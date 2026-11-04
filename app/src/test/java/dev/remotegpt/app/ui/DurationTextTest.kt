package dev.remotegpt.app.ui

import dev.remotegpt.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationTextTest {
    @Test fun shortDurationsStayInMilliseconds() {
        assertEquals(DurationText(R.string.ui_duration_ms, listOf(0L)), durationText(-5))
        assertEquals(DurationText(R.string.ui_duration_ms, listOf(186L)), durationText(186))
        assertEquals(DurationText(R.string.ui_duration_ms, listOf(999L)), durationText(999))
    }

    @Test fun secondsKeepOneTruncatedDecimal() {
        assertEquals(DurationText(R.string.ui_duration_seconds, listOf("1.0")), durationText(1_000))
        assertEquals(DurationText(R.string.ui_duration_seconds, listOf("2.1")), durationText(2_140))
        assertEquals(DurationText(R.string.ui_duration_seconds, listOf("59.9")), durationText(59_999))
    }

    @Test fun longDurationsUseMinutesAndSeconds() {
        assertEquals(DurationText(R.string.ui_duration_minutes, listOf(1L, 0L)), durationText(60_000))
        assertEquals(DurationText(R.string.ui_duration_minutes, listOf(1L, 12L)), durationText(72_500))
    }
}
