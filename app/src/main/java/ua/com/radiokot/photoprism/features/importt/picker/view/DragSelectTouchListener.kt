package ua.com.radiokot.photoprism.features.importt.picker.view

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Selects a range of list items by dragging a finger over them,
 * once [start] is called, for example, on a long press.
 * Scrolls the list when the finger is close to its top or bottom.
 */
class DragSelectTouchListener(
    private val recyclerView: RecyclerView,
    /**
     * Called with the adapter positions from where the drag started to where the finger is.
     */
    private val onRangeChanged: (startPosition: Int, endPosition: Int) -> Unit,
    private val onEnded: () -> Unit,
) : RecyclerView.OnItemTouchListener {
    private val density = recyclerView.resources.displayMetrics.density
    private val autoScrollZonePx = AUTO_SCROLL_ZONE_DP * density
    private val maxScrollSpeedPx = MAX_SCROLL_SPEED_DP * density

    private var startPosition = RecyclerView.NO_POSITION
    private var lastPosition = RecyclerView.NO_POSITION
    private var lastX = 0f
    private var lastY = 0f
    private var scrollSpeedPx = 0

    private val autoScroll = object : Runnable {
        override fun run() {
            if (startPosition == RecyclerView.NO_POSITION || scrollSpeedPx == 0) {
                return
            }
            recyclerView.scrollBy(0, scrollSpeedPx)
            updatePosition()
            recyclerView.postOnAnimation(this)
        }
    }

    fun start(position: Int) {
        if (position == RecyclerView.NO_POSITION) {
            return
        }
        startPosition = position
        lastPosition = position
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (startPosition == RecyclerView.NO_POSITION) {
            return false
        }
        handle(e)
        return true
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) =
        handle(e)

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
    }

    private fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                updateAutoScroll()
                updatePosition()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
                stop()
        }
    }

    private fun updatePosition() {
        val child = recyclerView.findChildViewUnder(
            lastX.coerceIn(0f, recyclerView.width - 1f),
            lastY.coerceIn(0f, recyclerView.height - 1f),
        ) ?: return
        val position = recyclerView.getChildAdapterPosition(child)
        if (position != RecyclerView.NO_POSITION && position != lastPosition) {
            lastPosition = position
            onRangeChanged(startPosition, position)
        }
    }

    private fun updateAutoScroll() {
        val height = recyclerView.height
        val speed = when {
            lastY < autoScrollZonePx ->
                -maxScrollSpeedPx * (autoScrollZonePx - lastY) / autoScrollZonePx

            lastY > height - autoScrollZonePx ->
                maxScrollSpeedPx * (lastY - (height - autoScrollZonePx)) / autoScrollZonePx

            else ->
                0f
        }.coerceIn(-maxScrollSpeedPx, maxScrollSpeedPx).roundToInt()

        val wasScrolling = scrollSpeedPx != 0
        scrollSpeedPx = speed
        if (!wasScrolling && speed != 0) {
            recyclerView.postOnAnimation(autoScroll)
        }
    }

    private fun stop() {
        startPosition = RecyclerView.NO_POSITION
        scrollSpeedPx = 0
        recyclerView.removeCallbacks(autoScroll)
        onEnded()
    }

    private companion object {
        private const val AUTO_SCROLL_ZONE_DP = 72
        private const val MAX_SCROLL_SPEED_DP = 20
    }
}
