package com.anticolision360.app

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Core 2.7 audio direccional:
 * - IZQUIERDA: tono grave/DTMF 1
 * - DERECHA: tono agudo/DTMF 9
 * - INFERIOR/FRONTAL: tono de alarma claramente diferente
 *
 * La identidad del sonido depende de la zona. La gravedad cambia la cadencia y
 * duración, pero no cambia la "familia" sonora, para que el conductor aprenda
 * rápidamente qué lado está avisando.
 */
class AlertAudioEngine {
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private var lastLeftAt = 0L
    private var lastRightAt = 0L
    private var lastFrontAt = 0L

    fun update(state: RiskState, now: Long) {
        // El aviso inferior/frontal tiene prioridad cuando existe riesgo frontal.
        if (state.front == AlertLevel.RED || state.frontCritical) {
            playFront(now, critical = state.frontCritical, yellow = false)
            return
        }
        if (state.front == AlertLevel.YELLOW && state.yellowAudible) {
            playFront(now, critical = false, yellow = true)
            return
        }

        val leftRed = state.left == AlertLevel.RED
        val rightRed = state.right == AlertLevel.RED
        if (leftRed || rightRed) {
            // Si ambos lados están activos, alternamos para conservar la dirección audible.
            if (leftRed && rightRed) {
                if ((now / 420L) % 2L == 0L) playLeft(now, red = true)
                else playRight(now, red = true)
            } else if (leftRed) {
                playLeft(now, red = true)
            } else {
                playRight(now, red = true)
            }
            return
        }

        // Los amarillos sólo suenan cuando RiskEngine los marcó como audibles.
        if (!state.yellowAudible) return

        val leftYellow = state.left == AlertLevel.YELLOW
        val rightYellow = state.right == AlertLevel.YELLOW
        if (leftYellow && rightYellow) {
            if ((now / 900L) % 2L == 0L) playLeft(now, red = false)
            else playRight(now, red = false)
        } else if (leftYellow) {
            playLeft(now, red = false)
        } else if (rightYellow) {
            playRight(now, red = false)
        }
    }

    private fun playLeft(now: Long, red: Boolean) {
        val interval = if (red) 430L else 1050L
        if (now - lastLeftAt < interval) return
        tone.startTone(ToneGenerator.TONE_DTMF_1, if (red) 170 else 95)
        lastLeftAt = now
    }

    private fun playRight(now: Long, red: Boolean) {
        val interval = if (red) 430L else 1050L
        if (now - lastRightAt < interval) return
        tone.startTone(ToneGenerator.TONE_DTMF_9, if (red) 155 else 85)
        lastRightAt = now
    }

    private fun playFront(now: Long, critical: Boolean, yellow: Boolean) {
        val interval = when {
            critical -> 230L
            yellow -> 900L
            else -> 520L
        }
        if (now - lastFrontAt < interval) return

        val toneType = if (yellow) {
            ToneGenerator.TONE_PROP_ACK
        } else {
            ToneGenerator.TONE_SUP_ERROR
        }
        val duration = when {
            critical -> 190
            yellow -> 110
            else -> 250
        }
        tone.startTone(toneType, duration)
        lastFrontAt = now
    }

    fun release() {
        tone.release()
    }
}
