package com.ninebik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import androidx.fragment.app.Fragment

@CloudstreamPlugin
class NineBikPlugin : Plugin() {
    override fun load(context: Context) {
        // Register provider
        registerMainAPI(NineBikProvider())

        // Expose settings screen - shows gear icon in CloudStream
        openSettings = {
            // Return a settings fragment instance
            NineBikSettings()
        }
    }
}
