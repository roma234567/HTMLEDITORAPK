package com.glassbrowser.app

import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Нижнее меню: Cookies, Инспектор, JS, SQL, Прокси, переключение темы.
 */
class SheetMenu(
    activity: MainActivity,
    private val onCookies: () -> Unit,
    private val onInspector: () -> Unit,
    private val onJs: () -> Unit,
    private val onSql: () -> Unit,
    private val onProxy: () -> Unit,
    private val onThemeToggle: () -> Unit
) : BaseSheet(activity, R.layout.sheet_menu) {

    override fun setup(view: View) {
        view.findViewById<View>(R.id.rowCookies).setOnClickListener {
            close(); onCookies()
        }
        view.findViewById<View>(R.id.rowInspector).setOnClickListener {
            close(); onInspector()
        }
        view.findViewById<View>(R.id.rowJs).setOnClickListener {
            close(); onJs()
        }
        view.findViewById<View>(R.id.rowSql).setOnClickListener {
            close(); onSql()
        }
        view.findViewById<View>(R.id.rowProxy).setOnClickListener {
            close(); onProxy()
        }
        view.findViewById<View>(R.id.rowTheme).setOnClickListener {
            close(); onThemeToggle()
        }

        // иконка и подпись в зависимости от текущей темы
        val icon = view.findViewById<ImageView>(R.id.menuThemeIcon)
        val label = view.findViewById<TextView>(R.id.menuThemeLabel)
        if (PrefsX.night) {
            icon.setImageResource(R.drawable.ic_sun)
            label.setText(R.string.menu_theme_light)
        } else {
            icon.setImageResource(R.drawable.ic_moon)
            label.setText(R.string.menu_theme_dark)
        }
    }
}
