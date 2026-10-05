package com.zui.zuicontrol

/** Shared platform-font roles. Numeric readouts retain their Owner metric scale. */
internal enum class OwnerTypography(val size: Float, val weight: Int) {
    TITLE(20f,800), PAGE_HEADING(19f,800), SECTION_HEADING(14f,800),
    BODY(13f,500), BUTTON(12f,800), CHIP(10f,700), META(11.5f,600), MONO(12.5f,600);
    companion object {
        fun size(requested: Float): Float = when {
            requested > 20f || requested < 10f -> requested
            requested >= 19f -> if(requested>=20f)TITLE.size else PAGE_HEADING.size
            requested >= 14f -> requested // gauge/modal headings have explicit Owner sizes
            requested == MONO.size -> MONO.size
            requested >= 13f -> BODY.size
            requested >= 12f -> BUTTON.size
            requested > 10f -> META.size
            else -> CHIP.size
        }
    }
}
