package com.anticolision360.app

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Core 2.1 audio policy:
 * - NONE: silent
 * - YELLOW: short precaution beep
 * - RED lateral: repeated side warning
 * - RED front: stronger repeated warning
 * - CRITICAL front: fast, strong alarm pattern
 */
class AlertAudioEngine {
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private var lastYellowAt = 0L
    private var lastFrontAt = 0L
    private var lastSideAt = 0L
    private var lastCriticalAt = 0L

    fun update(state: RiskState, now: Long) {
        if (state.frontCritical) {
            if (now - lastCriticalAt >= 230L) {
                tone.startTone(ToneGenerator.TONE_SUP_ERROR, 190)
                lastCriticalAt = now
            }
            return
        }

        if (state.front == AlertLevel.RED) {
            if (now - lastFrontAt >= 520L) {
                tone.startTone(ToneGenerator.TONE_SUP_ERROR, 260)
                lastFrontAt = now
            }
            return
        }

        if (state.left == AlertLevel.RED || state.right == AlertLevel.RED) {
            if (now - lastSideAt >= 420L) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 130)
                lastSideAt = now
            }
            return
        }

        if (
            state.front == AlertLevel.YELLOW ||
            state.left == AlertLevel.YELLOW ||
            state.right == AlertLevel.YELLOW
        ) {
            if (now - lastYellowAt >= 1100L) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 95)
                lastYellowAt = now
            }
        }
    }

    fun release() {
        tone.release()
    }
}
