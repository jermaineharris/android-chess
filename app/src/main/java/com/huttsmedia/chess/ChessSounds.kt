package com.huttsmedia.chess

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

suspend fun playMoveSound(event: String) {
    val tone = when (event) {
        "capture" -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 90
        "check", "mate" -> ToneGenerator.TONE_CDMA_CONFIRM to 120
        "draw", "resign", "flag" -> ToneGenerator.TONE_PROP_NACK to 140
        "move", "hint" -> ToneGenerator.TONE_PROP_BEEP to 45
        else -> return
    }
    withContext(Dispatchers.Default) {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
        try {
            tg.startTone(tone.first, tone.second)
            delay(tone.second.toLong() + 20)
        } finally {
            tg.release()
        }
    }
}
