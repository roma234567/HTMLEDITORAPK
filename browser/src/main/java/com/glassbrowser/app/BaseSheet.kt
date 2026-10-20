package com.glassbrowser.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.annotation.LayoutRes

/**
 * База нижних листов: Dialog снизу, стеклянный фон, blur-behind (API 31+).
 */
abstract class BaseSheet(
    private val activity: MainActivity,
    @LayoutRes private val layoutId: Int
) {

    var dialog: Dialog? = null
        private set
    private var sheetView: View? = null

    val view: View?
        get() = sheetView

    fun isShowing(): Boolean = dialog?.isShowing == true

    open fun show() {
        if (isShowing()) return
        val d = Dialog(activity, R.style.GlassSheet)
        d.setContentView(layoutId)
        d.setCanceledOnTouchOutside(true)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    w.attributes.dimAmount = 0.5f
                } catch (e: Exception) {
                    // ignore
                }
            } else {
                w.attributes.dimAmount = 0.45f
            }
        }
        sheetView = d.findViewById(layoutId)
        d.setOnDismissListener {
            onDismiss()
            sheetView = null
        }
        dialog = d
        val v = sheetView
        if (v != null) setup(v)
        d.show()
        onShown()
    }

    /** Заполнение view; вызывается до show(). */
    protected abstract fun setup(view: View)

    open fun onShown() {}
    open fun onDismiss() {}

    fun close() {
        try { dialog?.dismiss() } catch (e: Exception) { /* ignore */ }
    }

    fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    fun post(f: () -> Unit) {
        activity.runOnUiThread { f() }
    }

    /** runJs с callback в UI-потоке. */
    fun runJs(js: String, cb: ((String) -> Unit)? = null) {
        activity.runJs(js, cb)
    }
}
