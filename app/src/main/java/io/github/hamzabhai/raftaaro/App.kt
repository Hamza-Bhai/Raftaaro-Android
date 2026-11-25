package io.github.hamzabhai.raftaaro

import android.app.Application
import android.content.Context

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
