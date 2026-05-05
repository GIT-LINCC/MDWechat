package com.blanke.mdwechat.util

data class MaterialTabIconTintColors(
    val selectedIconColor: Int,
    val unselectedIconColor: Int
)

object MaterialTabIconTintPolicy {
    fun resolve(
        selectedTextColor: Int,
        unselectedTextColor: Int,
        tertiaryColor: Int,
        tintSelectedIcon: Boolean,
        tintUnselectedIcon: Boolean
    ): MaterialTabIconTintColors {
        return MaterialTabIconTintColors(
            selectedIconColor = if (tintSelectedIcon) selectedTextColor else unselectedTextColor,
            unselectedIconColor = if (tintUnselectedIcon) tertiaryColor else unselectedTextColor
        )
    }
}
