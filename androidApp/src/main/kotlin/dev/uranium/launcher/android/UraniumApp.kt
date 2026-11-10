package dev.uranium.launcher.android

import android.app.Application
import dev.uranium.launcher.core.AndroidPlatform

class UraniumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(this)
    }
}
