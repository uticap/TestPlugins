package com.ninebik

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NineBikPlugin : Plugin() {
    override fun load(context: Context) {
        appContext = context
        registerMainAPI(NineBikProvider())

        openSettings = {
            val ctx = context
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
            layout.addView(TextView(ctx).apply { text = "Username" })
            val userField = EditText(ctx).apply {
                hint = "username"
                setText(getCred(ctx, "user", ""))
            }
            layout.addView(userField)
            layout.addView(TextView(ctx).apply {
                text = "Password"
                setPadding(0, 24, 0, 0)
            })
            val passField = EditText(ctx).apply {
                hint = "password"
                setText(getCred(ctx, "pass", ""))
            }
            layout.addView(passField)

            AlertDialog.Builder(ctx)
                .setTitle("9bik IPTV Login")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    saveCred(ctx, "user", userField.text.toString().trim())
                    saveCred(ctx, "pass", passField.text.toString().trim())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    companion object {
        var appContext: Context? = null
    }

    private fun getCred(ctx: Context, key: String, def: String): String {
        return ctx.getSharedPreferences("ninebik", Context.MODE_PRIVATE)
            .getString(key, def) ?: def
    }

    private fun saveCred(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences("ninebik", Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}
