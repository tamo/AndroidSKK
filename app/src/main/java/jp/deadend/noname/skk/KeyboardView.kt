package jp.deadend.noname.skk

import android.content.Context
import android.graphics.*
import android.graphics.Paint.Align
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.util.TypedValue
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.widget.PopupWindow
import android.widget.TextView
import java.util.*
import kotlin.math.abs

open class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int = R.attr.keyboardViewStyle,
    defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes), View.OnClickListener {

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

    private var mKeyboard = Keyboard(context, R.xml.keys_null)
    private var mCurrentKeyIndex = NOT_A_KEY
    private var mLabelTextSize = 0
    private var mKeyTextSize = 0
    private var mKeyTextColor = 0
    private var mShadowRadius = 0f
    private var mShadowColor = 0
    private var mPreviewText: TextView? = null
    private val mPreviewPopup = PopupWindow(context)
    private var mPreviewTextSizeLarge = 0
    private var mPreviewOffset = 0
    private var mPreviewHeight = 0
    private val mCoordinates = IntArray(2)  // working variable
    private val mPopupKeyboard = PopupWindow(context)
    private var mMiniKeyboardOnScreen = false
    private var mPopupParent: View
    private var mMiniKeyboardOffsetX = 0
    private var mMiniKeyboardOffsetY = 0
    private val mMiniKeyboardCache: MutableMap<Keyboard.Key, View> = mutableMapOf()

    protected var onKeyboardActionListener: OnKeyboardActionListener? = null

    private var mVerticalCorrection = 0

    var isPreviewEnabled = true
    var backgroundAlpha = 255

    private var mLastX = 0
    private var mLastY = 0
    private var mStartX = 0
    private var mStartY = 0
    private val mPaint = Paint()
    private val mPadding = Rect(0, 0, 0, 0)
    private var mDownTime: Long = 0
    private var mLastMoveTime: Long = 0
    private var mLastKey = 0
    private var mLastCodeX = 0
    private var mLastCodeY = 0
    private var mCurrentKey = NOT_A_KEY
    private var mDownKey = NOT_A_KEY
    private var mLastKeyTime: Long = 0
    private var mCurrentKeyTime: Long = 0
    private var mGestureDetector: GestureDetector? = null
    private var mRepeatKeyIndex = NOT_A_KEY
    private var mPopupLayout = 0
    private var mAbortKey = false
    private var mInvalidatedKey: Keyboard.Key? = null
    private val mClipRegion = Rect(0, 0, 0, 0)
    private var mPossiblePoly = false
    private val mSwipeTracker = SwipeTracker()
    private val mSwipeThreshold: Int
    private val mDisambiguateSwipe: Boolean

    // Variables for dealing with multiple pointers
    private var mOldPointerCount = 1
    private var mOldPointerX = 0f
    private var mOldPointerY = 0f
    private var mKeyBackground: Drawable? = null

    // For multi-tap
    private var mLastSentIndex = 0
    private var mTapCount = 0
    private var mLastTapTime: Long = 0
    private var mInMultiTap = false
    private val mPreviewLabel = StringBuilder(1)

    private var mDrawPending = false
    private val mDirtyRect = Rect()
    private var mBuffer: Bitmap? = null
    private var mKeyboardChanged = false
    private var mCanvas: Canvas? = null

    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SHOW_PREVIEW -> showKey(msg.arg1)
                MSG_REMOVE_PREVIEW -> mPreviewText?.visibility = INVISIBLE
                MSG_REPEAT -> if (repeatKey()) {
                    val repeat = Message.obtain(this, MSG_REPEAT)
                    sendMessageDelayed(repeat, REPEAT_INTERVAL.toLong())
                }
                MSG_LONGPRESS -> openPopupIfRequired()
            }
        }
    }

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
                    mLabelTextSize = a.getDimensionPixelSize(attr, 14)
                R.styleable.KeyboardView_popupLayout ->
                    mPopupLayout = a.getResourceId(attr, 0)
                R.styleable.KeyboardView_shadowColor ->
                    mShadowColor = a.getColor(attr, 0)
                R.styleable.KeyboardView_shadowRadius ->
                    mShadowRadius = a.getFloat(attr, 0f)
            }
        }
        a.recycle()

        if (previewLayout != 0) {
            mPreviewText =
                (context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                    .inflate(previewLayout, null) as TextView
            mPreviewTextSizeLarge = mPreviewText?.textSize?.toInt() ?: 0
            mPreviewPopup.contentView = mPreviewText
            mPreviewPopup.setBackgroundDrawable(null)
        } else {
            isPreviewEnabled = false
        }
        mPreviewPopup.isTouchable = false

        mPopupKeyboard.setBackgroundDrawable(null)
        mPopupParent = this

        mPaint.isAntiAlias = true
        mPaint.textSize = mKeyTextSize.toFloat()
        mPaint.textAlign = Align.CENTER
        mPaint.alpha = 255

        mKeyBackground?.getPadding(mPadding)

        mSwipeThreshold = (500 * resources.displayMetrics.density).toInt()
        mDisambiguateSwipe = resources.getBoolean(R.bool.config_swipeDisambiguation)

        resetMultiTap()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initGestureDetector()
    }

    private fun initGestureDetector() {
        if (mGestureDetector == null) {
            mGestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
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
                        sendDownKey = if (mDisambiguateSwipe && endingVelocityX < velocityX / 4) {
                            true
                        } else {
                            swipeRight()
                            return true
                        }
                    } else if (velocityX < -mSwipeThreshold && absY < absX && deltaX < -travelX) {
                        sendDownKey = if (mDisambiguateSwipe && endingVelocityX > velocityX / 4) {
                            true
                        } else {
                            swipeLeft()
                            return true
                        }
                    } else if (velocityY < -mSwipeThreshold && absX < absY && deltaY < -travelY) {
                        sendDownKey = if (mDisambiguateSwipe && endingVelocityY > velocityY / 4) {
                            true
                        } else {
                            swipeUp()
                            return true
                        }
                    } else if (velocityY > mSwipeThreshold && absX < absY / 2 && deltaY > travelY) {
                        sendDownKey = if (mDisambiguateSwipe && endingVelocityY < velocityY / 4) {
                            true
                        } else {
                            swipeDown()
                            return true
                        }
                    }
                    if (sendDownKey) { detectAndSendKey(mDownKey, mStartX, mStartY, me1.eventTime) }
                    return false
                }
            })
            mGestureDetector?.setIsLongpressEnabled(false)
        }
    }

    var keyboard: Keyboard
        get() = mKeyboard
        set(keyboard) {
            removeMessages()
            mKeyboard = keyboard
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
            if (mKeyboard.setShifted(value)) { invalidateAllKeys() }
        }

    fun setPopupParent(v: View) {
        mPopupParent = v
    }

    fun setPopupOffset(x: Int, y: Int) {
        mMiniKeyboardOffsetX = x
        mMiniKeyboardOffsetY = y
        if (mPreviewPopup.isShowing) { mPreviewPopup.dismiss() }
    }

    fun setKeyBackground(d: Drawable) {
        mKeyBackground = d
    }

    override fun onClick(v: View) {
        dismissPopupKeyboard()
    }

    private fun adjustCase(label: CharSequence): CharSequence {
        return if (mKeyboard.isShifted && label.length < 3 && Character.isLowerCase(label[0])) {
            label.toString().uppercase(Locale.getDefault())
        } else label
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = mKeyboard.width + paddingLeft + paddingRight
        if (MeasureSpec.getSize(widthMeasureSpec) < width + 10) {
            width = MeasureSpec.getSize(widthMeasureSpec)
        }
        setMeasuredDimension(width, mKeyboard.height + paddingTop + paddingBottom)
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mKeyboard.resize(w, h)
        mBuffer = null
    }

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mDrawPending || mBuffer == null || mKeyboardChanged) { onBufferDraw() }
        mBuffer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun onBufferDraw() {
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || (mBuffer!!.width != width || mBuffer!!.height != height)) {
                // Make sure our bitmap is at least 1x1
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                mBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                mCanvas = Canvas(mBuffer!!)
            }
            invalidateAllKeys()
            mKeyboardChanged = false
        }

        mCanvas?.save()
        mCanvas?.let { canvas ->
            canvas.clipRect(mDirtyRect)
            val keyBackground = mKeyBackground
            val kbdPaddingLeft = paddingLeft
            val kbdPaddingTop = paddingTop
            val invalidKey = mInvalidatedKey
            mPaint.color = mKeyTextColor
            var drawSingleKey = false
            if (invalidKey != null && canvas.getClipBounds(mClipRegion)) {
                // Is clipRegion completely contained within the invalidated key?
                if (invalidKey.x + kbdPaddingLeft - 1 <= mClipRegion.left
                        && invalidKey.y + kbdPaddingTop - 1 <= mClipRegion.top
                        && invalidKey.x + invalidKey.width + kbdPaddingLeft + 1 >= mClipRegion.right
                        && invalidKey.y + invalidKey.height + kbdPaddingTop + 1 >= mClipRegion.bottom
                ) {
                    drawSingleKey = true
                }
            }
            keyBackground?.alpha = backgroundAlpha
            canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
            for (i in 0 until mKeyboard.keys.size) {
                val key = mKeyboard.keys[i]
                if (drawSingleKey && invalidKey !== key) { continue }
                keyBackground?.state = key.currentDrawableState

                // Switch the character to uppercase if shift is pressed
                val label = if (key.label.isEmpty()) null else adjustCase(key.label).toString()
                val icon = key.icon
                keyBackground?.bounds?.let {
                    if (key.width != it.right || key.height != it.bottom) {
                        keyBackground.setBounds(0, 0, key.width, key.height)
                    }
                }
                canvas.translate((key.x + kbdPaddingLeft).toFloat(), (key.y + kbdPaddingTop).toFloat())
                keyBackground?.draw(canvas)
                if (label != null) {
                    // For characters, use large font. For labels like "Done", use small font.
                    if (label.length > 1 && key.codes.size < 2) {
                        mPaint.textSize = mLabelTextSize.toFloat()
                        mPaint.typeface = Typeface.DEFAULT_BOLD
                    } else {
                        mPaint.textSize = mKeyTextSize.toFloat()
                        mPaint.typeface = Typeface.DEFAULT
                    }
                    // Draw a drop shadow for the text
                    mPaint.setShadowLayer(mShadowRadius, 0f, 0f, mShadowColor)
                    // Draw the text
                    canvas.drawText(
                        label,
                        (key.width - mPadding.left - mPadding.right) / 2f + mPadding.left,
                        (key.height - mPadding.top - mPadding.bottom) / 2f + (mPaint.textSize - mPaint.descent()) / 2 + mPadding.top,
                        mPaint
                    )
                    // Turn off drop shadow
                    mPaint.setShadowLayer(0f, 0f, 0f, 0)
                } else if (icon != null) {
                    val drawableX =
                        (key.width - mPadding.left - mPadding.right - icon.intrinsicWidth) / 2 + mPadding.left
                    val drawableY =
                        (key.height - mPadding.top - mPadding.bottom - icon.intrinsicHeight) / 2 + mPadding.top
                    canvas.translate(drawableX.toFloat(), drawableY.toFloat())
                    icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
                    icon.draw(canvas)
                    canvas.translate(-drawableX.toFloat(), -drawableY.toFloat())
                }
                canvas.translate((-key.x - kbdPaddingLeft).toFloat(), (-key.y - kbdPaddingTop).toFloat())
            }
            mInvalidatedKey = null
            // Overlay a dark rectangle to dim the keyboard
            if (mMiniKeyboardOnScreen) {
                mPaint.color = (BACKGROUND_DIM_AMOUNT * 0xFF).toInt() shl 24
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mPaint)
            }

            mCanvas?.restore()
            mDrawPending = false
            mDirtyRect.setEmpty()
        }
    }

    private fun getKeyIndices(x: Int, y: Int): Int {
        var primaryIndex = NOT_A_KEY
        val nearestKeyIndices = mKeyboard.getNearestKeys(x, y)
        for (i in nearestKeyIndices.indices) {
            if (mKeyboard.keys[nearestKeyIndices[i]].isInside(x, y)) {
                primaryIndex = nearestKeyIndices[i]
            }
        }

        return primaryIndex
    }

    private fun detectAndSendKey(index: Int, x: Int, y: Int, eventTime: Long) {
        if (index != NOT_A_KEY && index < mKeyboard.keys.size) {
            val key = mKeyboard.keys[index]
            val text = key.text
            if (text != null) {
                onKeyboardActionListener?.onText(text)
                onKeyboardActionListener?.onRelease(NOT_A_KEY)
            } else {
                var code = key.codes[0]
                //TextEntryState.keyPressedAt(key, x, y);
                getKeyIndices(x, y)
                // Multi-tap
                if (mInMultiTap) {
                    if (mTapCount != -1) {
                        onKeyboardActionListener?.onKey(Keyboard.KEYCODE_DELETE)
                    } else {
                        mTapCount = 0
                    }
                    code = key.codes[mTapCount]
                }
                onKeyboardActionListener?.onKey(code)
                onKeyboardActionListener?.onRelease(code)
            }
            mLastSentIndex = index
            mLastTapTime = eventTime
        }
    }

    private fun getPreviewText(key: Keyboard.Key): CharSequence {
        return if (mInMultiTap) {
            mPreviewLabel.setLength(0)
            mPreviewLabel.append(key.codes[mTapCount.coerceAtLeast(0)].toChar())
            adjustCase(mPreviewLabel)
        } else {
            adjustCase(key.label)
        }
    }

    private fun showPreview(keyIndex: Int) {
        val oldKeyIndex = mCurrentKeyIndex
        mCurrentKeyIndex = keyIndex
        // Release the old key and press the new key
        val keys = mKeyboard.keys
        if (oldKeyIndex != mCurrentKeyIndex) {
            if (oldKeyIndex != NOT_A_KEY && keys.size > oldKeyIndex) {
                keys[oldKeyIndex].onReleased(mCurrentKeyIndex == NOT_A_KEY)
                invalidateKey(oldKeyIndex)
            }
            if (mCurrentKeyIndex != NOT_A_KEY && keys.size > mCurrentKeyIndex) {
                keys[mCurrentKeyIndex].onPressed()
                invalidateKey(mCurrentKeyIndex)
            }
        }
        if (oldKeyIndex != mCurrentKeyIndex && isPreviewEnabled) {
            mHandler.removeMessages(MSG_SHOW_PREVIEW)
            if (mPreviewPopup.isShowing) {
                if (keyIndex == NOT_A_KEY) {
                    mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_REMOVE_PREVIEW),
                        DELAY_AFTER_PREVIEW.toLong()
                    )
                }
            }
            if (keyIndex != NOT_A_KEY) {
                if (mPreviewPopup.isShowing && mPreviewText?.visibility == VISIBLE) {
                    // Show right away, if it's already visible and finger is moving around
                    showKey(keyIndex)
                } else {
                    mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_SHOW_PREVIEW, keyIndex, 0),
                        DELAY_BEFORE_PREVIEW.toLong()
                    )
                }
            }
        }
    }

    private fun showKey(keyIndex: Int) {
        mPreviewText?.let { previewText ->
            if (keyIndex < 0 || keyIndex >= mKeyboard.keys.size) { return }
            val key = mKeyboard.keys[keyIndex]
            if (key.icon != null) {
                previewText.setCompoundDrawables(
                    null, null, null,
                    if (key.iconPreview != null) key.iconPreview else key.icon
                )
                previewText.text = null
            } else {
                previewText.setCompoundDrawables(null, null, null, null)
                previewText.text = getPreviewText(key)
                if (key.label.length > 1 && key.codes.size < 2) {
                    previewText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mKeyTextSize.toFloat())
                    previewText.typeface = Typeface.DEFAULT_BOLD
                } else {
                    previewText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mPreviewTextSizeLarge.toFloat())
                    previewText.typeface = Typeface.DEFAULT
                }
            }
            previewText.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
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
                mPreviewPopup.update(popupPreviewX, popupPreviewY, popupWidth, popupHeight)
            } else {
                mPreviewPopup.width = popupWidth
                mPreviewPopup.height = popupHeight
                mPreviewPopup.showAtLocation(
                    mPopupParent, Gravity.NO_GRAVITY,
                    popupPreviewX, popupPreviewY
                )
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
        if (keyIndex < 0 || keyIndex >= mKeyboard.keys.size) { return }
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
        if (mPopupLayout == 0) { return false }
        if (mCurrentKey < 0 || mCurrentKey >= mKeyboard.keys.size) { return false }

        val result = onLongPress(mKeyboard.keys[mCurrentKey])
        if (result) {
            mAbortKey = true
            showPreview(NOT_A_KEY)
        }
        return result
    }

    protected open fun onLongPress(key: Keyboard.Key): Boolean {
        val popupKeyboardId = key.popupResId
        if (popupKeyboardId != 0) {
            var miniKeyboardContainer = mMiniKeyboardCache[key]
            val mMiniKeyboard: KeyboardView
            if (miniKeyboardContainer == null) {
                miniKeyboardContainer =
                    (context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                        .inflate(mPopupLayout, null)
                mMiniKeyboard = miniKeyboardContainer.findViewById(R.id.keyboardView)
                miniKeyboardContainer.findViewById<View>(R.id.closeButton)?.setOnClickListener(this)
                mMiniKeyboard.onKeyboardActionListener = object : OnKeyboardActionListener {
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
                val popupChars = key.popupCharacters
                val kb: Keyboard = if (popupChars != null) {
                    Keyboard(
                        context, popupKeyboardId,
                        popupChars, -1, paddingLeft + paddingRight
                    )
                } else {
                    Keyboard(context, popupKeyboardId)
                }
                mMiniKeyboard.keyboard = kb
                mMiniKeyboard.setPopupParent(this)
                miniKeyboardContainer.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
                )
                mMiniKeyboardCache[key] = miniKeyboardContainer
            } else {
                mMiniKeyboard = miniKeyboardContainer.findViewById(R.id.keyboardView)
            }
            getLocationInWindow(mCoordinates)
            var mPopupX = key.x + paddingLeft
            var mPopupY = key.y + paddingTop
            mPopupX += key.width - miniKeyboardContainer!!.measuredWidth
            mPopupY -= miniKeyboardContainer.measuredHeight
            val x = mPopupX + miniKeyboardContainer.paddingRight + mCoordinates[0]
            val y = mPopupY + miniKeyboardContainer.paddingBottom + mCoordinates[1]
            mMiniKeyboard.setPopupOffset(x.coerceAtLeast(0), y)
            mMiniKeyboard.isShifted = isShifted
            mPopupKeyboard.contentView = miniKeyboardContainer
            mPopupKeyboard.width = miniKeyboardContainer.measuredWidth
            mPopupKeyboard.height = miniKeyboardContainer.measuredHeight
            mPopupKeyboard.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
            mMiniKeyboardOnScreen = true
            //mMiniKeyboard.onTouchEvent(getTranslatedEvent(me));
            invalidateAllKeys()
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { return true }
        // Convert multi-pointer up/down events to single up/down events to
        // deal with the typical multi-pointer behavior of two-thumb typing
        val pointerCount = event.pointerCount
        var result: Boolean
        val now = event.eventTime
        if (pointerCount != mOldPointerCount) {
            if (pointerCount == 1) {
                // Send a down event for the latest pointer
                val down = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN,
                    event.x, event.y, event.metaState
                )
                result = onModifiedTouchEvent(down, false)
                down.recycle()
                // If it's an up action, then deliver the up as well.
                if (event.action == MotionEvent.ACTION_UP) {
                    result = onModifiedTouchEvent(event, true)
                }
            } else {
                // Send an up event for the last pointer
                val up = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_UP,
                    mOldPointerX, mOldPointerY, event.metaState
                )
                result = onModifiedTouchEvent(up, true)
                up.recycle()
            }
        } else {
            if (pointerCount == 1) {
                result = onModifiedTouchEvent(event, false)
                mOldPointerX = event.x
                mOldPointerY = event.y
            } else {
                // Don't do anything when 2 pointers are down and moving.
                result = true
            }
        }
        mOldPointerCount = pointerCount
        return result
    }

    private fun onModifiedTouchEvent(me: MotionEvent, possiblePoly: Boolean): Boolean {
        var touchX = me.x.toInt() - paddingLeft
        var touchY = me.y.toInt() - paddingTop
        if (touchY >= -mVerticalCorrection) { touchY += mVerticalCorrection }
        val action = me.action
        val eventTime = me.eventTime
        val keyIndex = getKeyIndices(touchX, touchY)
        mPossiblePoly = possiblePoly

        // Track the last few movements to look for spurious swipes.
        if (action == MotionEvent.ACTION_DOWN) { mSwipeTracker.clear() }
        mSwipeTracker.addMovement(me)

        // Ignore all motion events until a DOWN.
        if (mAbortKey && action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_CANCEL) {
            return true
        }

        if (mGestureDetector?.onTouchEvent(me) == true) {
            showPreview(NOT_A_KEY)
            mHandler.removeMessages(MSG_REPEAT)
            mHandler.removeMessages(MSG_LONGPRESS)
            return true
        }

        // Needs to be called after the gesture detector gets a turn, as it may have
        // displayed the mini keyboard
        if (mMiniKeyboardOnScreen && action != MotionEvent.ACTION_CANCEL) { return true }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mAbortKey = false
                mStartX = touchX
                mStartY = touchY
                mLastCodeX = touchX
                mLastCodeY = touchY
                mLastKeyTime = 0
                mCurrentKeyTime = 0
                mLastKey = NOT_A_KEY
                mCurrentKey = keyIndex
                mDownKey = keyIndex
                mDownTime = me.eventTime
                mLastMoveTime = mDownTime
                checkMultiTap(eventTime, keyIndex)
                onKeyboardActionListener?.onPress(
                    if (keyIndex != NOT_A_KEY) mKeyboard.keys[keyIndex].codes[0] else 0
                )
                if (mCurrentKey >= 0 && mKeyboard.keys[mCurrentKey].repeatable) {
                    mRepeatKeyIndex = mCurrentKey
                    mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_REPEAT), REPEAT_START_DELAY.toLong()
                    )
                    repeatKey()
                }
                if (mAbortKey) { // Delivering the key could have caused an abort
                    mRepeatKeyIndex = NOT_A_KEY
                } else {
                    if (mCurrentKey != NOT_A_KEY) {
                        mHandler.sendMessageDelayed(
                            mHandler.obtainMessage(MSG_LONGPRESS, me), LONGPRESS_TIMEOUT.toLong()
                        )
                    }
                    showPreview(keyIndex)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                var continueLongPress = false
                if (keyIndex != NOT_A_KEY) {
                    if (mCurrentKey == NOT_A_KEY) {
                        mCurrentKey = keyIndex
                        mCurrentKeyTime = eventTime - mDownTime
                    } else {
                        if (keyIndex == mCurrentKey) {
                            mCurrentKeyTime += eventTime - mLastMoveTime
                            continueLongPress = true
                        } else if (mRepeatKeyIndex == NOT_A_KEY) {
                            resetMultiTap()
                            mLastKey = mCurrentKey
                            mLastCodeX = mLastX
                            mLastCodeY = mLastY
                            mLastKeyTime = mCurrentKeyTime + eventTime - mLastMoveTime
                            mCurrentKey = keyIndex
                            mCurrentKeyTime = 0
                        }
                    }
                }
                if (!continueLongPress) {
                    // Cancel old longpress
                    mHandler.removeMessages(MSG_LONGPRESS)
                    // Start new longpress if key has changed
                    if (keyIndex != NOT_A_KEY) {
                        mHandler.sendMessageDelayed(
                            mHandler.obtainMessage(MSG_LONGPRESS, me), LONGPRESS_TIMEOUT.toLong()
                        )
                    }
                }
                showPreview(mCurrentKey)
                mLastMoveTime = eventTime
            }
            MotionEvent.ACTION_UP -> {
                removeMessages()
                if (keyIndex == mCurrentKey) {
                    mCurrentKeyTime += eventTime - mLastMoveTime
                } else {
                    resetMultiTap()
                    mLastKey = mCurrentKey
                    mLastKeyTime = mCurrentKeyTime + eventTime - mLastMoveTime
                    mCurrentKey = keyIndex
                    mCurrentKeyTime = 0
                }
                if (mCurrentKeyTime < mLastKeyTime && mCurrentKeyTime < DEBOUNCE_TIME && mLastKey != NOT_A_KEY) {
                    mCurrentKey = mLastKey
                    touchX = mLastCodeX
                    touchY = mLastCodeY
                }
                showPreview(NOT_A_KEY)
                // If we're not on a repeating key (which sends on a DOWN event)
                if (mRepeatKeyIndex == NOT_A_KEY && !mMiniKeyboardOnScreen && !mAbortKey) {
                    detectAndSendKey(mCurrentKey, touchX, touchY, eventTime)
                }
                invalidateKey(keyIndex)
                mRepeatKeyIndex = NOT_A_KEY
            }
            MotionEvent.ACTION_CANCEL -> {
                removeMessages()
                dismissPopupKeyboard()
                mAbortKey = true
                showPreview(NOT_A_KEY)
                invalidateKey(mCurrentKey)
            }
        }
        mLastX = touchX
        mLastY = touchY
        return true
    }

    private fun repeatKey(): Boolean {
        val key = mKeyboard.keys[mRepeatKeyIndex]
        detectAndSendKey(mCurrentKey, key.x, key.y, mLastTapTime)
        return true
    }

    protected open fun swipeRight() = onKeyboardActionListener?.swipeRight()
    protected open fun swipeLeft()  = onKeyboardActionListener?.swipeLeft()
    protected open fun swipeUp()    = onKeyboardActionListener?.swipeUp()
    protected open fun swipeDown()  = onKeyboardActionListener?.swipeDown()

    private fun closing() {
        if (mPreviewPopup.isShowing) { mPreviewPopup.dismiss() }
        removeMessages()
        dismissPopupKeyboard()
        mBuffer = null
        mCanvas = null
        mMiniKeyboardCache.clear()
    }

    private fun removeMessages() {
        mHandler.removeMessages(MSG_REPEAT)
        mHandler.removeMessages(MSG_LONGPRESS)
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

    fun handleBack(): Boolean {
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
        if (keyIndex == NOT_A_KEY) { return }

        val key = mKeyboard.keys[keyIndex]
        if (key.codes.size > 1) {
            mInMultiTap = true
            mTapCount =
                if (eventTime < mLastTapTime + MULTITAP_INTERVAL && keyIndex == mLastSentIndex) {
                    (mTapCount + 1) % key.codes.size
                } else {
                    -1
                }
        } else if (eventTime > mLastTapTime + MULTITAP_INTERVAL || keyIndex != mLastSentIndex) {
            resetMultiTap()
        }
    }

    private class SwipeTracker {
        val mPastX = FloatArray(NUM_PAST)
        val mPastY = FloatArray(NUM_PAST)
        val mPastTime = LongArray(NUM_PAST)
        var yVelocity = 0f
        var xVelocity = 0f
        fun clear() { mPastTime[0] = 0 }

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
            if (i == NUM_PAST && drop < 0) { drop = 0 }
            if (drop == i) { drop-- }
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
            if (i < NUM_PAST) { pastTime[i] = 0 }
        }

        @JvmOverloads
        fun computeCurrentVelocity(units: Int, maxVelocity: Float = Float.MAX_VALUE) {
            val pastX = mPastX
            val pastY = mPastY
            val pastTime = mPastTime
            val oldestX = pastX[0]
            val oldestY = pastY[0]
            val oldestTime = pastTime[0]
            var accumX = 0f
            var accumY = 0f
            var num = 0
            while (num < NUM_PAST) {
                if (pastTime[num] == 0L) { break }
                num++
            }
            for (i in 1 until num) {
                val dur = (pastTime[i] - oldestTime).toInt()
                if (dur == 0) { continue }
                val velX = (pastX[i] - oldestX) / dur * units // pixels/frame.
                accumX = if (accumX == 0f) velX else (accumX + velX) * .5f
                val velY = (pastY[i] - oldestY) / dur * units // pixels/frame.
                accumY = if (accumY == 0f) velY else (accumY + velY) * .5f
            }
            xVelocity = if (accumX < 0.0f) {
                accumX.coerceAtLeast(-maxVelocity)
            } else {
                accumX.coerceAtMost(maxVelocity)
            }
            yVelocity = if (accumY < 0.0f) {
                accumY.coerceAtLeast(-maxVelocity)
            } else {
                accumY.coerceAtMost(maxVelocity)
            }
        }

        companion object {
            const val NUM_PAST = 4
            const val LONGEST_PAST_TIME = 200
        }
    }

    companion object {
        private const val NOT_A_KEY = -1
        private val KEY_DELETE = intArrayOf(Keyboard.KEYCODE_DELETE)
        private val LONG_PRESSABLE_STATE_SET = intArrayOf(R.attr.state_long_pressable)
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REMOVE_PREVIEW = 2
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val DELAY_BEFORE_PREVIEW = 0
        private const val DELAY_AFTER_PREVIEW = 70
        private const val DEBOUNCE_TIME = 70
        private const val REPEAT_INTERVAL = 50 // ~20 keys per second
        private const val REPEAT_START_DELAY = 400
        private val LONGPRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout()
        private const val MAX_NEARBY_KEYS = 12
        private const val MULTITAP_INTERVAL = 800 // milliseconds
        private const val BACKGROUND_DIM_AMOUNT = 0.6f
    }
}