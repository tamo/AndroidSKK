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
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View.OnTouchListener
import android.widget.FrameLayout
import android.widget.LinearLayout
import jp.deadend.noname.skk.CandidatesView.Companion.LINE_SCALE
import jp.deadend.noname.skk.databinding.ViewCandidatesBinding
import kotlin.math.abs

class CandidatesViewContainer(screen: Context, attrs: AttributeSet) : LinearLayout(screen, attrs) {
    internal lateinit var binding: ViewCandidatesBinding
    internal var lines = 0
        set(value) {
            field = value
            setSize(-1)
        }
    private lateinit var mService: SKKService

    private val mActivePointers = mutableListOf<Pair<Int, Float>>()
    private var mDragging = false
    private var mDragStartLeft = 0
    private var mDragStartX = 0f
    private var mPinchStartWidth = 0
    private var mPinchStartDistance = 0f

    fun setService(service: SKKService) {
        mService = service
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ViewCandidatesBinding.bind(this)
    }

    fun initViews() {
        val onTouchListener = OnTouchListener { view, event ->
            try {
                val id = event.getPointerId(event.actionIndex)
                val idx = mActivePointers.indexOfFirst { it.first == id }
                val x = event.getRawX(event.findPointerIndex(id))
                when (event.action and MotionEvent.ACTION_MASK) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> if (!view.performClick()) {
                        mActivePointers.add(id to x)
                        mDragStartLeft = mService.leftOffset
                        if (mActivePointers.count() == 1) {
                            mDragStartX = x
                            mDragging = true
                        } else {
                            mPinchStartWidth = width
                            mPinchStartDistance = abs(
                                mActivePointers.last().second - mActivePointers.first().second
                            )
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        assert(idx != -1)
                        mActivePointers[idx] = id to x
                        if (mActivePointers.count() > 1) { // 幅の調整
                            val currentDistance = abs(
                                mActivePointers.last().second - mActivePointers.first().second
                            )
                            val newWidth =
                                (mPinchStartWidth - mPinchStartDistance + currentDistance)
                                    .toInt().coerceIn(
                                        resources.getDimensionPixelSize(R.dimen.keyboard_minimum_width),
                                        mService.mScreenWidth
                                    )
                            val newLeftOffset =
                                mDragStartLeft + (mPinchStartDistance - currentDistance).toInt() / 2
                            mService.leftOffset =
                                newLeftOffset.coerceIn(0, mService.mScreenWidth - newWidth)
                            mService.inputViewWidth = newWidth
                        } else if (mDragging) { // 位置の調整
                            val newLeftOffset = mDragStartLeft + (
                                    mActivePointers.first().second - mDragStartX
                                    ).toInt()
                            mService.leftOffset =
                                newLeftOffset.coerceIn(0, mService.mScreenWidth - width)
                            mService.setInputView(null)
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        mActivePointers.removeAt(idx)
                        if (mActivePointers.count() == 1) {
                            saveWidth()
                            // もう一度ドラッグに戻る
                            mDragStartLeft = mService.leftOffset
                            mDragStartX = mActivePointers.first().second
                        } else if (mActivePointers.isEmpty()) {
                            if (mDragging) {
                                if (mDragStartLeft != mService.leftOffset) {
                                    saveLeft(mService.leftOffset)
                                }
                                mDragging = false
                            }
                            // DOWN で performClick() しているので else は無視
                        }
                    }

                    else -> dLog(event.toString())
                }
                true
            } catch (e: Exception) {
                dLog(e.stackTraceToString())
                false
            }
        }
        binding.candidatesLeft.setOnTouchListener(onTouchListener)
        binding.candidatesRight.setOnTouchListener(onTouchListener)
    }

    private fun saveLeft(leftOffset: Int) {
        val centerRate =
            if (mService.mScreenWidth <= width) 0.5f
            else (leftOffset * 2 + width) / 2.0f / mService.mScreenWidth
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyCenterLand = centerRate
            else -> skkPrefs.keyCenterPort = centerRate
        }
    }

    private fun saveWidth() {
        setSize(-1)
        val widthToSave = width * 100 /
                if (mService.isFlickWidth) 100 else skkPrefs.keyWidthQwertyZoom
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE ->
                skkPrefs.keyWidthLand = widthToSave.coerceIn(101, mService.mScreenWidth)

            else -> skkPrefs.keyWidthPort = widthToSave.coerceIn(101, mService.mScreenWidth)
        }
    }

    fun setAlpha(alpha: Int) {
        binding.candidates.background.alpha = alpha
        binding.candidatesLeft.alpha = alpha / 255f
        binding.candidatesRight.alpha = alpha / 255f
        binding.candidatesLeft.background.alpha = alpha
        binding.candidatesRight.background.alpha = alpha
    }

    // 負数のときはフォントを変更せず行数だけ更新する
    fun setSize(px: Int) {
        if (px > 0) {
            binding.candidates.setTextSize(px)
        }
        val buttonSize = resources.getDimensionPixelSize(R.dimen.candidates_scroll_button_width)
        val width = mService.inputViewWidth - buttonSize * 2
        val height = if (lines > 0) (px * lines * LINE_SCALE).toInt() else buttonSize
        binding.frame.layoutParams = LayoutParams(width, height)
        binding.candidates.layoutParams = FrameLayout.LayoutParams(width, height)
        requestLayout()
    }
}

class CandidatesViewImageButton(screen: Context, attrs: AttributeSet?, defStyleAttr: Int) :
    androidx.appcompat.widget.AppCompatImageButton(screen, attrs, defStyleAttr) {
    constructor(screen: Context, attrs: AttributeSet?) : this(screen, attrs, 0)
    constructor(screen: Context) : this(screen, null)

    private val side: String
    var active = false
        set(value) {
            field = value
            background.alpha = if (value) skkPrefs.activeAlpha else skkPrefs.inactiveAlpha
            alpha = background.alpha / 255f
        }

    init {
        context.obtainStyledAttributes(attrs, intArrayOf(R.attr.side)).apply {
            side = getString(0) ?: "null"
            recycle()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!active) return false
        when (side) {
            "left" -> (parent as CandidatesViewContainer).binding.candidates.scrollPrev()
            "right" -> (parent as CandidatesViewContainer).binding.candidates.scrollNext()
            else -> return false
        }
        return true
    }
}
