/*
 * Copyright (C) 2008-2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package jp.deadend.noname.skk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation

internal data class CandidateInfo(
    val text: String, val annotation: String?,
    val w: Int, val x: Int, val l: Int
)

internal data class CandidateLayout(
    val candidates: List<CandidateInfo>, val totalWidth: Int,
    val kanjiKey: String, val picture: Picture? = null
) {
    companion object {
        val EMPTY = CandidateLayout(emptyList(), 0, "")
    }
}

/**
 * Construct a CandidatesView for showing suggested words for completion.
 * @param context
 * @param attrs
 */
class CandidatesView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private lateinit var mContainer: CandidatesViewContainer
    private lateinit var mService: SKKService
    private var mLayout = CandidateLayout.EMPTY
    private var mTouchedIndex = -1
    private var mLongPressed = false

    private var mCursor = 0

    private var mTouchX = OUT_OF_BOUNDS
    private var mTouchY = OUT_OF_BOUNDS
    private val mSelectionHighlight: Drawable?
    private var mScrollPixels = 0

    private var mScrolled = false
    private val mColorCursor: Int
    private val mColorOther: Int
    private val mTextPath: Path
    private val mWorkPaint = Paint()
    internal val mPaint = Paint()
    internal val mLineHeight
        get() = (mPaint.textSize * LINE_SCALE).toInt()

    private var mTargetScrollX = 0

    private val mGestureDetector: GestureDetector

    private var mScrollX = 0
    private val mCoordinates = IntArray(2)

    init {
        val r = context.resources

        mSelectionHighlight =
            ResourcesCompat.getDrawable(r, R.drawable.candidates_scroll_button_bg, context.theme)
        mSelectionHighlight?.state = intArrayOf(
            android.R.attr.state_enabled,
            android.R.attr.state_focused,
            android.R.attr.state_window_focused,
            android.R.attr.state_pressed
        )

        setBackgroundColor(ResourcesCompat.getColor(r, R.color.candidate_background, context.theme))

        mColorCursor = ResourcesCompat.getColor(r, R.color.candidate_cursor, context.theme)
        mColorOther = ResourcesCompat.getColor(r, R.color.candidate_other, context.theme)
        mTextPath = Path()

        mScrollPixels = r.getDimensionPixelSize(R.dimen.candidates_scroll_size)

        mPaint.apply {
            isAntiAlias = true
            textSize = r.getDimensionPixelSize(R.dimen.candidate_font_height).toFloat()
            strokeWidth = 0f
        }

        mGestureDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
                ): Boolean {
                    val width = width
                    mScrolled = true
                    mScrollX = (scrollX + distanceX.toInt())
                        .coerceAtMost(mLayout.totalWidth - width) // 負のこともある?
                        .coerceAtLeast(0)
                    mTargetScrollX = mScrollX
                    invalidate()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (mTouchedIndex >= 0) {
                        SKKLog.d("onLongPress: $mTouchedIndex")
                        performHapticFeedback(skkPrefs.haptic)
                        mLongPressed = true
                    }
                }
            })

        isHorizontalFadingEdgeEnabled = false
        setWillNotDraw(false)
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
    }

    /**
     * A connection back to the service to communicate with the text field
     * @param listener
     */
    fun setService(listener: SKKService) {
        mService = listener
    }

    fun setContainer(c: CandidatesViewContainer) {
        mContainer = c
    }

    fun setTextSize(px: Int) {
        mPaint.textSize = px.toFloat()
    }

    fun setTypeface(typeface: android.graphics.Typeface?) {
        mPaint.typeface = typeface
        invalidate()
    }

    public override fun computeHorizontalScrollRange() = mLayout.totalWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(50, widthMeasureSpec)
        mScrollPixels = measuredWidth / 12

        // Get the desired height of the icon menu view (last row of items does
        // not have a divider below)
        /*
        val padding = new Rect()
        mSelectionHighlight.getPadding(padding)
        val desiredHeight = mPaint.getTextSize().toInt() + mVerticalPadding
                + padding.top + padding.bottom
        */

        // Maximum possible width and desired height
        val buttonSize = resources.getDimensionPixelSize(R.dimen.candidates_scroll_button_width)
        setMeasuredDimension(
            measuredWidth,
            resolveSize(
                (mLineHeight * mContainer.lines).coerceAtLeast(buttonSize),
                heightMeasureSpec
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (mTouchedIndex >= 0 && !mScrolled) {
            val ci = mLayout.candidates[mTouchedIndex]
            canvas.withTranslation(ci.x.toFloat(), (ci.l * mLineHeight).toFloat()) {
                mSelectionHighlight?.setBounds(0, 0, ci.w, mLineHeight)
                mSelectionHighlight?.draw(this)
            }
        }

        mLayout.picture?.let { canvas.drawPicture(it) }

        if (mCursor >= 0 && mCursor < mLayout.candidates.size) {
            mWorkPaint.set(mPaint)
            mWorkPaint.apply {
                isFakeBoldText = true
                color = mColorCursor
                textAlign = Paint.Align.CENTER
            }
            canvas.drawCandidate(mLayout.candidates[mCursor], mWorkPaint, mTextPath)
        }

        if (mScrolled && mTargetScrollX != scrollX) scrollToTarget()
    }

    private fun scrollToTarget() {
        var sx = scrollX
        if (mTargetScrollX > sx) {
            sx += mScrollPixels
            if (sx >= mTargetScrollX) {
                sx = mTargetScrollX
                setScrollButtonsEnabled(sx)
            }
        } else {
            sx -= mScrollPixels
            if (sx <= mTargetScrollX) {
                sx = mTargetScrollX
                setScrollButtonsEnabled(sx)
            }
        }
        scrollTo(sx, scrollY)
        invalidate()
    }

    private fun setScrollButtonsEnabled(targetX: Int) {
        mContainer.binding.candidatesLeft.active = targetX > 0
        mContainer.binding.candidatesRight.active = targetX + width < mLayout.totalWidth
    }

    internal fun buildLayout(list: List<String>, kanjiKey: String): Pair<CandidateLayout, Int> {
        SKKLog.d("buildLayout(list=$list, kanjiKey=$kanjiKey)")
        val isEmoji = kanjiKey == "emoji"
        val viewLines =
            if (isEmoji) skkPrefs.candidatesEmojiLines else skkPrefs.candidatesNormalLines
        val displayList = mutableListOf<Pair<String, String?>>()
        list.take(MAX_CANDIDATES).forEach { rawStr ->
            val str = if (isEmoji) removeAnnotation(rawStr) else rawStr
            val main = processConcatAndMore(str.substringBefore(';'), kanjiKey)
            val anno = if (';' in str) {
                ";" + processConcatAndMore(str.substringAfter(';'), "#")
            } else null
            displayList.add(main to anno)
        }

        val candidates = mutableListOf<CandidateInfo>()
        val viewWidth = width.coerceAtLeast(1)

        // Compute the total width
        var lineN = 0
        var lineX = 0
        var lineW = 0
        var totalLineW = 0
        val oldSize = mPaint.textSize
        displayList.forEach { (main, anno) ->
            val mainW = mPaint.measureText(main)
            val annoW = anno?.let {
                mPaint.textSize = oldSize * skkPrefs.annotationRatio
                val w = mPaint.measureText(it)
                mPaint.textSize = oldSize
                w
            } ?: 0f
            val ww = if (viewLines == 0) 1f else
                (mainW + annoW).coerceAtLeast(mLineHeight * 0.7f) + X_GAP * 2

            // 改行
            if (viewLines != 0 && lineW != 0 && lineW + ww > viewWidth) {
                lineN = (lineN + 1) % viewLines
                if (lineN == 0) {
                    // width より長いエントリがあるときはハミ出た部分だけ次画面として
                    // width 単位でのスクロールからズレのないようにする
                    lineX += kotlin.math.ceil(totalLineW / viewWidth.toDouble()).toInt() * viewWidth
                    totalLineW = 0
                }
                lineW = 0
            }

            // 登録
            candidates.add(CandidateInfo(main, anno, ww.toInt(), lineX + lineW, lineN))
            lineW += ww.toInt()
            totalLineW = lineW.coerceAtLeast(totalLineW)
        }
        val totalWidth = if (candidates.isEmpty()) 0 else lineX + totalLineW

        val picture = Picture()
        if (totalWidth > 0) {
            val canvas = picture.beginRecording(totalWidth, mLineHeight * viewLines)
            val textPath = Path()
            mWorkPaint.set(mPaint)
            mWorkPaint.apply {
                color = mColorOther
                textAlign = Paint.Align.CENTER
            }

            candidates.forEach { ci ->
                canvas.drawCandidate(ci, mWorkPaint, textPath)
                canvas.drawLine(
                    ci.x + ci.w + 0.5f, (ci.l + 0f) * mLineHeight + 1f,
                    ci.x + ci.w + 0.5f, (ci.l + 1f) * mLineHeight - 1f,
                    mWorkPaint
                )
            }
            picture.endRecording()
        }

        return CandidateLayout(candidates, totalWidth, kanjiKey, picture) to viewLines
    }

    internal fun setContents(layout: CandidateLayout?, index: Int = 0) {
        layout?.candidates?.let {
            if (it.isNotEmpty()) SKKLog.d("setContents($it, index=$index)")
        }
        mLayout = layout ?: CandidateLayout.EMPTY

        scrollTo(0, 0)
        mScrollX = 0
        mTargetScrollX = 0
        mCursor = 0

        // Preserve selection if finger is down
        mTouchedIndex = (if (mTouchX != OUT_OF_BOUNDS)
            getSelectedIndex(mTouchX, mTouchY)
        else -1).also {
            if (it != mTouchedIndex) SKKLog.d("mTouchedIndex: $mTouchedIndex -> $it")
        }

        if (index != 0) setCursor(index) else {
            setScrollButtonsEnabled(scrollX)
            invalidate()
        }
    }

    fun scrollPrev() {
        mScrollX = scrollX
        val leftEdge = (mScrollX - width).coerceAtLeast(0)
        val targetX = leftEdge - (leftEdge % width)
        updateScrollPosition(targetX)
    }

    fun scrollNext() {
        mScrollX = scrollX
        val rightEdge = mScrollX + width
        val targetX = rightEdge - (rightEdge % width)
        updateScrollPosition(if (targetX < mLayout.totalWidth) targetX else mLayout.totalWidth - width)
    }

    private fun updateScrollPosition(targetX: Int) {
        mScrollX = scrollX
        if (targetX != mScrollX) {
            mTargetScrollX = targetX
            setScrollButtonsEnabled(targetX)
            invalidate()
            mScrolled = true
            performHapticFeedback(skkPrefs.haptic)
        }
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        me.setLocation(me.x, mCoordinates.let {
            getLocationOnScreen(it)
            me.rawY - it[1]
        })
        if (me.y < 0 || height < me.y) return false

        if (mLayout.candidates.isEmpty()) { // ドラッグで位置調整
            return mContainer.binding.candidatesLeft.dispatchTouchEvent(me)
        }
        // スクロールした時にはここで処理されて終わりのようだ。ソースの頭で定義している。
        if (mGestureDetector.onTouchEvent(me)) {
            return true
        }

        val action = me.action
        val x = me.x.toInt()
        val y = me.y.toInt()
        mTouchX = x
        mTouchY = y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mScrolled = false
                mLongPressed = false
                mTouchedIndex = getSelectedIndex(x, y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                // よってここのコードは生きていない。使用されない。
                if (y <= 0) {
                    // Fling up!?
                    if (mTouchedIndex >= 0) {
                        if (mLongPressed) performLongPress()
                        else performClick()
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                // ここは生きている。
                if (mLongPressed) performLongPress()
                else performClick()
            }
        }
        return true
    }

    private fun performLongPress() {
        performHapticFeedback(skkPrefs.haptic)
        mService.pickCandidatesViewManually(mTouchedIndex, unregister = true)
        resetTouchState()
    }

    override fun performClick(): Boolean {
        SKKLog.d("performClick: $mTouchedIndex")
        super.performClick()

        val rv = if (!mScrolled && mTouchedIndex >= 0) {
            mService.pickCandidatesViewManually(mTouchedIndex)
            performHapticFeedback(skkPrefs.haptic)
            true
        } else false
        resetTouchState()
        return rv
    }

    private fun resetTouchState() {
        mLongPressed = false
        mTouchedIndex = -1
        mTouchX = OUT_OF_BOUNDS
        invalidate()
    }

    internal fun setCursor(chosenIndex: Int) {
        val targetX = mLayout.candidates[chosenIndex].x
        val leftEdge = targetX - (targetX % width)
        scrollTo(leftEdge, scrollY)
        setScrollButtonsEnabled(leftEdge)
        invalidate()
        mScrolled = false
        mCursor = chosenIndex
    }

    private fun getSelectedIndex(x: Int, y: Int): Int {
        if (mLayout.candidates.isEmpty()) return -1
        val sx = scrollX
        for (i in mLayout.candidates.indices) {
            val ci = mLayout.candidates[i]
            if (x + sx >= ci.x
                && x + sx < ci.x + ci.w
                && y in (ci.l * mLineHeight)..<((ci.l + 1) * mLineHeight)
            ) {
                return i
            }
        }
        return -1
    }

    private fun Canvas.drawCandidate(ci: CandidateInfo, paint: Paint, path: Path) {
        val textSize = paint.textSize
        val annoSize = textSize * skkPrefs.annotationRatio
        val y = (textSize * (LINE_SCALE - 1) / 2 - paint.ascent()).toInt()
        val drawX = ci.x + ci.w / 2f
        val drawY = (y + ci.l * mLineHeight).toFloat()

        val mainText = ci.text
        val annoText = ci.annotation

        val mainW = paint.measureText(mainText)
        val annoW = annoText?.let {
            paint.textSize = annoSize
            val w = paint.measureText(it)
            paint.textSize = textSize
            w
        } ?: 0f
        val startX = drawX - (mainW + annoW) / 2f

        fun draw(text: String, x: Float) = if (skkPrefs.originalColor) {
            path.reset()
            paint.getTextPath(text, 0, text.length, x, drawY, path)
            drawPath(path, paint)
        } else {
            drawText(text, x, drawY, paint)
        }

        paint.textAlign.let { oldAlign ->
            paint.textAlign = Paint.Align.LEFT

            draw(mainText, startX)
            annoText?.let {
                paint.textSize = annoSize
                draw(it, startX + mainW)
                paint.textSize = textSize
            }

            paint.textAlign = oldAlign
        }
    }

    companion object {
        private const val OUT_OF_BOUNDS = -1
        private const val MAX_CANDIDATES = 2000 // 絵文字は1500程度ある
        private const val X_GAP = 5
        private const val LINE_SCALE = 1.3
    }
}
