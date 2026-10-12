package io.github.loganrosen.wattdog

import android.app.Application

class WattdogApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
