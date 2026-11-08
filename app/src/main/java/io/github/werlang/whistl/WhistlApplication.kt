package io.github.werlang.whistl

import android.app.Application
import io.github.werlang.whistl.demo.DemoSwitch

/**
 * The application object. It owns the one [AppContainer] of the process, built on first start with the demo switch's state
 * (always off in a release build). Activities reach the container through it.
 */
class WhistlApplication : Application() {
    /** The object graph for this process. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, demoMode = DemoSwitch.isOn(this))
    }
}
