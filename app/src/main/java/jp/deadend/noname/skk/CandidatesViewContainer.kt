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
import android.util.SparseArray
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.util.isEmpty
import androidx.core.util.size
import jp.deadend.noname.skk.databinding.ViewCandidatesBinding
import kotlin.math.abs
import kotlin.math.max

class CandidatesViewContainer(screen: Context, attrs: AttributeSet) : LinearLayout(screen, attrs) {
    internal lateinit var binding: ViewCandidatesBinding
    internal var lines = 0
        set(value) {
            if (field != value) {
                field = value
                setSize(-1)
            }
        }
    internal val minHeight = resources.getDimensionPixelSize(R.dimen.candidates_scroll_button_width)
    internal val maxHeight
        get() = binding.candidates.mLineHeight * max(
            skkPrefs.candidatesNormalLines,
            skkPrefs.candidatesEmojiLines
        )

    private lateinit var mService: SKKService

    private val mActivePointers = SparseArray<Float>()
    private var mDragging = false
    private var mDragStartLeft = 0
    private var mDragStartX = 0f
    private var mPinchStartWidth = 0
    private var mPinchStartDistance = 0f
    private val mCoordinates = IntArray(2)

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
                val x = event.getRawX(event.findPointerIndex(id))
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                        mActivePointers.put(id, x)

                    MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount)
                        mActivePointers.put(event.getPointerId(i), event.getRawX(i))

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                        mActivePointers.remove(id)
                }

                // y が本当に自分の範囲内か確認
                event.setLocation(event.x, mCoordinates.let {
                    getLocationOnScreen(it)
                    event.rawY - it[1]
                })
                if (event.y < 0 || height < event.y) return@OnTouchListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (!view.performClick()) {
                            mDragStartLeft = mService.leftOffset
                            if (mActivePointers.size == 1) {
                                mDragStartX = x
                                mDragging = true
                            } else {
                                mPinchStartWidth = width
                                mPinchStartDistance = abs(
                                    mActivePointers.valueAt(1) - mActivePointers.valueAt(0)
                                )
                            }
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val rightEdge = mService.mInsets.left + mService.mRootWidth
                        if (mActivePointers.size > 1) { // 幅の調整
                            val currentDistance = abs(
                                mActivePointers.valueAt(1) - mActivePointers.valueAt(0)
                            )
                            val newWidth =
                                (mPinchStartWidth - mPinchStartDistance + currentDistance)
                                    .toInt().coerceIn(
                                        resources.getDimensionPixelSize(R.dimen.keyboard_minimum_width),
                                        mService.mRootWidth
                                    )
                            val newLeftOffset =
                                mDragStartLeft + (mPinchStartDistance - currentDistance).toInt() / 2
                            mService.leftOffset =
                                newLeftOffset.coerceIn(mService.mInsets.left, rightEdge - newWidth)
                            mService.inputViewWidth = newWidth
                        } else if (mDragging) { // 位置の調整
                            val newLeftOffset =
                                mDragStartLeft + (mActivePointers.get(id) - mDragStartX).toInt()
                            mService.leftOffset = newLeftOffset.coerceIn(
                                mService.mInsets.left, rightEdge - mService.inputViewWidth
                            )
                            mService.setInputView(null)
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        if (mActivePointers.size == 1) {
                            saveWidth()
                            // もう一度ドラッグに戻る
                            mDragStartLeft = mService.leftOffset
                            mDragStartX = mActivePointers.valueAt(0)
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
        val currentWidth = mService.inputViewWidth
        val centerRate =
            if (mService.mRootWidth <= currentWidth) 0.5f
            else (leftOffset - mService.mInsets.left + currentWidth / 2.0f) / mService.mRootWidth
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> skkPrefs.keyCenterLand = centerRate
            else -> skkPrefs.keyCenterPort = centerRate
        }
        feedback()
    }

    private fun saveWidth() {
        setSize(-1)
        val widthToSave = width * 100 /
                if (mService.isFlickWidth()) 100 else skkPrefs.keyWidthQwertyZoom
        val minWidth = resources.getDimensionPixelSize(R.dimen.keyboard_minimum_width)
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE ->
                skkPrefs.keyWidthLand = widthToSave.coerceIn(minWidth, mService.mRootWidth)

            else -> skkPrefs.keyWidthPort = widthToSave.coerceIn(minWidth, mService.mRootWidth)
        }
        feedback()
    }

    // 正常終了し save されたことを伝えたい
    private fun feedback() {
        performHapticFeedback(skkPrefs.haptic)
        foreground =
            ResourcesCompat.getColor(resources, R.color.key_checked_color, null)
                .toDrawable()
        postDelayed({ foreground = null }, 100)
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
        val height = if (lines > 0) lines * binding.candidates.mLineHeight else minHeight
        if (binding.frame.layoutParams.height != height) {
            binding.frame.layoutParams.height = height
            requestLayout() // この処理中はタッチイベントを取りこぼす!
        }
    }

    fun setTypeface(typeface: android.graphics.Typeface?) {
        binding.candidates.setTypeface(typeface)
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
