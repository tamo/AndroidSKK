package jp.deadend.noname.skk

import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import kotlin.math.roundToInt

open class Keyboard {
    protected var defaultHorizontalGap = 0
    protected var defaultVerticalGap = 0
    protected var defaultKeyWidth = 0
    protected var defaultKeyHeight = 0

    private var leftOffset = 0

    private val mShiftKeys = arrayOf<Key?>(null, null)
    var isShifted = false
        set(value) {
            for (shiftKey in mShiftKeys) {
                if (shiftKey != null) { shiftKey.on = value }
            }
            if (field != value) { field = value }
        }

    var height = 0
        private set
    var width = 0
        private set

    val keys: MutableList<Key>  = mutableListOf()
    private val rows: MutableList<Row> = mutableListOf()
    private val modifierKeys: MutableList<Key> = mutableListOf()

    private val mDisplayWidth: Int
    private val mDisplayHeight: Int

    private var mCellWidth = 0
    private var mCellHeight = 0
    private var mGridNeighbors: Array<IntArray?> = arrayOfNulls(GRID_SIZE)
    private var mProximityThreshold = 0

    class Row(val parent: Keyboard) {
        var defaultWidth = 0
        var defaultHeight = 0
        var defaultHorizontalGap = 0
        var verticalGap = 0

        var keys: MutableList<Key> = mutableListOf()

        var rowEdgeFlags = 0

        constructor(res: Resources, parent: Keyboard, parser: XmlResourceParser): this(parent) {
            var a = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard)
            defaultWidth = getDimensionOrFraction(
                a, R.styleable.Keyboard_keyWidth, parent.mDisplayWidth, parent.defaultKeyWidth
            )
            defaultHeight = getDimensionOrFraction(
                a, R.styleable.Keyboard_keyHeight, parent.mDisplayHeight, parent.defaultKeyHeight
            )
            defaultHorizontalGap = getDimensionOrFraction(
                a, R.styleable.Keyboard_horizontalGap, parent.mDisplayWidth, parent.defaultHorizontalGap
            )
            verticalGap = getDimensionOrFraction(
                a, R.styleable.Keyboard_verticalGap, parent.mDisplayHeight, parent.defaultVerticalGap
            )
            a.recycle()
            a = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard_Row)
            rowEdgeFlags = a.getInt(R.styleable.Keyboard_Row_rowEdgeFlags, 0)
            a.recycle()
        }
    }

    class Key(parent: Row) {
        var codes: IntArray = intArrayOf()
        var label: CharSequence = ""
        var icon: Drawable? = null
        var iconPreview: Drawable? = null
        var width: Int
        var height: Int
        var horizontalGap: Int
        var sticky = false
        var repeatable = false

        var text: CharSequence? = null
        var popupCharacters: CharSequence? = null
        var popupResId = 0

        var x = 0
        var y = 0

        var pressed = false
        var on = false

        var edgeFlags: Int

        var isModifier = false

        private val keyboard: Keyboard

        init {
            keyboard = parent.parent
            height = parent.defaultHeight
            width = parent.defaultWidth
            horizontalGap = parent.defaultHorizontalGap
            edgeFlags = parent.rowEdgeFlags
        }

        constructor(res: Resources, parent: Row, x: Int, y: Int, parser: XmlResourceParser) : this(parent) {
            this.x = x
            this.y = y
            var a = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard)
            width = getDimensionOrFraction(
                a, R.styleable.Keyboard_keyWidth, keyboard.mDisplayWidth, parent.defaultWidth
            )
            height = getDimensionOrFraction(
                a, R.styleable.Keyboard_keyHeight, keyboard.mDisplayHeight, parent.defaultHeight
            )
            horizontalGap = getDimensionOrFraction(
                a, R.styleable.Keyboard_horizontalGap, keyboard.mDisplayWidth, parent.defaultHorizontalGap
            )
            a.recycle()
            a = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard_Key)
            this.x += horizontalGap
            val codesValue = TypedValue()
            a.getValue(R.styleable.Keyboard_Key_codes, codesValue)
            if (codesValue.type == TypedValue.TYPE_INT_DEC || codesValue.type == TypedValue.TYPE_INT_HEX) {
                codes = intArrayOf(codesValue.data)
            } else if (codesValue.type == TypedValue.TYPE_STRING) {
                codes = codesValue.string.toString()
                            .split(",").map { it.trim().toInt() }.toIntArray()
            }
            iconPreview = a.getDrawable(R.styleable.Keyboard_Key_iconPreview)
            iconPreview?.let {
                iconPreview?.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
            }
            popupCharacters = a.getText(R.styleable.Keyboard_Key_popupCharacters)
            popupResId = a.getResourceId(R.styleable.Keyboard_Key_popupKeyboard, 0)
            repeatable = a.getBoolean(R.styleable.Keyboard_Key_isRepeatable, false)
            isModifier = a.getBoolean(R.styleable.Keyboard_Key_isModifier, false)
            sticky = a.getBoolean(R.styleable.Keyboard_Key_isSticky, false)
            edgeFlags = a.getInt(R.styleable.Keyboard_Key_keyEdgeFlags, 0)
            edgeFlags = edgeFlags or parent.rowEdgeFlags
            icon = a.getDrawable(R.styleable.Keyboard_Key_keyIcon)
            icon?.let {
                it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
            }
            label = a.getText(R.styleable.Keyboard_Key_keyLabel) ?: ""
            text = a.getText(R.styleable.Keyboard_Key_keyOutputText)
