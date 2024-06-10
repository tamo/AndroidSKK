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
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import jp.deadend.noname.skk.databinding.ViewCandidatesBinding

class CandidateViewContainer(screen: Context, attrs: AttributeSet) : LinearLayout(screen, attrs) {
    private lateinit var binding: ViewCandidatesBinding
    private var mFontSize = -1
    private var mButtonWidth = screen.resources.getDimensionPixelSize(R.dimen.candidates_scrollbutton_width)

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ViewCandidatesBinding.bind(this)
    }

    fun initViews() {
        binding.candidateLeft.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) binding.candidates.scrollPrev()
            false
        }
        binding.candidateRight.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) binding.candidates.scrollNext()
            false
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
        binding.candidateLeft.isEnabled = left
        binding.candidateRight.isEnabled = right
        val alphaLeft = if (left) ALPHA_ON else ALPHA_OFF
        val alphaRight = if (right) ALPHA_ON else ALPHA_OFF
        binding.candidateLeft.alpha = alphaLeft / 255f
        binding.candidateRight.alpha = alphaRight / 255f
        binding.candidateLeft.background.alpha = alphaLeft
        binding.candidateRight.background.alpha = alphaRight
    }

    fun setSize(px: Int) {
        if (px == mFontSize) return

        binding.candidates.setTextSize(px)
        binding.candidates.layoutParams = LayoutParams(0, px + px / 3, 1f)
        binding.candidateLeft.layoutParams = LayoutParams(mButtonWidth, px + px / 3)
        binding.candidateRight.layoutParams = LayoutParams(mButtonWidth, px + px / 3)
        requestLayout()

        mFontSize = px
    }

    companion object {
        private const val ALPHA_OFF = 96
        private const val ALPHA_ON = 255
    }
}
