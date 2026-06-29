package jp.deadend.noname.skk

import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.util.Xml
import kotlin.math.roundToInt

open class Keyboard {
    protected var defaultHorizontalGap = 0
    protected var defaultVerticalGap = 0
    protected var defaultKeyWidth = 0
    protected var defaultKeyHeight = 0
    protected var defaultPopupLayout = 0
    protected var defaultRepeatable = false

    private val mShiftKeys = arrayOf<Key?>(null, null)
    var isShifted = false
        set(value) {
            mShiftKeys.forEach { it?.on = value }
            field = value
        }
    var isCapsLocked = false

    var height = 0
        private set
    var width = 0
        private set
    private var bottom = 0

    val keys: MutableList<Key> = mutableListOf()
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

        constructor(res: Resources, parent: Keyboard, parser: XmlResourceParser) : this(parent) {
            res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard_Row).apply {
                defaultWidth = getDim(
                    R.styleable.Keyboard_Row_width,
                    parent.mDisplayWidth, parent.defaultKeyWidth
                )
                defaultHeight = getDim(
                    R.styleable.Keyboard_Row_height,
                    parent.mDisplayHeight, parent.defaultKeyHeight
                )
                defaultHorizontalGap = getDim(
                    R.styleable.Keyboard_Row_hGap,
                    parent.mDisplayWidth, parent.defaultHorizontalGap
                )
                verticalGap = getDim(
                    R.styleable.Keyboard_Row_vGap,
                    parent.mDisplayHeight, parent.defaultVerticalGap
                )
                rowEdgeFlags = getInt(R.styleable.Keyboard_Row_edge, 0)
                recycle()
            }
        }
    }

    class Key(val parent: Row) {
        class Labels {
            var main: String = ""
                set(value) {
                    field = value
                    mainLines = value.splitLines()
                }
            var shifted: String = ""
                set(value) {
                    field = value
                    shiftedLines = value.splitLines()
                }
            var down: String = ""
                set(value) {
                    field = value
                    downLines = value.splitLines()
                }

            var mainLines: List<String> = emptyList()
                private set
            var shiftedLines: List<String> = emptyList()
                private set
            var downLines: List<String> = emptyList()
                private set

            private fun String.splitLines() = takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()
        }

        data class Codes(
            var main: IntArray = intArrayOf(),
            var shifted: Int = 0,
            var down: Int = 0
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Codes) return false
                return main.contentEquals(other.main) &&
                        shifted == other.shifted &&
                        down == other.down
            }

            override fun hashCode(): Int {
                var result = main.contentHashCode()
                result = 31 * result + shifted
                result = 31 * result + down
                return result
            }
        }

        val labels = Labels()
        var codes = Codes()

        var icon: Drawable? = null
        var width: Int = parent.defaultWidth
        var defaultWidth: Int = width
        var height: Int = parent.defaultHeight
        var defaultHeight: Int = height
        var horizontalGap: Int = parent.defaultHorizontalGap
        var defaultHorizontalGap: Int = horizontalGap
        private var sticky = false
        var repeatable = false

        var popupCharacters = ""
        var popupLayout = 0

        var x = 0
        var y = 0

        var pressed = false
        var on = false

        private var edgeFlags: Int = parent.rowEdgeFlags

        private val keyboard: Keyboard = parent.parent

        constructor(res: Resources, parent: Row, x: Int, y: Int, parser: XmlResourceParser)
                : this(parent) {
            this.x = x
            this.y = y
            res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard_Key).apply {
                width = getDim(
                    R.styleable.Keyboard_Key_width, keyboard.mDisplayWidth, parent.defaultWidth
                )
                defaultWidth = width
                height = getDim(
                    R.styleable.Keyboard_Key_height, keyboard.mDisplayHeight, parent.defaultHeight
                )
                defaultHeight = height
                horizontalGap = getDim(
                    R.styleable.Keyboard_Key_hGap,
                    keyboard.mDisplayWidth, parent.defaultHorizontalGap
                )
                defaultHorizontalGap = horizontalGap

                this@Key.x += horizontalGap
                val codesValue = TypedValue()
                getValue(R.styleable.Keyboard_Key_codes, codesValue)
                if (codesValue.type == TypedValue.TYPE_INT_DEC || codesValue.type == TypedValue.TYPE_INT_HEX) {
                    codes.main = intArrayOf(codesValue.data)
                } else if (codesValue.type == TypedValue.TYPE_STRING) {
                    codes.main = codesValue.string.toString()
                        .split(",").map { it.trim().toInt() }.toIntArray()
                }
                codes.shifted = getInt(R.styleable.Keyboard_Key_shiftedCode, 0)
                codes.down = getInt(R.styleable.Keyboard_Key_downCode, 0)
                popupCharacters = getString(R.styleable.Keyboard_Key_popup).orEmpty()
                popupLayout =
                    getResourceId(R.styleable.Keyboard_Key_popupLayout, keyboard.defaultPopupLayout)
                repeatable =
                    getBoolean(R.styleable.Keyboard_Key_repeatable, keyboard.defaultRepeatable)
                sticky = getBoolean(R.styleable.Keyboard_Key_sticky, false)
                edgeFlags = getInt(R.styleable.Keyboard_Key_edge, 0) or parent.rowEdgeFlags
                icon = getDrawable(R.styleable.Keyboard_Key_icon)?.apply {
                    setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                }
                labels.main = getString(R.styleable.Keyboard_Key_label).orEmpty()
                labels.shifted = getString(R.styleable.Keyboard_Key_shifted).orEmpty()
                labels.down = getString(R.styleable.Keyboard_Key_down).orEmpty()

                if (codes.main.isEmpty() && labels.main.isNotEmpty()) {
                    codes.main = intArrayOf(labels.main[0].code)
                }
                if (codes.shifted == 0 && labels.shifted.isNotEmpty()) {
                    codes.shifted = labels.shifted[0].code
                }
                if (codes.down == 0 && labels.down.isNotEmpty()) {
                    codes.down = labels.down[0].code
                }
                recycle()
            }
        }

        fun press() {
            pressed = true
        }

        fun release() {
            pressed = false
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
            get() = when {
                on -> if (pressed) KEY_STATE_PRESSED_ON else KEY_STATE_NORMAL_ON
                sticky -> if (pressed) KEY_STATE_PRESSED_OFF else KEY_STATE_NORMAL_OFF
                else -> if (pressed) KEY_STATE_PRESSED else KEY_STATE_NORMAL
            }

        companion object {
            private val KEY_STATE_NORMAL_ON = intArrayOf(
                android.R.attr.state_checkable, android.R.attr.state_checked
            )
            private val KEY_STATE_PRESSED_ON = intArrayOf(
                android.R.attr.state_pressed,
                android.R.attr.state_checkable, android.R.attr.state_checked
            )
            private val KEY_STATE_NORMAL_OFF = intArrayOf(android.R.attr.state_checkable)
            private val KEY_STATE_PRESSED_OFF = intArrayOf(
                android.R.attr.state_pressed, android.R.attr.state_checkable
            )
            private val KEY_STATE_NORMAL = intArrayOf()
            private val KEY_STATE_PRESSED = intArrayOf(android.R.attr.state_pressed)
        }
    }

    constructor(context: Context, xmlLayoutResId: Int, displayWidth: Int, displayHeight: Int) {
        mDisplayWidth = displayWidth
        mDisplayHeight = displayHeight
        defaultKeyWidth = mDisplayWidth / 10
        defaultKeyHeight = defaultKeyWidth
        loadKeyboard(context, context.resources.getXml(xmlLayoutResId))
    }

    constructor(
        context: Context, layoutTemplateResId: Int,
        displayWidth: Int, displayHeight: Int,
        characters: String, columns: Int, horizontalPadding: Int
    ) : this(context, layoutTemplateResId, displayWidth, displayHeight) {
        initMiniKey(characters, columns, horizontalPadding)
    }

    private fun initMiniKey(characters: String, columns: Int, horizontalPadding: Int) {
        var x = 0
        var y = 0
        var column = 0
        width = 0
        val row = Row(this).apply {
            defaultHeight = defaultKeyHeight
            defaultWidth = defaultKeyWidth
            this.defaultHorizontalGap = this@Keyboard.defaultHorizontalGap
            verticalGap = defaultVerticalGap
            rowEdgeFlags = EDGE_TOP or EDGE_BOTTOM
        }
        val maxColumns = if (columns == -1) Int.MAX_VALUE else columns
        for (c in characters) {
            if (column >= maxColumns || x + defaultKeyWidth + horizontalPadding > mDisplayWidth) {
                x = 0
                y += defaultVerticalGap + defaultKeyHeight
                column = 0
            }
            val key = Key(row).apply {
                this.x = x
                this.y = y
                labels.main = c.toString()
                codes.main = intArrayOf(c.code)
            }
            column++
            x += key.width + key.horizontalGap
            keys.add(key)
            row.keys.add(key)
            if (x > width) width = x
        }
        height = y + defaultKeyHeight
        rows.add(row)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        if (newWidth < 1) return

        val maxWidth = rows.maxOfOrNull { row ->
            row.keys.sumOf { it.defaultHorizontalGap + it.defaultWidth }
        } ?: 0
        val totalHeight = rows.sumOf { it.defaultHeight + it.verticalGap }

        if (maxWidth == 0 || totalHeight == 0) return

        val hScaleFactor = newWidth.toFloat() / maxWidth
        val vScaleFactor = newHeight.toFloat() / totalHeight
        var y = 0
        for (row in rows) {
            row.defaultHeight = (row.defaultHeight * vScaleFactor).toInt()
            row.verticalGap = (row.verticalGap * vScaleFactor).toInt()
            var x = 0
            for (key in row.keys) {
                key.width = (key.defaultWidth * hScaleFactor).toInt()
                key.horizontalGap = (key.defaultHorizontalGap * hScaleFactor).toInt()
                key.x = x + key.horizontalGap
                x += key.horizontalGap + key.width
                key.height = (key.defaultHeight * vScaleFactor).toInt()
                key.y = y
            }
            y += row.defaultHeight + row.verticalGap
        }

        width = newWidth
        height = newHeight
        computeNearestNeighbors()
    }

    fun setShifted(shiftState: Boolean): Boolean {
        val oldState = isShifted
        isShifted = shiftState
        return oldState != shiftState
    }

    private fun computeNearestNeighbors() {
        if (width == 0 || height == 0) return
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
                    if (key.squaredDistanceFrom(x, y) < mProximityThreshold ||
                        key.squaredDistanceFrom(x + mCellWidth - 1, y) < mProximityThreshold ||
                        key.squaredDistanceFrom(
                            x + mCellWidth - 1,
                            y + mCellHeight - 1
                        ) < mProximityThreshold ||
                        key.squaredDistanceFrom(x, y + mCellHeight - 1) < mProximityThreshold
                    ) indices[count++] = i
                }
                mGridNeighbors[y / mCellHeight * GRID_WIDTH + x / mCellWidth] =
                    indices.copyOf(count)
            }
        }
    }

    fun getNearestKeys(x: Int, y: Int): IntArray {
        if (mGridNeighbors[0] == null) computeNearestNeighbors()
        if (x in 0 until width && y in 0 until height * (100 - bottom) / 100) {
            val index = (y / mCellHeight) * GRID_WIDTH + x / mCellWidth
            if (index in 0 until GRID_SIZE) {
                mGridNeighbors[index]?.let { return it }
            }
        }
        return IntArray(0)
    }

    private fun loadKeyboard(context: Context, parser: XmlResourceParser) {
        var x = 0
        var y = 0
        var currentRow: Row? = null
        val res = context.resources

        runCatching {
            var event: Int
            while (parser.next().also { event = it } != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG) {
                    when (parser.name) {
                        TAG_ROW -> {
                            x = 0
                            currentRow = Row(res, this, parser).also { rows.add(it) }
                        }

                        TAG_KEY -> currentRow?.let { crow ->
                            Key(res, crow, x, y, parser).also { key ->
                                keys.add(key)
                                crow.keys.add(key)
                                when (key.codes.main.getOrNull(0)) {
                                    KEYCODE_SHIFT -> {
                                        mShiftKeys.indexOf(null).takeIf { it != -1 }
                                            ?.let { mShiftKeys[it] = key }
                                        modifierKeys.add(key)
                                    }

                                    KEYCODE_ALT -> modifierKeys.add(key)
                                }
                                x += key.horizontalGap + key.width
                                if (x > width) width = x
                            }
                        }

                        TAG_KEYBOARD -> parseKeyboardAttributes(res, parser)
                    }
                } else if (event == XmlResourceParser.END_TAG && parser.name == TAG_ROW) {
                    currentRow?.let {
                        y += it.verticalGap + it.defaultHeight
                    }
                }
            }
        }.onFailure { SKKLog.e("Parse error", it) }

        height = y - defaultVerticalGap
    }

    private fun parseKeyboardAttributes(res: Resources, parser: XmlResourceParser) {
        res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard).apply {
            defaultKeyWidth = getDim(R.styleable.Keyboard_width, mDisplayWidth, mDisplayWidth / 10)
            defaultKeyHeight = getDim(R.styleable.Keyboard_height, mDisplayHeight, 50)
            defaultHorizontalGap = getDim(R.styleable.Keyboard_hGap, mDisplayWidth, 0)
            defaultVerticalGap = getDim(R.styleable.Keyboard_vGap, mDisplayHeight, 0)
            defaultPopupLayout = getResourceId(R.styleable.Keyboard_popupLayout, 0)
            defaultRepeatable = getBoolean(R.styleable.Keyboard_repeatable, false)
            mProximityThreshold = (defaultKeyWidth * SEARCH_DISTANCE).toInt().let { it * it }
            recycle()
        }
    }

    fun reloadShiftKeys() {
        mShiftKeys.indices.forEach { mShiftKeys[it] = null }
        modifierKeys.removeAll { it.codes.main.getOrNull(0) == KEYCODE_SHIFT }

        keys.filter { it.codes.main.getOrNull(0) == KEYCODE_SHIFT }.forEach { key ->
            mShiftKeys.indexOf(null).takeIf { it != -1 }?.let { mShiftKeys[it] = key }
            modifierKeys.add(key)
        }
    }

    fun resetKeys() {
        keys.forEach {
            it.on = false
            it.pressed = false
        }
    }

    companion object {
        // Keyboard XML Tags
        private const val TAG_KEYBOARD = "Keyboard"
        private const val TAG_ROW = "Row"
        private const val TAG_KEY = "Key"
        const val EDGE_LEFT = 0x01
        const val EDGE_RIGHT = 0x02
        const val EDGE_TOP = 0x04
        const val EDGE_BOTTOM = 0x08
        const val KEYCODE_SHIFT = -1

        // const val KEYCODE_MODE_CHANGE = -2
        // const val KEYCODE_CANCEL = -3
        // const val KEYCODE_DONE = -4
        const val KEYCODE_DELETE = -5
        const val KEYCODE_ALT = -6
        const val KEYCODE_CAPSLOCK = -7

        // Variables for pre-computing nearest keys.
        private const val GRID_WIDTH = 10
        private const val GRID_HEIGHT = 5
        private const val GRID_SIZE = GRID_WIDTH * GRID_HEIGHT
        private const val SEARCH_DISTANCE = 1.8f

        private fun TypedArray.getDim(idx: Int, base: Int, defValue: Int): Int =
            when (peekValue(idx)?.type) {
                TypedValue.TYPE_DIMENSION ->
                    getDimensionPixelOffset(idx, defValue)

                TypedValue.TYPE_FRACTION ->
                    getFraction(idx, base, base, defValue.toFloat()).roundToInt()

                else -> defValue
            }
    }
}
