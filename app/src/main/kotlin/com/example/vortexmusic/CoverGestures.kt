package io.github.vortexmusic.player

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

// Oynatıcı ekranındaki hareketler: kapağı yana çekince şarkı değişir,
// ekranı aşağı çekince kapanır.
class CoverGestures(
    private val activity: Activity,
    private val screen: View,
    private val player: () -> ExoPlayer?,
    private val close: () -> Unit
) {

    // Oynatıcı ekranını aşağı çekince kapanır
    fun pullMove(dy: Float) {
        val d = maxOf(0f, dy)
        screen.translationY = d
        screen.alpha = 1f - 0.4f * (d / maxOf(1, screen.height)).coerceAtMost(1f)
    }

    fun pullEnd(dy: Float) {
        val h = screen.height
        if (dy > h * 0.2f) {
            screen.animate().translationY(h.toFloat()).alpha(0f).setDuration(180)
                .withEndAction {
                    close()
                    screen.translationY = 0f
                    screen.alpha = 1f
                }.start()
        } else {
            screen.animate().translationY(0f).alpha(1f).setDuration(200).start()
        }
    }

    fun attachScreen() {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downY = 0f
        var pulling = false
        screen.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    pulling = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!pulling && dy > slop) pulling = true
                    if (pulling) pullMove(dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) pullEnd(e.rawY - downY)
                    pulling = false
                }
            }
            true
        }
    }

    // Mini karttan yukarı çekince oynatıcı ekranı açılır (dokunma da çalışmaya devam eder)
    fun attachMini(mini: View, open: () -> Unit) {
        val minDist = activity.resources.displayMetrics.density * 40f
        var downY = 0f
        var opened = false
        mini.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    opened = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!opened && downY - e.rawY > minDist) {
                        opened = true
                        open()
                    }
                }
            }
            opened
        }
    }

    // Kapağı yana sürükleyince şarkı değişir (girdap gibi dönerek kayar)
    fun attachCover(v: View) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        var vertical = false
        var busy = false
        v.setOnTouchListener { view, e ->
            if (busy) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    vertical = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        vertical = abs(dy) > abs(dx)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging && vertical) {
                        pullMove(dy)
                    } else if (dragging) {
                        val f = (abs(dx) / view.width).coerceAtMost(1f)
                        view.translationX = dx * 0.7f
                        view.rotation = dx / view.width * 18f
                        view.alpha = 1f - 0.5f * f
                        val sc = 1f - 0.12f * f
                        view.scaleX = sc
                        view.scaleY = sc
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    if (dragging && vertical) {
                        pullEnd(e.rawY - downY)
                        dragging = false
                        vertical = false
                        return@setOnTouchListener true
                    }
                    val p = player()
                    val goNext = dx < 0
                    val canGo = p != null && (if (goNext) p.hasNextMediaItem() else true)
                    if (dragging && abs(dx) > view.width * 0.28f && canGo) {
                        val dir = if (goNext) -1 else 1
                        busy = true
                        view.animate()
                            .translationX(dir * view.width * 1.1f)
                            .rotation(dir * 28f)
                            .scaleX(0.7f).scaleY(0.7f)
                            .alpha(0f)
                            .setDuration(170)
                            .withEndAction {
                                if (goNext) p?.seekToNext() else p?.seekToPrevious()
                                p?.play()
                                view.translationX = -dir * view.width * 1.1f
                                view.rotation = -dir * 28f
                                view.scaleX = 0.7f
                                view.scaleY = 0.7f
                                view.animate()
                                    .translationX(0f).rotation(0f)
                                    .scaleX(1f).scaleY(1f).alpha(1f)
                                    .setDuration(280)
                                    .setInterpolator(OvershootInterpolator(0.8f))
                                    .withEndAction { busy = false }
                                    .start()
                            }
                            .start()
                    } else if (dragging) {
                        view.animate()
                            .translationX(0f).rotation(0f)
                            .scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                    dragging = false
                }
            }
            true
        }
    }
}
