package com.ninebik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NineBikPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NineBikProvider())
    }
}
