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
import android.widget.LinearLayout
import jp.deadend.noname.skk.CandidateView.Companion.LINESCALE
import jp.deadend.noname.skk.databinding.ViewCandidatesBinding
import kotlin.math.abs

class CandidateViewContainer(screen: Context, attrs: AttributeSet) : LinearLayout(screen, attrs) {
    internal lateinit var binding: ViewCandidatesBinding
    internal var lines = 1
        set(value) {
            field = value
            setSize(-1)
        }
    private lateinit var mService: SKKService
    private var mFontSize = -1

    private var mLeftEnabled = false
    private var mRightEnabled = false
    private var mActivePointerIds = mutableListOf<Int>()
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
                when (event.action and MotionEvent.ACTION_MASK) {
                    MotionEvent.ACTION_DOWN -> when {
                        view == binding.candidateLeft && mLeftEnabled -> {
                            binding.candidates.scrollPrev()
                        }
                        view == binding.candidateRight && mRightEnabled -> {
                            binding.candidates.scrollNext()
                        }
                        else -> {
                            mActivePointerIds = mutableListOf(event.getPointerId(0))
                            mDragStartLeft = mService.leftOffset
                            mDragStartX =
                                event.getRawX(event.findPointerIndex(mActivePointerIds[0]))
                            mDragging = true
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> { // 2本目以降の指追加
                        mActivePointerIds.add(event.getPointerId(event.actionIndex))
                        assert(mActivePointerIds.count() > 1)
                        mDragStartLeft = mService.leftOffset
                        mPinchStartWidth = width
                        mPinchStartDistance = abs(
                            event.getRawX(event.findPointerIndex(mActivePointerIds[1])) -
                                    event.getRawX(event.findPointerIndex(mActivePointerIds[0]))
                        )
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (mActivePointerIds.count() > 1) { // 幅の調整
                            val currentDistance = abs(
                                event.getRawX(event.findPointerIndex(mActivePointerIds[1])) -
                                        event.getRawX(event.findPointerIndex(mActivePointerIds[0]))
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
                            mService.setInputViewWidth(newWidth)
                        } else if (mDragging) { // 位置の調整
                            val newLeftOffset = mDragStartLeft + (
                                    event.getRawX(event.findPointerIndex(mActivePointerIds[0])) -
                                            mDragStartX
                                    ).toInt()
                            mService.leftOffset =
                                newLeftOffset.coerceIn(0, mService.mScreenWidth - width)
                            mService.setInputView(null)
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        mActivePointerIds.remove(event.getPointerId(event.actionIndex))
                        if (mActivePointerIds.count() == 1) {
                            saveWidth()
                            // もう一度ドラッグに戻る
                            mDragStartLeft = mService.leftOffset
                            mDragStartX =
                                event.getRawX(event.findPointerIndex(mActivePointerIds[0]))
                        } else if (mActivePointerIds.isEmpty()) {
                            if (mDragging) {
                                if (mDragStartLeft != mService.leftOffset) {
                                    saveLeft(mService.leftOffset)
                                }
                                mDragging = false
                            } else {
                                view.performClick() // ???
                            }
                        }
                    }
                }
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        binding.candidateLeft.setOnTouchListener(onTouchListener)
        binding.candidateRight.setOnTouchListener(onTouchListener)
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
        binding.candidateLeft.alpha = alpha / 255f
        binding.candidateRight.alpha = alpha / 255f
        binding.candidateLeft.background.alpha = alpha
        binding.candidateRight.background.alpha = alpha
    }

    fun setScrollButtonsEnabled(left: Boolean, right: Boolean) {
        mLeftEnabled = left
        mRightEnabled = right
        val alphaLeft = if (left) ALPHA_ON else ALPHA_OFF
        val alphaRight = if (right) ALPHA_ON else ALPHA_OFF
        binding.candidateLeft.alpha = alphaLeft / 255f
        binding.candidateRight.alpha = alphaRight / 255f
        binding.candidateLeft.background.alpha = alphaLeft
        binding.candidateRight.background.alpha = alphaRight
    }

    // 負数のときはフォントを変更せず行数だけ更新する
    fun setSize(px: Int) {
        if (px > 0) {
            if (px == mFontSize) return
            binding.candidates.setTextSize(px)
            mFontSize = px
        }
        val height = (px * lines * LINESCALE).toInt()
        binding.candidates.layoutParams = LayoutParams(0, height, 1f)
        requestLayout()
    }

    companion object {
        private const val ALPHA_OFF = 96
        private const val ALPHA_ON = 255
    }
}
