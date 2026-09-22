package com.zui.zuicontrol

import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.FrameLayout

internal object UiControls {
    fun styleDialog(dialog: AlertDialog) = with(dialog) {
        // Install panels and establish the final geometry before WindowManager sees the dialog.
        create()
        val r = context.resources
        val margin = r.getDimensionPixelSize(R.dimen.ui_dialog_spacing)
        window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawable(shape(context, R.color.ui_surface, r.getDimension(R.dimen.ui_card_radius)))
            setLayout(minOf(r.getDimensionPixelSize(R.dimen.ui_dialog_max_width),
                r.displayMetrics.widthPixels - 2 * margin), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // The platform title and action panels use 24dp; custom content shares those edges.
        findViewById<FrameLayout>(android.R.id.custom)?.let { slot ->
            slot.setPadding(margin, r.getDimensionPixelSize(R.dimen.ui_dialog_vertical_spacing),
                margin, r.getDimensionPixelSize(R.dimen.ui_dialog_vertical_spacing))
            slot.getChildAt(0)?.setPadding(0, 0, 0, 0)
        }
    }
    fun modeChip(context: Context, mode: UperfMode, selected: Boolean = false) =
        chip(context, mode.title, selected).apply {
            background = shape(context, if (selected) mode.color else R.color.ui_field,
                resources.getDimension(R.dimen.ui_chip_height) / 2)
            setTextColor(if (selected) Color.WHITE else context.getColor(R.color.ui_text))
        }
    fun shape(context: Context, color: Int, radius: Float) = GradientDrawable().apply {
        setColor(context.getColor(color)); cornerRadius = radius
    }
    fun styleChip(view: TextView, selected: Boolean) = with(view) {
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = resources.getDimensionPixelSize(R.dimen.ui_chip_min_width)
        minHeight = resources.getDimensionPixelSize(R.dimen.ui_chip_height)
        val padding = resources.getDimensionPixelSize(R.dimen.ui_chip_padding)
        setPadding(padding, 0, padding, 0)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ui_chip_text))
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (selected) android.graphics.Color.WHITE else context.getColor(R.color.ui_text))
        background = shape(context, if (selected) R.color.ui_accent else R.color.ui_field,
            resources.getDimension(R.dimen.ui_chip_height) / 2)
        isSelected = selected
    }
    fun chip(context: Context, text: String, selected: Boolean = false) = TextView(context).apply {
        this.text = text; styleChip(this, selected)
    }
    fun gridColumns(contentWidthDp: Int) = (contentWidthDp / 260).coerceIn(2, 4)
}

/** Both Refresh and Uperf use this single below-anchor, same-width native popup. */
internal class AnchoredDropdown(context: Context, private val items: List<String>) : android.widget.LinearLayout(context) {
    var selectedItemPosition = 0; private set
    var onSelection: (Int) -> Unit = {}
    private var popup: PopupWindow? = null
    private val selectedText = TextView(context).apply {
        textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.getColor(R.color.ui_text)); setSingleLine(true)
    }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val pad = resources.getDimensionPixelSize(R.dimen.ui_chip_padding)
        setPadding(pad, 0, pad, 0)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.ui_control_height)
        background = UiControls.shape(context, R.color.ui_field, resources.getDimension(R.dimen.ui_card_radius))
        isFocusable = true
        addView(selectedText, LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "▾"; textSize = 16f; gravity = Gravity.END
            setTextColor(context.getColor(R.color.ui_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(-2, -2))
        commitSelection(0)
        setOnClickListener { showChoices() }
    }
    fun commitSelection(position: Int) {
        selectedItemPosition = position.coerceIn(items.indices)
        selectedText.text = items[selectedItemPosition]
        contentDescription = items[selectedItemPosition]
        onSelection(selectedItemPosition)
    }
    private fun showChoices() {
        popup?.dismiss()
        val frame = Rect(); getWindowVisibleDisplayFrame(frame)
        val xy = IntArray(2); getLocationOnScreen(xy)
        val gap = resources.getDimensionPixelSize(R.dimen.ui_dropdown_gap)
        val rowHeight = resources.getDimensionPixelSize(R.dimen.ui_dropdown_row_height)
        val pad = resources.getDimensionPixelSize(R.dimen.ui_dropdown_padding)
        val available = frame.bottom - xy[1] - height - gap
        val popupHeight = minOf(items.size * rowHeight + 2 * pad,
            resources.getDimensionPixelSize(R.dimen.ui_dropdown_max_height), available)
        if (popupHeight <= 0) return
        val list = ListView(context).apply {
            divider = null; setPadding(pad, pad, pad, pad); clipToPadding = true
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    ((convertView as? TextView) ?: TextView(context)).apply {
                        text = items[position]; textSize = 14f; includeFontPadding = false
                        setTextColor(context.getColor(R.color.ui_text))
                        setPadding(resources.getDimensionPixelSize(R.dimen.ui_chip_padding), 0,
                            resources.getDimensionPixelSize(R.dimen.ui_chip_padding), 0)
                        setBackgroundColor(if (position == selectedItemPosition) context.getColor(R.color.ui_field) else Color.TRANSPARENT)
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        layoutParams = android.widget.AbsListView.LayoutParams(-1, rowHeight)
                    }
            }
            setOnItemClickListener { _, _, position, _ ->
                this@AnchoredDropdown.commitSelection(position)
                popup?.dismiss()
            }
        }
        popup = PopupWindow(list, width, popupHeight, true).apply {
            setBackgroundDrawable(UiControls.shape(context, R.color.ui_surface, resources.getDimension(R.dimen.ui_card_radius)))
            elevation = resources.getDimension(R.dimen.ui_dropdown_gap)
            isOutsideTouchable = true; overlapAnchor = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            showAsDropDown(this@AnchoredDropdown, 0, gap, Gravity.START)
        }
        if (popupHeight < items.size * rowHeight + 2 * pad) list.setSelection(selectedItemPosition)
    }
    override fun onDetachedFromWindow() { popup?.dismiss(); popup = null; super.onDetachedFromWindow() }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Spinner"
}