//            if (codes.isEmpty() && !TextUtils.isEmpty(label)) {
//                codes = intArrayOf(label[0].code)
//            }
            a.recycle()
        }

        fun press() { pressed = true }
        fun release() {
            pressed = false
            if (sticky) { on != on }
        }

        fun isInside(x: Int, y: Int): Boolean {
            val leftEdge = edgeFlags and EDGE_LEFT > 0
            val rightEdge = edgeFlags and EDGE_RIGHT > 0
            val topEdge = edgeFlags and EDGE_TOP > 0
            val bottomEdge = edgeFlags and EDGE_BOTTOM > 0
            return ((x >= this.x || (leftEdge && x <= this.x + this.width))
                    && (x < this.x + this.width || (rightEdge && x >= this.x))
                    && (y >= this.y || (topEdge && y <= this.y + this.height))
                    && (y < this.y + this.height || (bottomEdge && y >= this.y)))
        }

        fun squaredDistanceFrom(x: Int, y: Int): Int {
            val xDist = this.x + width / 2 - x
            val yDist = this.y + height / 2 - y
            return xDist * xDist + yDist * yDist
        }

        val currentDrawableState: IntArray
            get() {
                return if (on) {
                    if (pressed) KEY_STATE_PRESSED_ON else KEY_STATE_NORMAL_ON
                } else {
                    if (sticky) {
                        if (pressed) KEY_STATE_PRESSED_OFF else KEY_STATE_NORMAL_OFF
                    } else {
                        if (pressed) KEY_STATE_PRESSED else KEY_STATE_NORMAL
                    }
                }
            }

        companion object {
            private val KEY_STATE_NORMAL_ON = intArrayOf(
                android.R.attr.state_checkable,
                android.R.attr.state_checked
            )
            private val KEY_STATE_PRESSED_ON = intArrayOf(
                android.R.attr.state_pressed,
                android.R.attr.state_checkable,
                android.R.attr.state_checked
            )
            private val KEY_STATE_NORMAL_OFF = intArrayOf(
                android.R.attr.state_checkable
            )
            private val KEY_STATE_PRESSED_OFF = intArrayOf(
                android.R.attr.state_pressed,
                android.R.attr.state_checkable
            )
            private val KEY_STATE_NORMAL = intArrayOf()
            private val KEY_STATE_PRESSED = intArrayOf(
                android.R.attr.state_pressed
            )
        }
    }

    constructor(context: Context, xmlLayoutResId: Int, width: Int, height: Int) {
        mDisplayWidth = width
        mDisplayHeight = height
        defaultKeyWidth = mDisplayWidth / 10
        defaultKeyHeight = defaultKeyWidth
        loadKeyboard(context, context.resources.getXml(xmlLayoutResId))
    }

    constructor(context: Context, xmlLayoutResId: Int) {
        val dm = context.resources.displayMetrics
        mDisplayWidth = dm.widthPixels
        mDisplayHeight = dm.heightPixels
        defaultKeyWidth = mDisplayWidth / 10
        defaultKeyHeight = defaultKeyWidth
        loadKeyboard(context, context.resources.getXml(xmlLayoutResId))
    }

    constructor(
        context: Context, layoutTemplateResId: Int,
        characters: CharSequence, columns: Int, horizontalPadding: Int
    ) : this(context, layoutTemplateResId) {
        var x = 0
        var y = 0
        var column = 0
        width = 0
        val row = Row(this)
        row.defaultHeight = defaultKeyHeight
        row.defaultWidth = defaultKeyWidth
        row.defaultHorizontalGap = defaultHorizontalGap
        row.verticalGap = defaultVerticalGap
        row.rowEdgeFlags = EDGE_TOP or EDGE_BOTTOM
        val maxColumns = if (columns == -1) Int.MAX_VALUE else columns
        for (c in characters) {
            if (column >= maxColumns
                || x + defaultKeyWidth + horizontalPadding > mDisplayWidth
            ) {
                x = 0
                y += defaultVerticalGap + defaultKeyHeight
                column = 0
            }
            val key = Key(row)
            key.x = x
            key.y = y
            key.label = c.toString()
            key.codes = intArrayOf(c.code)
            column++
            x += key.width + key.horizontalGap
            keys.add(key)
            row.keys.add(key)
            if (x > width) { width = x }
        }
        height = y + defaultKeyHeight
        rows.add(row)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if ((newWidth == width || newWidth == width - leftOffset) && newHeight == height) { return }

        var totalHeight = 0
        var maxWidth = 0
        for (row in rows) {
            var totalWidth = 0
            for (key in row.keys) { totalWidth += key.horizontalGap + key.width }
            if (totalWidth > maxWidth) { maxWidth = totalWidth }

                totalHeight += row.defaultHeight + row.verticalGap
        }

        val hScaleFactor = newWidth.toFloat() / maxWidth
        val vScaleFactor = newHeight.toFloat() / totalHeight
        var x: Int
        var y = 0
        for (row in rows) {
            row.defaultHeight = (row.defaultHeight * vScaleFactor).toInt()
            row.verticalGap = (row.verticalGap * vScaleFactor).toInt()
            x = 0
            for (key in row.keys) {
                key.width = (key.width * hScaleFactor).toInt()
                key.horizontalGap = (key.horizontalGap * hScaleFactor).toInt()
                key.x = x + key.horizontalGap
                x += key.horizontalGap + key.width
                key.height = row.defaultHeight
                key.y = y
            }
            y += row.defaultHeight + row.verticalGap
        }

        width = newWidth
        height = newHeight

        computeNearestNeighbors()
    }

    fun resizeByPercentageOfScreen(newWidth: Int, newHeight: Int) {
        resize(mDisplayWidth*newWidth/100, mDisplayHeight*newHeight/100)
    }

    fun setLeftOffset(keyboardPosition: String) {
        val xOffset = when (keyboardPosition) {
            "right" -> mDisplayWidth - (width - leftOffset)
            "center" -> ((mDisplayWidth - (width - leftOffset)) / 2f).toInt()
            else -> 0
        }

        for (row in rows) {
            for (i in row.keys.indices) {
                val key = row.keys[i]
                if (i == 0) {
                    key.horizontalGap -= leftOffset
                    key.horizontalGap += xOffset
                }
                key.x -= leftOffset
                key.x += xOffset
            }
        }
        width -= leftOffset
        width += xOffset
        leftOffset = xOffset

        computeNearestNeighbors()
    }

    fun setShifted(shiftState: Boolean): Boolean {
        for (shiftKey in mShiftKeys) {
            if (shiftKey != null) { shiftKey.on = shiftState }
        }
        if (isShifted != shiftState) {
            isShifted = shiftState
            return true
        }
        return false
    }

    private fun computeNearestNeighbors() {
        // Round-up so we don't have any pixels outside the grid
        mCellWidth = (width + GRID_WIDTH - 1) / GRID_WIDTH
        mCellHeight = (height + GRID_HEIGHT - 1) / GRID_HEIGHT
        val indices = IntArray(keys.size)
        val gridWidth = GRID_WIDTH * mCellWidth
        val gridHeight = GRID_HEIGHT * mCellHeight
        for (x in 0 until gridWidth step mCellWidth) {
            for (y in 0 until gridHeight step mCellHeight) {
                var count = 0
                for (i in keys.indices) {
                    val key = keys[i]
                    if (key.squaredDistanceFrom(x, y) < mProximityThreshold
                        || key.squaredDistanceFrom(x + mCellWidth - 1, y) < mProximityThreshold
                        || key.squaredDistanceFrom(x + mCellWidth - 1, y + mCellHeight - 1) < mProximityThreshold
                        || key.squaredDistanceFrom(x, y + mCellHeight - 1) < mProximityThreshold
                    ) {
                        indices[count++] = i
                    }
                }
                val cell = IntArray(count)
                System.arraycopy(indices, 0, cell, 0, count)
                mGridNeighbors[y / mCellHeight * GRID_WIDTH + x / mCellWidth] = cell
            }
        }
    }

    fun getNearestKeys(x: Int, y: Int): IntArray {
        if (mGridNeighbors[0] == null) { computeNearestNeighbors() }
        if (x in 0 until width && y in 0 until height) {
            val index = (y / mCellHeight) * GRID_WIDTH + x / mCellWidth
            if (index < GRID_SIZE) {
                mGridNeighbors[index]?.let { return it }
            }
        }
        return IntArray(0)
    }

    protected fun createRowFromXml(res: Resources, parser: XmlResourceParser): Row {
        return Row(res, this, parser)
    }

    protected fun createKeyFromXml(
        res: Resources, parent: Row, x: Int, y: Int, parser: XmlResourceParser
    ): Key {
        return Key(res, parent, x, y, parser)
    }

    private fun loadKeyboard(context: Context, parser: XmlResourceParser) {
        var inKey = false
        var inRow = false
        var row = 0
        var x = 0
        var y = 0
        var key: Key? = null
        var currentRow: Row? = null
        val res = context.resources
        try {
            var event: Int
            while (parser.next().also { event = it } != XmlResourceParser.END_DOCUMENT) {
                when (event) {
                    XmlResourceParser.START_TAG -> {
                        when (parser.name) {
                            TAG_ROW -> {
                                inRow = true
                                x = 0
                                currentRow = createRowFromXml(res, parser)
                                rows.add(currentRow)
                            }
                            TAG_KEY -> {
                                currentRow?.let { crow ->
                                    inKey = true
                                    key = createKeyFromXml(res, crow, x, y, parser)
                                    key?.let {
                                        keys.add(it)
                                        when (it.codes[0]) {
                                            KEYCODE_SHIFT -> {
                                                // Find available shift key slot and put this shift key in it
                                                for (i in mShiftKeys.indices) {
                                                    if (mShiftKeys[i] == null) {
                                                        mShiftKeys[i] = it
                                                        break
                                                    }
                                                }
                                                modifierKeys.add(it)
                                            }
                                            KEYCODE_ALT -> modifierKeys.add(it)
                                        }
                                        crow.keys.add(it)
                                    }
                                }
                            }
                            TAG_KEYBOARD -> parseKeyboardAttributes(res, parser)
                        }
                    }
                    XmlResourceParser.END_TAG -> {
                        when {
                            inKey -> {
                                inKey = false
                                key?.let {
                                    x += it.horizontalGap + it.width
                                    if (x > width) { width = x }
                                }
                            }
                            inRow -> {
                                inRow = false
                                currentRow?.let {
                                    y += it.verticalGap
                                    y += it.defaultHeight
                                    row++
                                }
                            }
                            else -> {
                                // TODO: error or extend?
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error:$e")
            e.printStackTrace()
        }

        height = y - defaultVerticalGap
    }

    private fun parseKeyboardAttributes(res: Resources, parser: XmlResourceParser) {
        val a = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard)
        defaultKeyWidth = getDimensionOrFraction(
            a, R.styleable.Keyboard_keyWidth, mDisplayWidth, mDisplayWidth / 10
        )
        defaultKeyHeight = getDimensionOrFraction(
            a, R.styleable.Keyboard_keyHeight, mDisplayHeight, 50
        )
        defaultHorizontalGap = getDimensionOrFraction(
            a, R.styleable.Keyboard_horizontalGap, mDisplayWidth, 0
        )
        defaultVerticalGap = getDimensionOrFraction(
            a, R.styleable.Keyboard_verticalGap, mDisplayHeight, 0
        )
        mProximityThreshold = (defaultKeyWidth * SEARCH_DISTANCE).toInt()
        mProximityThreshold *= mProximityThreshold // Square it for comparison
        a.recycle()
    }

    fun resetKeys() {
        for (key in keys) {
            key.on = false
            key.pressed = false
        }
    }

    companion object {
        const val TAG = "Keyboard"

        // Keyboard XML Tags
        private const val TAG_KEYBOARD = "Keyboard"
        private const val TAG_ROW = "Row"
        private const val TAG_KEY = "Key"
        const val EDGE_LEFT = 0x01
        const val EDGE_RIGHT = 0x02
        const val EDGE_TOP = 0x04
        const val EDGE_BOTTOM = 0x08
        const val KEYCODE_SHIFT = -1
        const val KEYCODE_MODE_CHANGE = -2
        const val KEYCODE_CANCEL = -3
        const val KEYCODE_DONE = -4
        const val KEYCODE_DELETE = -5
        const val KEYCODE_ALT = -6

        // Variables for pre-computing nearest keys.
        private const val GRID_WIDTH = 10
        private const val GRID_HEIGHT = 5
        private const val GRID_SIZE = GRID_WIDTH * GRID_HEIGHT
        private const val SEARCH_DISTANCE = 1.8f

        fun getDimensionOrFraction(a: TypedArray, index: Int, base: Int, defValue: Int): Int {
            val value = a.peekValue(index) ?: return defValue
            return when (value.type) {
                TypedValue.TYPE_DIMENSION -> a.getDimensionPixelOffset(index, defValue)
                TypedValue.TYPE_FRACTION -> a.getFraction(index, base, base, defValue.toFloat()).roundToInt()
                else -> defValue
            }
        }
    }
}