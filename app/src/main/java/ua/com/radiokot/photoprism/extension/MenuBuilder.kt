package ua.com.radiokot.photoprism.extension

import android.annotation.SuppressLint
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.Menu
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import ua.com.radiokot.photoprism.R

@SuppressLint("RestrictedApi")
fun MenuBuilder.showOverflowItemIcons(isBottomBar: Boolean) {
    // Enable icons for overflow menu items.
    setOptionalIconsVisible(true)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        // Apply horizontal margin for overflow menu item icons for more pleasant look.
        val iconMarginHorizontal =
            context.resources.getDimensionPixelSize(R.dimen.menu_icon_margin_horizontal)
        // All the items, as hidden ones may be shown later.
        (0 until size()).map { getItem(it) as MenuItemImpl }.forEach { menuItem ->
            if ((isBottomBar && !menuItem.requiresActionButton()
                || !isBottomBar && !menuItem.requestsActionButton())
                && menuItem.icon !is InsetDrawable
            ) {
                menuItem.icon = InsetDrawable(
                    menuItem.icon,
                    iconMarginHorizontal, 0, iconMarginHorizontal, 0
                )
            }
        }
    }
}

@SuppressLint("RestrictedApi")
fun Menu.showOverflowItemIcons(isBottomBar: Boolean) =
    (this as? MenuBuilder)?.showOverflowItemIcons(isBottomBar)
