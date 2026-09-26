package com.clashlite

import android.app.Application

class ClashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ClashApp
            private set
    }
}
