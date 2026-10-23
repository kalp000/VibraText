package com.example.vibratext.app

import android.app.Application

class VibraTextApp : Application() {
    /** Created once per process; the listener service, the UI and the tests screen all share it. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
