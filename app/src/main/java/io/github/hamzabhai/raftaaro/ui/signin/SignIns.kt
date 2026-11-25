package io.github.hamzabhai.raftaaro.ui.signin

import io.github.hamzabhai.raftaaro.AppGraph
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.core.cookieCount

/** Saved site sign-ins (cookie files on this phone). */
object SignIns {
    fun isSignedIn(graph: AppGraph, site: Site): Boolean {
        val f = graph.cookieFile(site)
        return f.isFile && cookieCount(f.readText()) > 0
    }

    fun signOut(graph: AppGraph, site: Site) {
        graph.cookieFile(site).delete()
    }
}
