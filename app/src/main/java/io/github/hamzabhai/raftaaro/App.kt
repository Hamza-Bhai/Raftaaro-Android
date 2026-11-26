package io.github.hamzabhai.raftaaro

import android.app.Application
import android.content.Context
import io.github.hamzabhai.raftaaro.engine.PlayerWarmUpWorker

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.warmUp()
        PlayerWarmUpWorker.schedule(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
