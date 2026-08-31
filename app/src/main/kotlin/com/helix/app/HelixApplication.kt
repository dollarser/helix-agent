package com.helix.app

import android.app.Application

class HelixApplication : Application() {
    val appContainer: AppContainer by lazy(::DefaultAppContainer)
}
