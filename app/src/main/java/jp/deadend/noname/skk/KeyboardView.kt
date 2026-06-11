package jp.deadend.noname.skk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import jp.deadend.noname.skk.engine.SKKState
import kotlin.math.abs

open class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int = R.attr.keyboardViewStyle,
    defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes), View.OnClickListener {

    @Suppress("EmptyMethod")
    interface OnKeyboardActionListener {
        fun onPress(primaryCode: Int)
        fun onRelease(primaryCode: Int)
        fun onKey(primaryCode: Int)
        fun onText(text: CharSequence)
        fun swipeLeft()
        fun swipeRight()
        fun swipeDown()
        fun swipeUp()
    }

    private lateinit var mKeyboard: Keyboard
    lateinit var mService: SKKService
    private var mCurrentPreviewKeyIndex = NOT_A_KEY
    private var mLabelTextSize = 0
    private var mKeyTextSize = 0
    private var mKeyTextColor = 0
    private var mPreviewText: TextView? = null
    private val mPreviewPopup = PopupWindow(context)
    private var mPreviewOffset = 0
    private var mPreviewHeight = 0
    private val mCoordinates = IntArray(2)  // working variable
    private val mPopupKeyboard = PopupWindow(context)
    var mMiniKeyboardOnScreen = false
    private var mPopupParent: View = this
    private var mMiniKeyboardOffsetX = 0
    private var mMiniKeyboardOffsetY = 0
    private val mMiniKeyboardCache: MutableMap<Keyboard.Key, View> = mutableMapOf()

    protected var onKeyboardActionListener: OnKeyboardActionListener? = null

    private var mVerticalCorrection = 0

    var isPreviewEnabled = true
    var backgroundAlpha = 255
    var isZenkaku = false
    var isHankaku = false

    private val mPaint = Paint()
    private val mPadding = Rect(0, 0, 0, 0)
    private var mCurrentKey = NOT_A_KEY
    private var mDownKey = NOT_A_KEY
    private var mRepeatKeyIndex = NOT_A_KEY
    private var mHasRepeated = false
    private var mPopupLayout = 0
    private var mAbortKey = false
    private var mInvalidatedKey: Keyboard.Key? = null
    private val mClipRegion = Rect(0, 0, 0, 0)
    private var mPossiblePoly = false
    private val mSwipeTracker = SwipeTracker()
    private val mSwipeThreshold = (500 * resources.displayMetrics.density).toInt()
    private val mDisambiguateSwipe = resources.getBoolean(R.bool.config_swipeDisambiguation)
    open var mFlickSensitivitySquared = 100


    // Variables for dealing with multiple pointers
    private var mActivePointerId = -1 // 有効な ID は 0 とか 1 とかなので初期値は負にしておく
    private var mOldPointerX = 0f
    private var mOldPointerY = 0f
    private var mKeyBackground: Drawable? = null

    // For multi-tap
    private var mLastSentIndex = 0
    private var mTapCount = 0
    private var mLastTapTime: Long = 0
    private var mInMultiTap = false

    private var mDrawPending = false
    private val mDirtyRect = Rect()
    private var mBuffer: Bitmap? = null
    private var mKeyboardChanged = false
    private var mCanvas: Canvas? = null

    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SHOW_PREVIEW -> showKeyInPreview(msg.arg1)
                MSG_REMOVE_PREVIEW -> {
                    mPreviewText?.visibility = INVISIBLE
                    mPreviewPopup.dismiss()
                }

                MSG_REPEAT -> if (mRepeatKeyIndex != NOT_A_KEY && !mAbortKey) {
                    detectAndSendKey(mRepeatKeyIndex, mLastTapTime)
                    mHasRepeated = true
                    if (mAbortKey) mRepeatKeyIndex = NOT_A_KEY
                    else sendMessageDelayed(
                        Message.obtain(this, MSG_REPEAT), REPEAT_INTERVAL.toLong()
                    )
                }

                MSG_LONG_PRESS -> openPopupIfRequired()
            }
        }
    }

    private val mGestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
        override fun onFling(
            me1: MotionEvent?, me2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (mPossiblePoly || me1 == null) return false
            val absX = abs(velocityX)
            val absY = abs(velocityY)
            val deltaX = me2.x - me1.x
            val deltaY = me2.y - me1.y
            val travelX = width / 2 // Half the keyboard width
            val travelY = height / 2 // Half the keyboard height
            mSwipeTracker.computeCurrentVelocity(1000)
            val endingVelocityX = mSwipeTracker.xVelocity
            val endingVelocityY = mSwipeTracker.yVelocity
            var sendDownKey = false
            if (velocityX > mSwipeThreshold && absY < absX && deltaX > travelX) {
                if (mDisambiguateSwipe && endingVelocityX < velocityX / 4) {
                    sendDownKey = true
                } else {
                    swipeRight()
                    return true
                }
            } else if (velocityX < -mSwipeThreshold && absY < absX && deltaX < -travelX) {
                if (mDisambiguateSwipe && endingVelocityX > velocityX / 4) {
                    sendDownKey = true
                } else {
                    swipeLeft()
                    return true
                }
            } else if (velocityY < -mSwipeThreshold && absX < absY && deltaY < -travelY) {
                if (mDisambiguateSwipe && endingVelocityY > velocityY / 4) {
                    sendDownKey = true
                } else {
                    swipeUp()
                    return true
                }
            } else if (velocityY > mSwipeThreshold && absX < absY / 2 && deltaY > travelY) {
                if (mDisambiguateSwipe && endingVelocityY < velocityY / 4) {
                    sendDownKey = true
                } else {
                    swipeDown()
                    return true
                }
            }
            if (sendDownKey) {
                detectAndSendKey(mDownKey, me1.eventTime)
            }
            return false
        }
    })

    init {
//        val a = context.obtainStyledAttributes(
//            attrs, R.styleable.KeyboardView, defStyleAttr, defStyleRes
//        )
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.KeyboardView, R.attr.keyboardViewStyle, R.style.KeyboardView
        )
        var previewLayout = 0
        for (i in 0 until a.indexCount) {
            when (val attr = a.getIndex(i)) {
                R.styleable.KeyboardView_keyBackground ->
                    mKeyBackground = a.getDrawable(attr)

                R.styleable.KeyboardView_verticalCorrection ->
                    mVerticalCorrection = a.getDimensionPixelOffset(attr, 0)

                R.styleable.KeyboardView_keyPreviewLayout ->
                    previewLayout = a.getResourceId(attr, 0)

                R.styleable.KeyboardView_keyPreviewOffset ->
                    mPreviewOffset = a.getDimensionPixelOffset(attr, 0)

                R.styleable.KeyboardView_keyPreviewHeight ->
                    mPreviewHeight = a.getDimensionPixelSize(attr, 80)

                R.styleable.KeyboardView_keyTextSize ->
                    mKeyTextSize = a.getDimensionPixelSize(attr, 18)

                R.styleable.KeyboardView_keyTextColor ->
                    mKeyTextColor = a.getColor(attr, -0x1000000)

                R.styleable.KeyboardView_labelTextSize ->
                    mLabelTextSize = a.getDimensionPixelSize(attr, 16)

                R.styleable.KeyboardView_popupLayout ->
                    mPopupLayout = a.getResourceId(attr, 0)
            }
        }
        a.recycle()

        if (previewLayout != 0) {
            mPreviewText =
                (context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                    .inflate(previewLayout, null) as TextView
            mPreviewPopup.apply {
                contentView = mPreviewText
                setBackgroundDrawable(null)
                isClippingEnabled = false
                animationStyle = 0
                enterTransition = null
                exitTransition = null
            }
        } else {
            isPreviewEnabled = false
        }
        mPreviewPopup.isTouchable = false

        mPopupKeyboard.setBackgroundDrawable(null)
        mPopupKeyboard.isClippingEnabled = false

        mPaint.apply {
            isAntiAlias = true
            textSize = mKeyTextSize.toFloat()
            textAlign = Align.CENTER
            alpha = 255
        }

        mKeyBackground?.getPadding(mPadding)

        isHapticFeedbackEnabled = true

        @Suppress("UsePropertyAccessSyntax")
        mGestureDetector.setIsLongpressEnabled(false)

        resetMultiTap()
    }

    open fun setService(service: SKKService) {
        mService = service
    }

    open fun setKeyState(state: SKKState): KeyboardView = this

    open fun setFlickSensitivity(sensitivity: Int) {
        mFlickSensitivitySquared = sensitivity * sensitivity
    }

    var keyboard: Keyboard
        get() = mKeyboard
        set(keyboard) {
            removeMessages()
            mKeyboard = keyboard
            mKeyboard.resetKeys()
            requestLayout()
            // Hint to reallocate the buffer if the size changed
            mKeyboardChanged = true
            invalidateAllKeys()
            mMiniKeyboardCache.clear() // Not really necessary to do every time, but will free up views
            // Switching to a different keyboard should abort any pending keys so that the key up
            // doesn't get delivered to the old or new keyboard
            mAbortKey = true // Until the next ACTION_DOWN
        }

    var isShifted: Boolean
        get() = mKeyboard.isShifted
        set(value) {
            if (mKeyboard.setShifted(value)) {
                invalidateAllKeys()
            }
        }

    var isCapsLocked: Boolean
        get() = mKeyboard.isCapsLocked
        set(value) {
            mKeyboard.isCapsLocked = value
        }

    var isFlicked = FLICK_NONE
        set(value) {
            if (field != value) {
                performHapticFeedback(skkPrefs.haptic)
                if (value != FLICK_NONE) mHandler.removeMessages(MSG_LONG_PRESS)
                else if (mCurrentKey != NOT_A_KEY) {
                    mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_LONG_PRESS), skkPrefs.longPressTimeout.toLong()
                    )
                }
                field = value
                showKeyInPreview(mCurrentPreviewKeyIndex)
                invalidateAllKeys()
            }
        }
    var flickStartX = -1f
    var flickStartY = -1f

    private fun setPopupParent(v: View) {
        mPopupParent = v
    }

    private fun setPopupOffset(x: Int, y: Int) {
        mMiniKeyboardOffsetX = x
        mMiniKeyboardOffsetY = y
        if (mPreviewPopup.isShowing) {
            mPreviewPopup.dismiss()
        }
    }

    fun setKeyBackground(d: Drawable) {
        mKeyBackground = d
        invalidateAllKeys()
    }

    internal var mBoldTypeface: Typeface = Typeface.DEFAULT_BOLD
    fun setTypeface(typeface: Typeface?) {
        mPaint.typeface = typeface
        mBoldTypeface =
            if (typeface == null) Typeface.DEFAULT_BOLD
            else Typeface.create(typeface, Typeface.BOLD)
        invalidateAllKeys()
    }

    override fun onClick(v: View) {
        dismissPopupKeyboard()
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (size < width + 10) size else mKeyboard.width + paddingLeft + paddingRight

        setMeasuredDimension(width, mKeyboard.height + paddingTop + paddingBottom)
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mKeyboard.resize(w, h)
        mBuffer = null
    }

    public override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        super.onDraw(canvas)
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            onBufferDraw()
        }
        mBuffer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun onBufferDraw() {
        if (width == 0 || height == 0) return
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || (mBuffer!!.width != width || mBuffer!!.height != height)) {
                // Make sure our bitmap is at least 1x1
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                mBuffer = createBitmap(w, h)
                mCanvas = Canvas(mBuffer!!)
            }
            invalidateAllKeys()
            mKeyboardChanged = false
        }

        mCanvas?.withSave {
            this.let { canvas ->
                canvas.clipRect(mDirtyRect)
                val keyBackground = mKeyBackground
                val kbdPaddingLeft = paddingLeft
                val kbdPaddingTop = paddingTop
                val invalidKey = mInvalidatedKey
                mPaint.color = mKeyTextColor
                val drawSingleKey = (
                        invalidKey != null
                                && canvas.getClipBounds(mClipRegion)
                                // Is clipRegion completely contained within the invalidated key?
                                && invalidKey.x + kbdPaddingLeft - 1 <= mClipRegion.left
                                && invalidKey.y + kbdPaddingTop - 1 <= mClipRegion.top
                                && invalidKey.x + invalidKey.width + kbdPaddingLeft + 1 >= mClipRegion.right
                                && invalidKey.y + invalidKey.height + kbdPaddingTop + 1 >= mClipRegion.bottom
                        )
                keyBackground?.alpha = backgroundAlpha
                canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
                val labelZoom = skkPrefs.keyLabelZoom / 100f

                for (key in mKeyboard.keys) {
                    if (drawSingleKey && invalidKey !== key) {
                        continue
                    }
                    keyBackground?.state = key.currentDrawableState

                    // Switch the character to uppercase if shift is pressed
                    val useDown = isFlicked == FLICK_DOWN && key.labels.down.isNotEmpty()
                    val useShift =
                        isShifted xor (isFlicked == FLICK_UP) && key.labels.shifted.isNotEmpty()
                    val lines = when {
                        useDown -> key.labels.splitDown()
                        useShift -> key.labels.splitShifted()
                        else -> key.labels.split()
                    }

                    val icon = key.icon
                    keyBackground?.bounds?.let {
                        if (key.width != it.right || key.height != it.bottom) {
                            keyBackground.setBounds(0, 0, key.width, key.height)
                        }
                    }
                    canvas.translate(
                        (key.x + kbdPaddingLeft).toFloat(),
                        (key.y + kbdPaddingTop).toFloat()
                    )
                    keyBackground?.draw(canvas)

                    if (lines.isNotEmpty()) {
                        val numLines = lines.size
                        // For characters, use large font. For labels like "Done", use small font.
                        if (numLines == 1 && lines[0].length > 1 && key.codes.main.size < 2) {
                            mPaint.textSize = mLabelTextSize.toFloat()
                            mPaint.textScaleX = 1.0f
                        } else {
                            mPaint.textSize = mKeyTextSize.toFloat()
                            mPaint.textScaleX = when {
                                isZenkaku -> 1.4f
                                isHankaku && (numLines <= 1 || lines[0].length == 1) -> 0.7f
                                else -> 1.0f
                            }
                        }

                        val sizeDefault = mPaint.textSize
                        val lineScale = 1.05f // 行間
                        val centerX = (key.width + mPadding.left - mPadding.right) / 2f
                        val centerY = (key.height + mPadding.top - mPadding.bottom) / 2f
                        val currentTypeface = mPaint.typeface

                        when (numLines) {
                            3 -> {
                                val h0 = 0.4f * labelZoom * sizeDefault
                                val h1 = 0.6f * labelZoom * sizeDefault
                                val h2 = 0.4f * labelZoom * sizeDefault
                                val totalHeight = h0 + h1 + h2

                                mPaint.textSize = h0
                                mPaint.typeface = currentTypeface
                                canvas.drawText(
                                    lines[0],
                                    centerX,
                                    centerY + lineScale * ((totalHeight - mPaint.descent()) / 2f - (h1 + h2)),
                                    mPaint
                                )

                                mPaint.textSize = h1
                                mPaint.typeface = currentTypeface
                                val drawY1 =
                                    centerY + lineScale * ((totalHeight - mPaint.descent()) / 2f - h2)
                                if (!drawCenterLabel(lines, 1, canvas, centerX, drawY1, mPaint)) {
                                    mPaint.typeface = mBoldTypeface
                                    canvas.drawText(lines[1], centerX, drawY1, mPaint)
                                }

                                mPaint.textSize = h2
                                mPaint.typeface = currentTypeface
                                canvas.drawText(
                                    lines[2],
                                    centerX,
                                    centerY + lineScale * ((totalHeight - mPaint.descent()) / 2f),
                                    mPaint
                                )
                            }

                            1 -> {
                                val h0 = labelZoom * sizeDefault
                                mPaint.textSize = h0
                                mPaint.typeface = mBoldTypeface
                                canvas.drawText(
                                    lines[0],
                                    centerX,
                                    centerY + lineScale * ((h0 - mPaint.descent()) / 2f),
                                    mPaint
                                )
                            }

                            else -> {
                                // ここには到達しないはず
                                mPaint.typeface = mBoldTypeface
                                lines.forEach { line ->
                                    canvas.drawText(line, centerX, centerY, mPaint)
                                }
                            }
                        }

                        // 英数キーボードの上下フリックやシフトを上下に小さく表示
                        val savedAlign = mPaint.textAlign
                        val altLines =
                            if (useShift) key.labels.split() else key.labels.splitShifted()
                        val hasAlt =
                            if (useShift) key.labels.main.isNotEmpty() else key.labels.shifted.isNotEmpty()
                        if (hasAlt
                            && altLines.getOrNull(0) != lines[0].uppercase()
                            && altLines.getOrNull(0) != lines[0].lowercase()
                        ) {
                            mPaint.textAlign = Align.LEFT
                            mPaint.textSize = mLabelTextSize * .7f * labelZoom
                            mPaint.typeface = currentTypeface
                            canvas.drawText(
                                altLines[0],
                                mPadding.left + mPaint.textSize * .5f,
                                mPadding.top + mPaint.textSize * 1.5f,
                                mPaint
                            )
                        }
                        if (key.labels.down.isNotEmpty()) {
                            mPaint.textAlign = Align.RIGHT
                            mPaint.textSize = mLabelTextSize * .7f * labelZoom
                            mPaint.typeface = mBoldTypeface
                            canvas.drawText(
                                key.labels.splitDown()[0],
                                key.width - mPadding.right - mPaint.textSize * .5f,
                                key.height - mPadding.bottom - mPaint.textSize * .6f,
                                mPaint
                            )
                        }
                        mPaint.textAlign = savedAlign
                    } else if (icon != null) {
                        val w = (icon.intrinsicWidth * labelZoom).toInt()
                        val h = (icon.intrinsicHeight * labelZoom).toInt()
                        val drawableX =
                            (key.width - mPadding.left - mPadding.right - w) / 2 + mPadding.left
                        val drawableY =
                            (key.height - mPadding.top - mPadding.bottom - h) / 2 + mPadding.top
                        canvas.translate(drawableX.toFloat(), drawableY.toFloat())
                        icon.setBounds(0, 0, w, h)
                        icon.draw(canvas)
                        canvas.translate(-drawableX.toFloat(), -drawableY.toFloat())
                    }
                    canvas.translate(
                        (-key.x - kbdPaddingLeft).toFloat(),
                        (-key.y - kbdPaddingTop).toFloat()
                    )
                }
                mInvalidatedKey = null
                // Overlay a dark rectangle to dim the keyboard
                if (mMiniKeyboardOnScreen) {
                    mPaint.color = (BACKGROUND_DIM_AMOUNT * 0xFF).toInt() shl 24
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mPaint)
                }

            }
            mDrawPending = false
            mDirtyRect.setEmpty()
        }
    }

    protected open fun drawCenterLabel(
        lines: List<String>, @Suppress("SameParameterValue") lineIndex: Int,
        canvas: Canvas, x: Float, y: Float, paint: Paint
    ): Boolean = false

    private fun getKeyIndex(x: Int, y: Int): Int {
        return mKeyboard.getNearestKeys(x, y).findLast {
            mKeyboard.keys[it].isInside(x, y)
        } ?: NOT_A_KEY
    }

    private fun detectAndSendKey(index: Int, eventTime: Long) {
        if (index != NOT_A_KEY && index < mKeyboard.keys.size) {
            val key = mKeyboard.keys[index]
            val text = key.text
            if (text != null) {
                onKeyboardActionListener?.onText(text)
                onKeyboardActionListener?.onRelease(NOT_A_KEY)
            } else {
                var code = key.codes.main[0]
                // Multi-tap
                if (mInMultiTap) {
                    if (mTapCount != -1) { // 前回の入力を取り消す
                        val codesLength = key.codes.main.size
                        val prevCount = (mTapCount + codesLength - 1) % codesLength
                        val prevCode = mKeyboard.keys[mLastSentIndex].codes.main[prevCount]
                        if (prevCode > 0) {
                            onKeyboardActionListener?.onKey(Keyboard.KEYCODE_DELETE)
                        }
                    } else {
                        mTapCount = 0
                    }
                    code = key.codes.main[mTapCount]
                }
                onKeyboardActionListener?.onKey(code)
                onKeyboardActionListener?.onRelease(code)
            }
            mLastSentIndex = index
            mLastTapTime = eventTime
        }
    }

    private fun getPreviewText(key: Keyboard.Key): String = when {
        key.codes.main[0] == Keyboard.KEYCODE_SHIFT -> when (isFlicked) {
            FLICK_NONE -> "SHIFT"
            else -> "CAPSLOCK"
        }
//        mInMultiTap -> {
//            Char(key.codes[mTapCount.coerceAtLeast(0)]).toString()
//            // シフト時の toUpper とか必要ならするけど今はアルファベットの multiTap がない
//        }
        else -> when (isFlicked) {
            FLICK_LEFT -> "Ctrl-"
            FLICK_RIGHT -> "Alt-"
            else -> ""
        } + when {
            isFlicked == FLICK_DOWN && key.labels.down.isNotEmpty() -> key.labels.splitDown()
            isShifted xor (isFlicked == FLICK_UP) && key.labels.shifted.isNotEmpty() -> key.labels.splitShifted()
            else -> key.labels.split()
        }[0]
    }

    private fun pressKey(keyIndex: Int) {
        if (keyIndex < 0 || keyIndex >= mKeyboard.keys.size) return
        performHapticFeedback(skkPrefs.haptic)
        mKeyboard.keys[keyIndex].press()
        invalidateKey(keyIndex)
        showPreview(keyIndex)
    }

    private fun releaseKey(keyIndex: Int) {
        if (keyIndex < 0 || keyIndex >= mKeyboard.keys.size) return
        mKeyboard.keys[keyIndex].release()
        invalidateKey(keyIndex)
        hidePreview()
    }


    private fun showPreview(keyIndex: Int) {
        val oldKeyIndex = mCurrentPreviewKeyIndex
        mCurrentPreviewKeyIndex = keyIndex

        if (isPreviewEnabled && oldKeyIndex != mCurrentPreviewKeyIndex) {
            mHandler.removeMessages(MSG_SHOW_PREVIEW)
            if (keyIndex != NOT_A_KEY) {
                if (mPreviewPopup.isShowing && mPreviewText?.visibility == VISIBLE) {
                    // Show right away, if it's already visible and finger is moving around
                    showKeyInPreview(keyIndex)
                } else {
                    mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_SHOW_PREVIEW, keyIndex, 0),
                        DELAY_BEFORE_PREVIEW.toLong()
                    )
                }
            }
        }
    }

    private fun hidePreview() {
        mCurrentPreviewKeyIndex = NOT_A_KEY
        mHandler.removeMessages(MSG_SHOW_PREVIEW)
        if (isPreviewEnabled && mPreviewPopup.isShowing) {
            mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_REMOVE_PREVIEW),
                DELAY_AFTER_PREVIEW.toLong()
            )
        }
    }

    private fun showKeyInPreview(keyIndex: Int) {
        mPreviewText?.let { previewText ->
            if (keyIndex < 0 || keyIndex >= mKeyboard.keys.size) return
            val key = mKeyboard.keys[keyIndex]
            if (key.icon != null && key.codes.main[0] != Keyboard.KEYCODE_SHIFT) return
            previewText.apply {
                text = getPreviewText(key)
                typeface = mPaint.typeface
                measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
            }
            val popupWidth = previewText.measuredWidth.coerceAtLeast(
                key.width + previewText.paddingLeft + previewText.paddingRight
            )
            val popupHeight = mPreviewHeight
            val lp = previewText.layoutParams
            if (lp != null) {
                lp.width = popupWidth
                lp.height = popupHeight
            }
            var popupPreviewX = key.x - previewText.paddingLeft + paddingLeft
            var popupPreviewY = key.y - popupHeight + mPreviewOffset
            mHandler.removeMessages(MSG_REMOVE_PREVIEW)
            getLocationInWindow(mCoordinates)
            mCoordinates[0] += mMiniKeyboardOffsetX // Offset may be zero
            mCoordinates[1] += mMiniKeyboardOffsetY // Offset may be zero

            // Set the preview background state
            previewText.background.state =
                if (key.popupResId != 0) LONG_PRESSABLE_STATE_SET else EMPTY_STATE_SET
            popupPreviewX += mCoordinates[0]
            popupPreviewY += mCoordinates[1]

            // If the popup cannot be shown above the key, put it on the side
            getLocationOnScreen(mCoordinates)
            if (popupPreviewY + mCoordinates[1] < 0) {
                // If the key you're pressing is on the left side of the keyboard, show the popup on
                // the right, offset by enough to see at least one key to the left/right.
                if (key.x + key.width <= width / 2) {
                    popupPreviewX += (key.width * 2.5).toInt()
                } else {
                    popupPreviewX -= (key.width * 2.5).toInt()
                }
                popupPreviewY += popupHeight
            }

            if (mPreviewPopup.isShowing) {
                mPreviewText?.visibility = INVISIBLE
                mPreviewPopup.dismiss()
            }

            mPreviewPopup.apply {
                width = popupWidth
                height = popupHeight
                showAtLocation(mPopupParent, Gravity.NO_GRAVITY, popupPreviewX, popupPreviewY)
            }

            previewText.visibility = VISIBLE
        }
    }

    fun invalidateAllKeys() {
        mDirtyRect.union(0, 0, width, height)
        mDrawPending = true
        invalidate()
    }

    fun invalidateKey(keyIndex: Int) {
        if (keyIndex < 0 || keyIndex >= mKeyboard.keys.size) return
        val key = mKeyboard.keys[keyIndex]
        mInvalidatedKey = key
        mDirtyRect.union(
            key.x + paddingLeft, key.y + paddingTop,
            key.x + key.width + paddingLeft, key.y + key.height + paddingTop
        )
        onBufferDraw()
        invalidate()
    }

    private fun openPopupIfRequired(): Boolean {
        // Check if we have a popup layout specified first.
        if (mPopupLayout == 0) return false
        if (mCurrentKey < 0 || mCurrentKey >= mKeyboard.keys.size) return false

        val result = onLongPress(mKeyboard.keys[mCurrentKey])
        if (result) {
            mAbortKey = true
            releaseKey(mCurrentKey)
        }
        return result
    }

    protected open fun onLongPress(key: Keyboard.Key): Boolean {
        if (!skkPrefs.useMiniKey) return false
        if (key.popupCharacters.isEmpty()) return false
        val popupKeyboardId = key.popupResId
        if (popupKeyboardId != 0) {
            val cached = mMiniKeyboardCache[key]
            val miniKeyboardContainer: View
            val miniKeyboardView: KeyboardView

            if (cached == null) {
                miniKeyboardContainer =
                    (context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                        .inflate(mPopupLayout, null)
                miniKeyboardContainer.findViewById<View>(R.id.closeButton)?.setOnClickListener(this)
                miniKeyboardView = miniKeyboardContainer.findViewById(R.id.keyboardView)
                miniKeyboardView.onKeyboardActionListener = object : OnKeyboardActionListener {
                    override fun onKey(primaryCode: Int) {
                        onKeyboardActionListener?.onKey(primaryCode)
                        dismissPopupKeyboard()
                    }

                    override fun onText(text: CharSequence) {
                        onKeyboardActionListener?.onText(text)
                        dismissPopupKeyboard()
                    }

                    override fun swipeLeft() {}
                    override fun swipeRight() {}
                    override fun swipeUp() {}
                    override fun swipeDown() {}

                    override fun onPress(primaryCode: Int) {
                        onKeyboardActionListener?.onPress(primaryCode)
                    }

                    override fun onRelease(primaryCode: Int) {
                        onKeyboardActionListener?.onRelease(primaryCode)
                    }
                }
                miniKeyboardView.keyboard = Keyboard(
                    context, popupKeyboardId,
                    mService.mRootWidth, mService.mScreenHeight,
                    key.popupCharacters, -1, paddingLeft + paddingRight
                )
                miniKeyboardView.setPopupParent(this)
                mKeyBackground?.let { miniKeyboardView.setKeyBackground(it) } // for inset
                miniKeyboardContainer.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
                )
                mMiniKeyboardCache[key] = miniKeyboardContainer
            } else {
                miniKeyboardContainer = cached
                miniKeyboardView = miniKeyboardContainer.findViewById(R.id.keyboardView)
            }

            getLocationInWindow(mCoordinates)
            val mPopupX = key.x + paddingLeft + key.width - miniKeyboardContainer.measuredWidth
            val mPopupY = key.y + paddingTop - miniKeyboardContainer.measuredHeight
            val x =
                (mPopupX + miniKeyboardContainer.paddingRight + mCoordinates[0]).coerceAtLeast(0)
            val y = mPopupY + miniKeyboardContainer.paddingBottom + mCoordinates[1]
            miniKeyboardView.setPopupOffset(x.coerceAtLeast(0), y)
            miniKeyboardView.isShifted = isShifted

            mPopupKeyboard.apply {
                contentView = miniKeyboardContainer
                width = miniKeyboardContainer.measuredWidth
                height = miniKeyboardContainer.measuredHeight
                showAtLocation(this@KeyboardView, Gravity.NO_GRAVITY, x, y)
            }
            mMiniKeyboardOnScreen = true
            //miniKeyboardView.onTouchEvent(getTranslatedEvent(me));
            invalidateAllKeys()

            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        // event.y は view 上端から測る性質上
        // 非同期に candidatesView が伸びる仕組みと相性が悪いので
        // 安定した値の取れる event.rawY から逆算しておく
        event.setLocation(event.x, mCoordinates.let {
            getLocationOnScreen(it)
            event.rawY - it[1]
        })

        // Convert multi-pointer up/down events to single up/down events to
        // deal with the typical multi-pointer behavior of two-thumb typing
        // 右手で「さ」をフリックして離さないうちに左手で「あ」をフリックするなど指が速いときの対応
        val result: Boolean
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> { // 1本目の指
                mActivePointerId = event.getPointerId(0)
                result = onModifiedTouchEvent(event, false)
                mOldPointerX = event.x
                mOldPointerY = event.y
                this.performClick() // ダミー
            }

            MotionEvent.ACTION_POINTER_DOWN -> { // 2本目以降の指
                // こっちを有効にして
                mActivePointerId = event.getPointerId(event.actionIndex)

                // 前のは up して今後無視する
                MotionEvent.obtain(event).let { up ->
                    up.action = MotionEvent.ACTION_UP
                    up.setLocation(mOldPointerX, mOldPointerY)
                    onModifiedTouchEvent(up, true)
                    up.recycle()
                }

                // そして最新のを down して使っていく
                mOldPointerX = event.getX(event.actionIndex)
                mOldPointerY = event.getY(event.actionIndex)
                MotionEvent.obtain(event).let { down ->
                    down.action = MotionEvent.ACTION_DOWN
                    down.setLocation(mOldPointerX, mOldPointerY)
                    result = onModifiedTouchEvent(down, false)
                    down.recycle()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> { // 指が減ったけど最後ではない場合
                // 最新の指以外はすでに up してあるので気にしない
                if (mActivePointerId == event.getPointerId(event.actionIndex)) {
                    // 唯一の指が無効になるので、以降の move はすべて無意味
                    mActivePointerId = -1
                    MotionEvent.obtain(event).let { up ->
                        up.action = MotionEvent.ACTION_UP
                        up.setLocation(mOldPointerX, mOldPointerY)
                        result = onModifiedTouchEvent(up, true)
                        up.recycle()
                    }
                } else {
                    result = true // すでに前借りで消費されているので true でいいのでは？
                }
            }

            MotionEvent.ACTION_UP -> { // 最後の指
                result = if (mActivePointerId == event.getPointerId(0)) {
                    mActivePointerId = -1
                    stopRepeatKey(true).let { unrepeatedKey ->
                        if (unrepeatedKey != NOT_A_KEY) { // repeatable なのに repeat しなかったキー
                            detectAndSendKey(unrepeatedKey, event.eventTime)
                        }
                        onModifiedTouchEvent(event, false)
                    }
                } else {
                    true // すでに前借りで消費されているので true でいいのでは？
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // どの指の動きで発行されたか分からないので、とにかく処理する
                // ただし有効な指が残っていない場合は無視できる
                if (mActivePointerId != -1) {
                    val activeX = event.getX(event.findPointerIndex(mActivePointerId))
                    val activeY = event.getY(event.findPointerIndex(mActivePointerId))
                    MotionEvent.obtain(event).let { move ->
                        move.setLocation(activeX, activeY)
                        result = onModifiedTouchEvent(move, false)
                        move.recycle()
                    }
                    mOldPointerX = activeX
                    mOldPointerY = activeY
                } else {
                    result = true // 無視したいイベントだから true でいいのでは？
                }
            }

            else -> result = onModifiedTouchEvent(event, false)
        }
        return result
    }

    /**
     * 計算負荷の低い擬似的な角度を計算します (0.0 から 4.0)
     * 0: 右, 1: 下, 2: 左, 3: 上
     */
    internal fun diamondAngle(x: Float, y: Float): Float {
        return if (y >= 0) {
            if (x >= 0) y / (x + y) else 1 - x / (-x + y)
        } else {
            if (x < 0) 2 - y / (-x - y) else 3 + x / (x - y)
        }
    }

    open fun onModifiedTouchEvent(me: MotionEvent, possiblePoly: Boolean): Boolean {
        val touchX = me.x.toInt() - paddingLeft
        var touchY = me.y.toInt() - paddingTop
        if (touchY >= -mVerticalCorrection) {
            touchY += mVerticalCorrection
        }
        val action = me.action
        val eventTime = me.eventTime
        val keyIndex = getKeyIndex(touchX, touchY)
        mPossiblePoly = possiblePoly

        // Track the last few movements to look for spurious swipes.
        if (action == MotionEvent.ACTION_DOWN) {
            mSwipeTracker.clear()
        }
        mSwipeTracker.addMovement(me)

        // Ignore all motion events until a DOWN.
        if (mAbortKey && action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_CANCEL) {
            return true
        }

        if (mGestureDetector.onTouchEvent(me)) {
            stopRepeatKey()
            return true
        }

        // Needs to be called after the gesture detector gets a turn, as it may have
        // displayed the mini keyboard
        if (mMiniKeyboardOnScreen && action != MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_DOWN) {
                dismissPopupKeyboard() // 消費されずここまで来たということは範囲外なので消す
            }
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mAbortKey = false
                mCurrentKey = keyIndex
                mDownKey = mCurrentKey
                checkMultiTap(eventTime, mCurrentKey)

                val key = mKeyboard.keys[mCurrentKey]
                onKeyboardActionListener?.onPress(key.codes.main.getOrNull(0) ?: 0)

                if (key.repeatable) {
                    mRepeatKeyIndex = mCurrentKey
                    mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_REPEAT), REPEAT_START_DELAY.toLong()
                    )
                }
                if (!mAbortKey) {
                    if (mCurrentKey != NOT_A_KEY) {
                        mHandler.sendMessageDelayed(
                            mHandler.obtainMessage(MSG_LONG_PRESS, me),
                            skkPrefs.longPressTimeout.toLong()
                        )
                    }
                    pressKey(mCurrentKey)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                var continueLongPress = false
                if (keyIndex != NOT_A_KEY) when (mCurrentKey) {
                    keyIndex -> continueLongPress = true
                    NOT_A_KEY -> {
                        mCurrentKey = keyIndex
                        pressKey(keyIndex)
                    }
                }
                if (!continueLongPress && keyIndex != mCurrentKey) {
                    mHandler.removeMessages(MSG_LONG_PRESS)
                }
            }

            MotionEvent.ACTION_UP -> {
                removeMessages()
                releaseKey(mCurrentKey)
                mPreviewPopup.dismiss()

                // If we're not on a repeatable key (which sends on a DOWN event)
                val key = mKeyboard.keys.getOrNull(mCurrentKey)
                if (key?.repeatable == false && !mMiniKeyboardOnScreen && !mAbortKey) {
                    detectAndSendKey(mCurrentKey, eventTime) // マルチタップのために必要
                }
                mRepeatKeyIndex = NOT_A_KEY
                isFlicked = FLICK_NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                removeMessages()
                dismissPopupKeyboard()
                mAbortKey = true
                releaseKey(mCurrentKey)
                isFlicked = FLICK_NONE
            }
        }
        return true
    }

    protected open fun swipeRight() = onKeyboardActionListener?.swipeRight()
    protected open fun swipeLeft() = onKeyboardActionListener?.swipeLeft()
    protected open fun swipeUp() = onKeyboardActionListener?.swipeUp()
    protected open fun swipeDown() = onKeyboardActionListener?.swipeDown()

    override fun performClick(): Boolean {
        if (mActivePointerId == -1) {
            Toast.makeText(context, "フリックできない環境ではほぼ使えません", Toast.LENGTH_SHORT)
                .show()
            return super.performClick()
        }
        return false
    }

    private fun closing() {
        if (mPreviewPopup.isShowing) {
            mPreviewPopup.dismiss()
        }
        removeMessages()
        dismissPopupKeyboard()
        mBuffer = null
        mCanvas = null
        mMiniKeyboardCache.clear()
    }

    // なぜか mHandler.removeMessages(MSG_REPEAT) で止まらないので
    fun stopRepeatKey(stopLong: Boolean = false): Int {
        val unrepeatedKey = if (!mHasRepeated) mRepeatKeyIndex else NOT_A_KEY
        mHasRepeated = false

        releaseKey(mCurrentKey)
        mRepeatKeyIndex = NOT_A_KEY
        mHandler.removeMessages(MSG_REPEAT)
        if (stopLong) mHandler.removeMessages(MSG_LONG_PRESS)

        return unrepeatedKey
    }

    private fun removeMessages() {
        mRepeatKeyIndex = NOT_A_KEY
        mHandler.removeMessages(MSG_REPEAT)
        mHandler.removeMessages(MSG_LONG_PRESS)
        mHandler.removeMessages(MSG_SHOW_PREVIEW)
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    private fun dismissPopupKeyboard() {
        if (mPopupKeyboard.isShowing) {
            mPopupKeyboard.dismiss()
            mMiniKeyboardOnScreen = false
            invalidateAllKeys()
        }
    }

    open fun handleBack(): Boolean {
        if (mPopupKeyboard.isShowing) {
            dismissPopupKeyboard()
            return true
        }
        return false
    }

    private fun resetMultiTap() {
        mLastSentIndex = NOT_A_KEY
        mTapCount = 0
        mLastTapTime = -1
        mInMultiTap = false
    }

    private fun checkMultiTap(eventTime: Long, keyIndex: Int) {
        if (keyIndex == NOT_A_KEY) return

        val key = mKeyboard.keys[keyIndex]
        if (key.codes.main.size > 1) {
            mInMultiTap = true
            mTapCount =
                if (eventTime < mLastTapTime + MULTI_TAP_INTERVAL && keyIndex == mLastSentIndex) {
                    (mTapCount + 1) % key.codes.main.size
                } else {
                    -1
                }
        } else if (eventTime > mLastTapTime + MULTI_TAP_INTERVAL || keyIndex != mLastSentIndex) {
            resetMultiTap()
        }
    }

    private class SwipeTracker {
        val mPastX = FloatArray(NUM_PAST)
        val mPastY = FloatArray(NUM_PAST)
        val mPastTime = LongArray(NUM_PAST)
        var yVelocity = 0f
        var xVelocity = 0f
        fun clear() {
            mPastTime[0] = 0
        }

        fun addMovement(ev: MotionEvent) {
            for (i in 0 until ev.historySize) {
                addPoint(ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalEventTime(i))
            }
            addPoint(ev.x, ev.y, ev.eventTime)
        }

        private fun addPoint(x: Float, y: Float, time: Long) {
            var drop = -1
            val pastTime = mPastTime
            var i = 0
            while (i < NUM_PAST) {
                if (pastTime[i] == 0L) {
                    break
                } else if (pastTime[i] < time - LONGEST_PAST_TIME) {
                    drop = i
                }
                i++
            }
            if (i == NUM_PAST && drop < 0) {
                drop = 0
            }
            if (drop == i) {
                drop--
            }
            val pastX = mPastX
            val pastY = mPastY
            if (drop >= 0) {
                val start = drop + 1
                val count = NUM_PAST - drop - 1
                System.arraycopy(pastX, start, pastX, 0, count)
                System.arraycopy(pastY, start, pastY, 0, count)
                System.arraycopy(pastTime, start, pastTime, 0, count)
                i -= drop + 1
            }
            pastX[i] = x
            pastY[i] = y
            pastTime[i] = time
            i++
            if (i < NUM_PAST) {
                pastTime[i] = 0
            }
        }

        @JvmOverloads
        fun computeCurrentVelocity(units: Int, maxVelocity: Float = Float.MAX_VALUE) {
            val pastX = mPastX
            val pastY = mPastY
            val pastTime = mPastTime
            val oldestX = pastX[0]
            val oldestY = pastY[0]
            val oldestTime = pastTime[0]
            var sumX = 0f
            var sumY = 0f
            var num = 0
            while (num < NUM_PAST) {
                if (pastTime[num] == 0L) break
                num++
            }
            for (i in 1 until num) {
                val dur = (pastTime[i] - oldestTime).toInt()
                if (dur == 0) continue
                val velX = (pastX[i] - oldestX) / dur * units // pixels/frame.
                sumX = if (sumX == 0f) velX else (sumX + velX) * .5f
                val velY = (pastY[i] - oldestY) / dur * units // pixels/frame.
                sumY = if (sumY == 0f) velY else (sumY + velY) * .5f
            }
            xVelocity = if (sumX < 0.0f) {
                sumX.coerceAtLeast(-maxVelocity)
            } else {
                sumX.coerceAtMost(maxVelocity)
            }
            yVelocity = if (sumY < 0.0f) {
                sumY.coerceAtLeast(-maxVelocity)
            } else {
                sumY.coerceAtMost(maxVelocity)
            }
        }

        companion object {
            const val NUM_PAST = 4
            const val LONGEST_PAST_TIME = 200
        }
    }

    companion object {
        const val FLICK_LEFT = 3
        const val FLICK_RIGHT = 2
        const val FLICK_UP = 1
        const val FLICK_NONE = 0
        const val FLICK_DOWN = -1

        private const val NOT_A_KEY = -1

        // private val KEY_DELETE = intArrayOf(Keyboard.KEYCODE_DELETE)
        private val LONG_PRESSABLE_STATE_SET = intArrayOf(R.attr.state_long_pressable)
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REMOVE_PREVIEW = 2
        private const val MSG_REPEAT = 3
        private const val MSG_LONG_PRESS = 4
        private const val DELAY_BEFORE_PREVIEW = 0
        private const val DELAY_AFTER_PREVIEW = 70
        private const val REPEAT_INTERVAL = 50 // ~20 keys per second
        private const val REPEAT_START_DELAY = 400

        // private const val MAX_NEARBY_KEYS = 12
        private const val MULTI_TAP_INTERVAL = 800 // milliseconds
        private const val BACKGROUND_DIM_AMOUNT = 0.6f
    }
}