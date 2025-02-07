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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Construct a CandidateView for showing suggested words for completion.
 * @param context
 * @param attrs
 */
class CandidateView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private lateinit var mContainer: CandidateViewContainer
    private lateinit var mService: SKKService
    private val mSuggestions = mutableListOf<String>()
    private var mSelectedIndex = 0

    private var mChosenIndex = 0

    private var mTouchX = OUT_OF_BOUNDS
    private var mTouchY = OUT_OF_BOUNDS
    private val mSelectionHighlight: Drawable?
    private var mScrollPixels = 0

    private val mWordWidth = IntArray(MAX_SUGGESTIONS)
    private val mWordX = IntArray(MAX_SUGGESTIONS)
    private val mWordL = IntArray(MAX_SUGGESTIONS)

    private var mDrawing = false
    private var mScrolled = false

    private val mColorNormal: Int
    private val mColorRecommended: Int
    private val mColorOther: Int
    private val mTextPath: Path
    private val mPaint = Paint()
    private val mLineHeight
        get() = (mPaint.textSize * LINESCALE).toInt()

    private var mTargetScrollX = 0

    private var mTotalWidth = 0

    private val mGestureDetector: GestureDetector

    private var mScrollX = 0

    init {
        val r = context.resources

        mSelectionHighlight = ResourcesCompat.getDrawable(r, R.drawable.suggest_scroll_button_bg, null)
        mSelectionHighlight?.state = intArrayOf(
                android.R.attr.state_enabled,
                android.R.attr.state_focused,
                android.R.attr.state_window_focused,
                android.R.attr.state_pressed
        )

        setBackgroundColor(ResourcesCompat.getColor(r, R.color.candidate_background, null))

        mColorNormal = ResourcesCompat.getColor(r, R.color.candidate_normal, null)
        mColorRecommended = ResourcesCompat.getColor(r, R.color.candidate_recommended, null)
        mColorOther = ResourcesCompat.getColor(r, R.color.candidate_other, null)
        mTextPath = Path()

        mScrollPixels = r.getDimensionPixelSize(R.dimen.candidates_scroll_size)

        mPaint.apply {
            color = mColorNormal
            isAntiAlias = true
            textSize = r.getDimensionPixelSize(R.dimen.candidate_font_height).toFloat()
            strokeWidth = 0f
        }

        mGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
            ): Boolean {
                val width = width
                mScrolled = true
                mScrollX = (scrollX + distanceX.toInt()).coerceIn(0, mTotalWidth - width)
                mTargetScrollX = mScrollX
                invalidate()
                return true
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

    fun setContainer(c: CandidateViewContainer) {
        mContainer = c
    }

    fun setTextSize(px: Int) {
        mPaint.textSize = px.toFloat()
    }

    public override fun computeHorizontalScrollRange() =  mTotalWidth

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
        setMeasuredDimension(measuredWidth, resolveSize(mLineHeight * mContainer.lines, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        mDrawing = true
        super.onDraw(canvas)

        val paint = mPaint
        val touchX = mTouchX
        val touchY = mTouchY
        val scrollX = scrollX
        val scrolled = mScrolled
        val y = (paint.textSize * (LINESCALE - 1) / 2 - paint.ascent()).toInt()

        mSuggestions.forEachIndexed { i, suggestion ->
            paint.color = mColorNormal
            if (touchX + scrollX >= mWordX[i]
                && touchX + scrollX < mWordX[i] + mWordWidth[i]
                && touchY in (mWordL[i] * mLineHeight)..<((mWordL[i] + 1) * mLineHeight)
                && !scrolled
            ) {
                canvas.translate(mWordX[i].toFloat(), (mWordL[i] * mLineHeight).toFloat())
                mSelectionHighlight?.setBounds(0, 0, mWordWidth[i], mLineHeight)
                mSelectionHighlight?.draw(canvas)
                canvas.translate((-mWordX[i]).toFloat(), (-mWordL[i] * mLineHeight).toFloat())
                mSelectedIndex = i
            }

            if (i == mChosenIndex) {
                paint.isFakeBoldText = true
                paint.color = mColorRecommended
            } else {
                paint.color = mColorOther
            }
            if (skkPrefs.originalColor) { // 高コントラストテキストの設定を無視
                paint.getTextPath(
                    suggestion, 0, suggestion.length,
                    (mWordX[i] + X_GAP).toFloat(), (y + mWordL[i] * mLineHeight).toFloat(),
                    mTextPath
                )
                canvas.drawPath(mTextPath, paint)
            } else {
                canvas.drawText(
                    suggestion,
                    (mWordX[i] + X_GAP).toFloat(), (y + mWordL[i] * mLineHeight).toFloat(),
                    paint
                )
            }
            paint.color = mColorOther
            canvas.drawLine(
                mWordX[i] + mWordWidth[i] + 0.5f, (mWordL[i] + 0f) * mLineHeight + 1f,
                mWordX[i] + mWordWidth[i] + 0.5f, (mWordL[i] + 1f) * mLineHeight - 1f,
                paint
            )
            paint.isFakeBoldText = false
        }

        if (scrolled && mTargetScrollX != getScrollX()) scrollToTarget()
        mDrawing = false
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
        val left = targetX > 0
        val right = targetX + width < mTotalWidth
        mContainer.setScrollButtonsEnabled(left, right)
    }

    fun setContents(list: List<String>?, kanjiKey: String) {
        MainScope().launch {
            while (mDrawing) delay(50)
            mSuggestions.clear()
            list?.take(MAX_SUGGESTIONS)?.forEach { str ->
                str.indexOf(";").let { semicolon ->
                    if (semicolon == -1) {
                        processConcatAndMore(str, kanjiKey)
                    } else {
                        processConcatAndMore(str.substring(0, semicolon), kanjiKey) +
                                ";" +
                                processConcatAndMore(str.substring(semicolon + 1, str.length), "#")
                    }.let {
                        mSuggestions.add(it)
                    }
                }
            }
            scrollTo(0, 0)
            mScrollX = 0
            mTargetScrollX = 0
            mTouchX = OUT_OF_BOUNDS
            mSelectedIndex = -1
            mChosenIndex = 0

            // Compute the total width
            var lineN = 0
            var lineX = 0
            var lineW = 0
            var totalLineW = 0
            mTotalWidth = mSuggestions.map { suggestion ->
                mPaint.measureText(suggestion).toInt().coerceAtLeast(mLineHeight / 2) + X_GAP * 2
            }.foldIndexed(0) { i, _, wordWidth ->
                // 改行
                if (lineW != 0 && lineW + wordWidth > width) {
                    lineN = (lineN + 1) % mContainer.lines
                    if (lineN == 0) {
                        // width より長いエントリがあるときはハミ出た部分だけ次画面として
                        // width 単位でのスクロールからズレのないようにする
                        lineX += ceil(totalLineW / width.toDouble()).toInt() * width
                        totalLineW = 0
                    }
                    lineW = 0
                }

                // 登録
                mWordWidth[i] = wordWidth
                mWordX[i] = lineX + lineW
                mWordL[i] = lineN
                lineW += wordWidth
                totalLineW = lineW.coerceAtLeast(totalLineW)
                lineX + totalLineW
            }

            setScrollButtonsEnabled(0)
            invalidate()
        }
    }

//    fun getContent(index: Int) = mSuggestions.getOrElse(index) { "" }

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
        updateScrollPosition(if (targetX < mTotalWidth) targetX else mTotalWidth - width)
    }

    private fun updateScrollPosition(targetX: Int) {
        mScrollX = scrollX
        if (targetX != mScrollX) {
            mTargetScrollX = targetX
            setScrollButtonsEnabled(targetX)
            invalidate()
            mScrolled = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        if (mSuggestions.isEmpty()) { // ドラッグで位置調整
            return mContainer.binding.candidateLeft.dispatchTouchEvent(me)
        }
        // スクロールした時にはここで処理されて終わりのようだ。ソースの頭で定義している。
        if (mGestureDetector.onTouchEvent(me)) { return true }

        val action = me.action
        val x = me.x.toInt()
        val y = me.y.toInt()
        mTouchX = x
        mTouchY = y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mScrolled = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // よってここのコードは生きていない。使用されない。
                if (y <= 0) {
                    // Fling up!?
                    if (mSelectedIndex >= 0) {
                        mService.pickCandidateViewManually(mSelectedIndex)
                        mSelectedIndex = -1
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                // ここは生きている。
                if (!mScrolled) {
                    if (mSelectedIndex >= 0) {
                        mService.pickCandidateViewManually(mSelectedIndex)
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                mSelectedIndex = -1
                mTouchX = OUT_OF_BOUNDS
                invalidate()
            }
        }
        return true
    }

    fun choose(chosenIndex: Int) {
        val targetX = mWordX[chosenIndex]
        val leftEdge = targetX - (targetX % width)
        scrollTo(leftEdge, scrollY)
        setScrollButtonsEnabled(leftEdge)
        invalidate()
        mScrolled = false
        mChosenIndex = chosenIndex
    }

    companion object {
        private const val OUT_OF_BOUNDS = -1
        private const val MAX_SUGGESTIONS = 2000 // 絵文字は1500程度ある
        private const val X_GAP = 5
        internal const val LINESCALE = 1.3
    }
}
