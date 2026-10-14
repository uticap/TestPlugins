package com.ninebik

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.plugins.Plugin

class NineBikSettings : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(ctx).apply {
            text = "9bik IPTV Login"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        val userLabel = TextView(ctx).apply { text = "Username" }
        layout.addView(userLabel)
        val userInput = EditText(ctx).apply {
            hint = "username"
            setText(ctx.getPluginString("xtream_user", ""))
        }
        layout.addView(userInput)

        val passLabel = TextView(ctx).apply {
            text = "Password"
            setPadding(0, 24, 0, 0)
        }
        layout.addView(passLabel)
        val passInput = EditText(ctx).apply {
            hint = "password"
            setText(ctx.getPluginString("xtream_pass", ""))
        }
        layout.addView(passInput)

        val saveBtn = Button(ctx).apply {
            text = "Save"
            setPadding(0, 32, 0, 0)
        }
        layout.addView(saveBtn)

        saveBtn.setOnClickListener {
            ctx.setPluginString("xtream_user", userInput.text.toString().trim())
            ctx.setPluginString("xtream_pass", passInput.text.toString().trim())
            Toast.makeText(ctx, "Saved. Reload the provider.", Toast.LENGTH_SHORT).show()
        }

        return layout
    }
}

// Storage helpers - use CloudStream plugin context
fun android.content.Context.getPluginString(key: String, default: String): String {
    return com.lagradost.cloudstream3.plugins.Plugin.context
        ?.getSharedPreferences("ninebik", 0)
        ?.getString(key, default) ?: default
}

fun android.content.Context.setPluginString(key: String, value: String) {
    com.lagradost.cloudstream3.plugins.Plugin.context
        ?.getSharedPreferences("ninebik", 0)
        ?.edit()
        ?.putString(key, value)
        ?.apply()
}
