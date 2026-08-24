package com.zui.zuicontrol

object PackageNames {
    private val pattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun isValid(value: String): Boolean = pattern.matches(value)
}
