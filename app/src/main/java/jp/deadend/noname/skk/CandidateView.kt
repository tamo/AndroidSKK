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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.min

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

    private var mChoosedIndex = 0

    private var mTouchX = OUT_OF_BOUNDS
    private val mSelectionHighlight: Drawable?
    private var mScrollPixels = 0

    private val mWordWidth = IntArray(MAX_SUGGESTIONS)
    private val mWordX = IntArray(MAX_SUGGESTIONS)

    private var mScrolled = false

    private val mColorNormal: Int
    private val mColorRecommended: Int
    private val mColorOther: Int
    private val mPaint = Paint()

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
                mScrollX = scrollX
                mScrollX += distanceX.toInt()
                if (mScrollX < 0) {
                    mScrollX = 0
                }
                if (distanceX > 0 && mScrollX + width > mTotalWidth) {
                    mScrollX -= distanceX.toInt()
                }
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
        val size = mPaint.textSize.toInt()
        val desiredHeight = size + size / 3

        // Maximum possible width and desired height
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec))
    }

    private fun calculateWidths() {
        mTotalWidth = 0

        var count = mSuggestions.size
        if (count > MAX_SUGGESTIONS) {
            count = MAX_SUGGESTIONS
        }

        var x = 0
        for (i in 0 until count) {
            val suggestion = mSuggestions[i]
            val textWidth = mPaint.measureText(suggestion)
            val wordWidth = textWidth.toInt() + X_GAP * 2

            mWordX[i] = x
            mWordWidth[i] = wordWidth

            x += wordWidth
        }

        mTotalWidth = x
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val height = height
        val paint = mPaint
        val touchX = mTouchX
        val scrollX = scrollX
        val scrolled = mScrolled
        val y = ((height - paint.textSize) / 2 - paint.ascent()).toInt()

        var count = mSuggestions.size
        if (count > MAX_SUGGESTIONS) {
            count = MAX_SUGGESTIONS
        }

        for (i in 0 until count) {
            paint.color = mColorNormal
            if (touchX + scrollX >= mWordX[i]
                    && touchX + scrollX < mWordX[i] + mWordWidth[i]
                    && !scrolled
            ) {
                canvas.translate(mWordX[i].toFloat(), 0f)
                mSelectionHighlight?.setBounds(0, 0, mWordWidth[i], height)
                mSelectionHighlight?.draw(canvas)
                canvas.translate((-mWordX[i]).toFloat(), 0f)
                mSelectedIndex = i
            }

            if (i == mChoosedIndex) {
                paint.isFakeBoldText = true
                paint.color = mColorRecommended
            } else {
                paint.color = mColorOther
            }
            canvas.drawText(mSuggestions[i], (mWordX[i] + X_GAP).toFloat(), y.toFloat(), paint)
            paint.color = mColorOther
            canvas.drawLine(
                    mWordX[i].toFloat() + mWordWidth[i].toFloat() + 0.5f, 0f,
                    mWordX[i].toFloat() + mWordWidth[i].toFloat() + 0.5f, (height + 1).toFloat(),
                    paint
            )
            paint.isFakeBoldText = false
        }

        if (scrolled && mTargetScrollX != getScrollX()) scrollToTarget()
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

    fun setContents(list: List<String>?) {
        mSuggestions.clear()
        if (list != null) {
            for (str in list) {
                val semicolon = str.indexOf(";")
                val newstr =
                    if (semicolon == -1) {
                        processConcatAndEscape(str)
                    } else {
                        (processConcatAndEscape(str.substring(0, semicolon)) + ";"
                            + processConcatAndEscape(str.substring(semicolon + 1, str.length)))
                    }
                mSuggestions.add(newstr)
            }
        }
        scrollTo(0, 0)
        mScrollX = 0
        mTargetScrollX = 0
        mTouchX = OUT_OF_BOUNDS
        mSelectedIndex = -1
        mChoosedIndex = 0

        // Compute the total width
        calculateWidths()
        setScrollButtonsEnabled(0)
        invalidate()
    }

    fun getContent(index: Int) = mSuggestions.getOrElse(index) { "" }

    fun scrollPrev() {
        mScrollX = scrollX
        var i = 0
        val count = mSuggestions.size
        var firstItem = 0 // Actually just before the first item, if at the boundary
        while (i < count) {
            if (mWordX[i] < mScrollX && mWordX[i] + mWordWidth[i] >= mScrollX - 1) {
                firstItem = i
                break
            }
            i++
        }
        var leftEdge = mWordX[firstItem] + mWordWidth[firstItem] - width
        if (leftEdge < 0) {
            leftEdge = 0
        }
        updateScrollPosition(leftEdge)
    }

    fun scrollNext() {
        var i = 0
        mScrollX = scrollX
        var targetX = mScrollX
        val count = mSuggestions.size
        val rightEdge = mScrollX + width
        while (i < count) {
            if (mWordX[i] <= rightEdge && mWordX[i] + mWordWidth[i] >= rightEdge) {
                targetX = min(mWordX[i], mTotalWidth - width)
                break
            }
            i++
        }
        updateScrollPosition(targetX)
    }

    private fun updateScrollPosition(targetX: Int) {
        mScrollX = scrollX
        if (targetX != mScrollX) {
            // TODO: Animate
            mTargetScrollX = targetX
            setScrollButtonsEnabled(targetX)
            invalidate()
            mScrolled = true
        }
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        // スクロールした時にはここで処理されて終わりのようだ。ソースの頭で定義している。
        if (mGestureDetector.onTouchEvent(me)) { return true }

        val action = me.action
        val x = me.x.toInt()
        val y = me.y.toInt()
        mTouchX = x

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
                    if (mSelectedIndex >= 0) mService.pickCandidateViewManually(mSelectedIndex)
                }
                mSelectedIndex = -1
                mTouchX = OUT_OF_BOUNDS
                invalidate()
            }
        }
        return true
    }

    fun choose(choosedIndex: Int) {
        if (mWordX[choosedIndex] != scrollX) {
            scrollTo(mWordX[choosedIndex], scrollY)
            setScrollButtonsEnabled(mWordX[choosedIndex])
            invalidate()
            mScrolled = false
            mChoosedIndex = choosedIndex
        }
    }

    companion object {
        private const val OUT_OF_BOUNDS = -1
        private const val MAX_SUGGESTIONS = 150
        private const val X_GAP = 5
    }
}
