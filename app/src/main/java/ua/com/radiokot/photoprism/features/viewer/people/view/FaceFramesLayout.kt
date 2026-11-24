package ua.com.radiokot.photoprism.features.viewer.people.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.chrisbanes.photoview.PhotoView
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.features.viewer.people.data.model.PhotoFace
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Wraps a [PhotoView] and draws frames over the faces in it, labelled with names.
 * The frames follow zoom, pan, rotation and fit, as they are mapped
 * through the image matrix of the view.
 * Face areas are relative to the oriented image, which is the one the previews show,
 * so the EXIF orientation needs no extra care.
 *
 * A tap on a frame is taken from the photo view, so it doesn't toggle the full screen;
 * any other gesture goes to the photo view as usual.
 *
 * In the adding mode, it shows a draft frame to move and resize, for a missed face.
 *
 * @see wrap
 */
@SuppressLint("ViewConstructor")
class FaceFramesLayout private constructor(
    context: Context,
    val photoView: PhotoView,
) : FrameLayout(context) {

    var faces: List<PhotoFace> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var highlightedFaceUid: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether unnamed faces can be named, it changes their label.
     */
    var isEditable: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onFaceClicked: ((PhotoFace) -> Unit)? = null

    /**
     * The draft frame relative to the image, non-null in the adding mode.
     */
    var draftArea: RectF? = null
        private set

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = Color.argb(90, 0, 0, 0)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val highlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 255, 255, 255)
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
        )
    }
    private val dimPaint = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val dashEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)

    private val namedColor = Color.WHITE
    private val unnamedColor = Color.rgb(255, 202, 40)
    private val labelBackgroundColor = Color.argb(200, 20, 20, 20)
    private val cornerRadius = dp(6f)
    private val labelPaddingH = dp(8f)
    private val labelPaddingV = dp(4f)
    private val labelGap = dp(4f)
    private val labelMaxWidth = dp(180f)
    private val touchExtra = dp(8f)
    private val handleRadius = dp(9f)
    private val handleTouchRadius = dp(28f)

    private val unnamedLabel = context.getString(R.string.photo_people_who_is_this)
    private val unknownLabel = context.getString(R.string.photo_people_unknown)

    private val tmpPoints = FloatArray(8)
    private val tmpMatrix = Matrix()
    private val dimPath = Path()

    /**
     * Frames and labels of the last draw, in view coordinates, for the touch.
     */
    private val hitAreas = mutableListOf<Pair<PhotoFace, List<RectF>>>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getLongPressTimeout()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tappedFace: PhotoFace? = null

    private enum class DraftDrag { MOVE, RESIZE }

    private var draftDrag: DraftDrag? = null
    private var lastDragX = 0f
    private var lastDragY = 0f

    init {
        setWillNotDraw(false)
        photoView.setOnMatrixChangeListener {
            invalidate()
        }
    }

    // region Mapping
    /**
     * @return the image area, relative to the image, mapped into this view, or null
     * if the image is not there yet.
     */
    private fun mapToView(area: RectF, out: RectF): Boolean {
        val drawable = photoView.drawable ?: return false
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) {
            return false
        }

        // All 4 corners, so a rotation keeps the frame around the face.
        tmpPoints[0] = area.left * dw; tmpPoints[1] = area.top * dh
        tmpPoints[2] = area.right * dw; tmpPoints[3] = area.top * dh
        tmpPoints[4] = area.right * dw; tmpPoints[5] = area.bottom * dh
        tmpPoints[6] = area.left * dw; tmpPoints[7] = area.bottom * dh
        photoView.imageMatrix.mapPoints(tmpPoints)

        val offsetX = photoView.left + photoView.paddingLeft.toFloat()
        val offsetY = photoView.top + photoView.paddingTop.toFloat()
        out.set(
            minOf(tmpPoints[0], tmpPoints[2], tmpPoints[4], tmpPoints[6]) + offsetX,
            minOf(tmpPoints[1], tmpPoints[3], tmpPoints[5], tmpPoints[7]) + offsetY,
            maxOf(tmpPoints[0], tmpPoints[2], tmpPoints[4], tmpPoints[6]) + offsetX,
            maxOf(tmpPoints[1], tmpPoints[3], tmpPoints[5], tmpPoints[7]) + offsetY,
        )
        return true
    }

    /**
     * @return the view point mapped into the image, relative to it, or null
     * if the image is not there yet.
     */
    private fun mapToImage(x: Float, y: Float): FloatArray? {
        val drawable = photoView.drawable ?: return null
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0 || !photoView.imageMatrix.invert(tmpMatrix)) {
            return null
        }
        val point = floatArrayOf(
            x - photoView.left - photoView.paddingLeft,
            y - photoView.top - photoView.paddingTop,
        )
        tmpMatrix.mapPoints(point)
        point[0] /= dw
        point[1] /= dh
        return point
    }
    // endregion

    // region Drawing
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        hitAreas.clear()
        val draft = draftArea
        if (draft != null) {
            drawDraft(canvas, draft)
        } else {
            faces.forEach { face -> drawFace(canvas, face) }
        }
    }

    private fun drawFace(canvas: Canvas, face: PhotoFace) {
        val frame = RectF()
        if (!mapToView(face.area, frame)) {
            return
        }

        val isHighlighted = face.markerUid == highlightedFaceUid
        val isNamed = face.hasName && !face.isToReview
        val color = if (isNamed) namedColor else unnamedColor

        if (isHighlighted) {
            canvas.drawRoundRect(frame, cornerRadius, cornerRadius, highlightFillPaint)
        }
        shadowPaint.strokeWidth = dp(if (isHighlighted) 6f else 4f)
        canvas.drawRoundRect(frame, cornerRadius, cornerRadius, shadowPaint)
        framePaint.color = color
        framePaint.strokeWidth = dp(if (isHighlighted) 3.5f else 2f)
        framePaint.pathEffect = if (face.hasName) null else dashEffect
        canvas.drawRoundRect(frame, cornerRadius, cornerRadius, framePaint)

        val text = when {
            face.name != null && face.isToReview -> "${face.name}?"
            face.name != null -> face.name
            isEditable -> unnamedLabel
            else -> unknownLabel
        }
        val label = drawLabel(
            canvas = canvas,
            text = text,
            frame = frame,
            backgroundColor = if (isNamed) labelBackgroundColor else unnamedColor,
            textColor = if (isNamed) Color.WHITE else Color.BLACK,
        )

        hitAreas += face to listOf(
            RectF(frame).apply { inset(-touchExtra, -touchExtra) },
            label,
        )
    }

    /**
     * Draws the label under the frame, or above it if there is no room.
     *
     * @return the label area
     */
    private fun drawLabel(
        canvas: Canvas,
        text: String,
        frame: RectF,
        backgroundColor: Int,
        textColor: Int,
    ): RectF {
        val ellipsized = TextUtils.ellipsize(
            text, labelTextPaint, labelMaxWidth, TextUtils.TruncateAt.END
        ).toString()
        val textWidth = labelTextPaint.measureText(ellipsized)
        val fontMetrics = labelTextPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val labelWidth = textWidth + 2 * labelPaddingH
        val labelHeight = textHeight + 2 * labelPaddingV

        var left = frame.centerX() - labelWidth / 2
        left = left.coerceIn(0f, max(0f, width - labelWidth))
        var top = frame.bottom + labelGap
        if (top + labelHeight > height) {
            top = frame.top - labelGap - labelHeight
        }
        top = top.coerceIn(0f, max(0f, height - labelHeight))

        val label = RectF(left, top, left + labelWidth, top + labelHeight)
        labelBackgroundPaint.color = backgroundColor
        canvas.drawRoundRect(label, labelHeight / 2, labelHeight / 2, labelBackgroundPaint)
        labelTextPaint.color = textColor
        canvas.drawText(
            ellipsized,
            label.left + labelPaddingH,
            label.top + labelPaddingV - fontMetrics.ascent,
            labelTextPaint,
        )
        return label
    }

    private fun drawDraft(canvas: Canvas, draft: RectF) {
        val frame = RectF()
        if (!mapToView(draft, frame)) {
            return
        }

        // Dim everything but the draft.
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addRoundRect(frame, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.drawPath(dimPath, dimPaint)

        shadowPaint.strokeWidth = dp(4f)
        canvas.drawRoundRect(frame, cornerRadius, cornerRadius, shadowPaint)
        framePaint.color = namedColor
        framePaint.strokeWidth = dp(2.5f)
        framePaint.pathEffect = null
        canvas.drawRoundRect(frame, cornerRadius, cornerRadius, framePaint)
        canvas.drawCircle(frame.right, frame.bottom, handleRadius + dp(1.5f), shadowPaint)
        canvas.drawCircle(frame.right, frame.bottom, handleRadius, handlePaint)
    }
    // endregion

    // region Adding
    /**
     * Starts the adding mode with a draft frame in the middle of what is visible.
     */
    fun startAdding() {
        val center = mapToImage(width / 2f, height / 2f)
        val corner = mapToImage(0f, 0f)
        val oppositeCorner = mapToImage(width.toFloat(), height.toFloat())
        val visibleWidth =
            if (corner != null && oppositeCorner != null)
                (min(oppositeCorner[0], 1f) - max(corner[0], 0f)).coerceIn(0.05f, 1f)
            else
                1f
        val drawable = photoView.drawable
        val aspect =
            if (drawable != null && drawable.intrinsicHeight > 0)
                drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
            else
                1f
        val w = (visibleWidth * 0.3f).coerceIn(MIN_DRAFT_SIZE, 1f)
        // Square on the screen.
        val h = (w * aspect).coerceIn(MIN_DRAFT_SIZE, 1f)
        val cx = (center?.get(0) ?: 0.5f).coerceIn(w / 2, 1 - w / 2)
        val cy = (center?.get(1) ?: 0.5f).coerceIn(h / 2, 1 - h / 2)
        draftArea = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        invalidate()
    }

    fun stopAdding() {
        draftArea = null
        draftDrag = null
        invalidate()
    }

    private fun startDraftDragIfHit(x: Float, y: Float): Boolean {
        val draft = draftArea ?: return false
        val frame = RectF()
        if (!mapToView(draft, frame)) {
            return false
        }

        draftDrag = when {
            hypot(x - frame.right, y - frame.bottom) <= handleTouchRadius ->
                DraftDrag.RESIZE

            RectF(frame).apply { inset(-touchExtra, -touchExtra) }.contains(x, y) ->
                DraftDrag.MOVE

            else ->
                null
        }
        lastDragX = x
        lastDragY = y
        return draftDrag != null
    }

    private fun dragDraft(x: Float, y: Float) {
        val draft = draftArea ?: return
        val previous = mapToImage(lastDragX, lastDragY) ?: return
        val current = mapToImage(x, y) ?: return
        lastDragX = x
        lastDragY = y
        val dx = current[0] - previous[0]
        val dy = current[1] - previous[1]

        when (draftDrag) {
            DraftDrag.MOVE -> {
                val moveX = dx.coerceIn(-draft.left, 1f - draft.right)
                val moveY = dy.coerceIn(-draft.top, 1f - draft.bottom)
                draft.offset(moveX, moveY)
            }

            DraftDrag.RESIZE -> {
                draft.right = (draft.right + dx).coerceIn(draft.left + MIN_DRAFT_SIZE, 1f)
                draft.bottom = (draft.bottom + dy).coerceIn(draft.top + MIN_DRAFT_SIZE, 1f)
            }

            null -> return
        }
        invalidate()
    }
    // endregion

    // region Touch
    /**
     * Watches every touch for a tap on a frame here, as the photo view
     * disallows intercepting once it gets a touch.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (draftArea != null) {
            return super.dispatchTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                tappedFace = findFaceAt(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL ->
                tappedFace = null

            MotionEvent.ACTION_MOVE ->
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    tappedFace = null
                }

            MotionEvent.ACTION_UP -> {
                val face = tappedFace
                tappedFace = null
                if (face != null && event.eventTime - downTime < tapTimeout) {
                    // Cancelling the tap for the photo view,
                    // so it doesn't toggle the full screen.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    onFaceClicked?.invoke(face)
                    return true
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (draftArea == null) {
            return false
        }

        // Dragging the draft is taken, the rest goes to the photo.
        val isDragging = event.actionMasked == MotionEvent.ACTION_DOWN
                && startDraftDragIfHit(event.x, event.y)
        if (isDragging) {
            // Not to let the pager swipe.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return isDragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (draftArea == null || draftDrag == null) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                dragDraft(event.x, event.y)

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL ->
                draftDrag = null
        }
        return true
    }

    private fun findFaceAt(x: Float, y: Float): PhotoFace? =
        // Last drawn is on top.
        hitAreas
            .lastOrNull { (_, areas) -> areas.any { it.contains(x, y) } }
            ?.first
    // endregion

    companion object {
        private const val MIN_DRAFT_SIZE = 0.03f

        /**
         * Puts the photo view into a [FaceFramesLayout], once.
         *
         * @return the layout the photo view is in
         */
        fun wrap(photoView: PhotoView): FaceFramesLayout {
            val currentParent = photoView.parent
            if (currentParent is FaceFramesLayout) {
                return currentParent
            }

            val parent = currentParent as ViewGroup
            val index = parent.indexOfChild(photoView)
            val layoutParams = photoView.layoutParams
            parent.removeViewAt(index)

            val layout = FaceFramesLayout(photoView.context, photoView)
            layout.addView(
                photoView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            parent.addView(layout, index, layoutParams)
            return layout
        }
    }
}
