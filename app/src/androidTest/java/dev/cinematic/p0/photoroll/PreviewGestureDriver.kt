package dev.cinematic.p0.photoroll

import android.app.UiAutomation
import android.os.SystemClock
import android.os.Trace
import android.view.InputDevice
import android.view.MotionEvent

/** 診断専用。注入の受付は配信・描画・画面提示を意味しない。 */
internal class PreviewGestureDriver(private val automation: UiAutomation) {
    data class Injection(val index: Int, val action: Int, val plannedUptimeMs: Long,
        val eventUptimeMs: Long, val callStartNs: Long, val callEndNs: Long)

    fun drag(id: Int, x: Float, y: Float, deltaX: Float, steps: Int = 30,
        intervalMs: Long = 16): List<Injection> {
        require(id >= 0 && steps in 2..240 && intervalMs in 8..100)
        require(x.isFinite() && y.isFinite() && deltaX.isFinite())
        val records = ArrayList<Injection>(steps + 2)
        val downTime = SystemClock.uptimeMillis()
        var pointerDown = false
        var lastX = x
        fun inject(index: Int, action: Int, position: Float, planned: Long) {
            val eventTime = SystemClock.uptimeMillis() // 予定時刻を実入力時刻に偽装しない。
            val event = MotionEvent.obtain(downTime, eventTime, action, position, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            val start = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("cc.preview.inject:$id:$index:$eventTime:$action")
            try {
                check(automation.injectInputEvent(event, false)) { "Preview input injection rejected" }
            } finally {
                Trace.endSection()
                event.recycle()
            }
            records += Injection(index, action, planned, eventTime, start, SystemClock.elapsedRealtimeNanos())
        }
        try {
            // DOWNの注入が例外になってもCANCELを試す。
            pointerDown = true
            inject(0, MotionEvent.ACTION_DOWN, x, downTime)
            for (step in 1..steps) {
                val planned = downTime + step * intervalMs
                SystemClock.sleep((planned - SystemClock.uptimeMillis()).coerceAtLeast(0))
                lastX = x + deltaX * step / steps
                inject(step, MotionEvent.ACTION_MOVE, lastX, planned)
            }
            inject(steps + 1, MotionEvent.ACTION_UP, lastX, SystemClock.uptimeMillis())
            pointerDown = false
        } finally {
            if (pointerDown) {
                val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, lastX, y, 0)
                cancel.source = InputDevice.SOURCE_TOUCHSCREEN
                try { automation.injectInputEvent(cancel, true) } finally { cancel.recycle() }
            }
        }
        return records
    }
}

