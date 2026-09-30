package app.sine

import android.app.Application
import android.content.Context
import app.sine.data.AppGraph

class SineApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as SineApp).graph
