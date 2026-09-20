package com.zui.zuicontrol

internal enum class UperfMode(val id: String, val title: String, val color: Int) {
    POWERSAVE("powersave", "节能", R.color.mode_powersave),
    BALANCE("balance", "均衡", R.color.mode_balance),
    PERFORMANCE("performance", "性能", R.color.mode_performance),
    FAST("fast", "快速", R.color.mode_fast);

    companion object {
        fun fromId(value: String): UperfMode? = entries.firstOrNull { it.id == value }
        fun resolve(global: String, rules: String, pkg: String): UperfMode =
            rules.lineSequence().map { line -> line.trim().split('|').map { it.trim() } }
                .firstOrNull { it.size == 2 && it[0] == pkg && fromId(it[1]) != null }
                ?.let { fromId(it[1]) } ?: fromId(global) ?: BALANCE
    }
}
