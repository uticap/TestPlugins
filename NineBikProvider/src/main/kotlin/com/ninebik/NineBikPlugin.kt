package com.ninebik

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NineBikPlugin : Plugin() {
    override fun load(context: Context) {
        appContext = context
        registerMainAPI(NineBikProvider())

        openSettings = { showActivationDialog(context) }

        if (getPref(context, "user", "").isBlank()) {
            // Try at 3s, retry at 6s if dialog didn't show
            Handler(Looper.getMainLooper()).postDelayed({
                try { showActivationDialog(context) } catch (_: Exception) {}
            }, 3000)
        }
    }

    private fun showActivationDialog(ctx: Context) {
        val appName = getPref(ctx, "brand_name", "9bik IPTV")
        val tagline = getPref(ctx, "brand_tagline", "Premium Live TV & Sports")
        val welcomeText = getPref(ctx, "brand_welcome", "Enter your activation code to continue")
        val btnLabel = getPref(ctx, "brand_button", "Activate")
        val footerText = getPref(ctx, "brand_footer", "Contact support if you don't have a code")
        val primary = getPref(ctx, "brand_primary", "#5c6bc0")
        val secondary = getPref(ctx, "brand_secondary", "#0a0e27")
        val accent = getPref(ctx, "brand_accent", "#7986cb")

        val primaryColor = try { Color.parseColor(primary) } catch (_: Exception) { Color.parseColor("#5c6bc0") }
        val secondaryColor = try { Color.parseColor(secondary) } catch (_: Exception) { Color.parseColor("#0a0e27") }
        val accentColor = try { Color.parseColor(accent) } catch (_: Exception) { Color.parseColor("#7986cb") }

        val pad = fun(dp: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), ctx.resources.displayMetrics
        ).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(24), pad(24), pad(24))
            setBackgroundColor(secondaryColor)
        }

        root.addView(TextView(ctx).apply {
            text = "🎬"
            textSize = 40f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(8))
        })

        root.addView(TextView(ctx).apply {
            text = appName
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(ctx).apply {
            text = tagline
            textSize = 12f
            setTextColor(Color.parseColor("#9fa8da"))
            gravity = Gravity.CENTER
            setPadding(0, pad(4), 0, pad(20))
        })

        root.addView(TextView(ctx).apply {
            text = welcomeText
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(16))
        })

        val codeField = EditText(ctx).apply {
            hint = "9BIK-XXXX-XXXX"
            setText(getPref(ctx, "activation_code", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#11152d"))
            setPadding(pad(12), pad(14), pad(12), pad(14))
            isAllCaps = true
            typeface = Typeface.MONOSPACE
        }
        root.addView(codeField)

        val btn = Button(ctx).apply {
            text = btnLabel
            setTextColor(Color.WHITE)
            setBackgroundColor(primaryColor)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(pad(16), pad(12), pad(16), pad(12))
        }
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        btnLp.topMargin = pad(16)
        root.addView(btn, btnLp)

        root.addView(TextView(ctx).apply {
            text = footerText
            textSize = 11f
            setTextColor(Color.parseColor("#7986cb"))
            gravity = Gravity.CENTER
            setPadding(0, pad(20), 0, 0)
        })

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .setCancelable(true)
            .create()

        btn.setOnClickListener {
            val code = codeField.text.toString().trim().uppercase()
            if (code.isBlank()) {
                Toast.makeText(ctx, "Enter your activation code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            btn.text = "Activating..."

            Thread {
                val ok = NineBikProvider.activateCode(ctx, code)
                Handler(Looper.getMainLooper()).post {
                    if (ok) {
                        Toast.makeText(ctx, "Activated! Open the provider to browse.", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    } else {
                        btn.isEnabled = true
                        btn.text = btnLabel
                        Toast.makeText(ctx, "Activation failed - check your code", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        dialog.show()
    }

    companion object {
        var appContext: Context? = null

        fun getPref(ctx: Context, key: String, def: String): String {
            return ctx.getSharedPreferences("ninebik", Context.MODE_PRIVATE)
                .getString(key, def) ?: def
        }

        fun setPref(ctx: Context, key: String, value: String) {
            ctx.getSharedPreferences("ninebik", Context.MODE_PRIVATE)
                .edit()
                .putString(key, value)
                .apply()
        }
    }
}
