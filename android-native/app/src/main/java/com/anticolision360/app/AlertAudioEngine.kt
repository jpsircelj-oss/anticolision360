package com.anticolision360.app

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Silent for NONE/YELLOW. RED front and RED lateral use different patterns.
 */
class AlertAudioEngine {
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 92)
    private var lastFrontAt = 0L
    private var lastSideAt = 0L

    fun update(state: RiskState, now: Long) {
        if (state.front == AlertLevel.RED) {
            if (now - lastFrontAt >= 850L) {
                tone.startTone(ToneGenerator.TONE_SUP_ERROR, 330)
                lastFrontAt = now
            }
            return
        }

        if (state.left == AlertLevel.RED || state.right == AlertLevel.RED) {
            if (now - lastSideAt >= 430L) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                lastSideAt = now
            }
        }
    }

    fun release() {
        tone.release()
    }
}
