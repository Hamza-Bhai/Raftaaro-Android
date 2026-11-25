package io.github.hamzabhai.raftaaro.ui.signin

import android.webkit.CookieManager
import io.github.hamzabhai.raftaaro.AppGraph
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.core.cookieCount
import io.github.hamzabhai.raftaaro.core.toNetscapeCookies

/** Saved site sign-ins: cookie files in the app's private storage, never sent anywhere but to the site. */
object SignIns {
    private fun urlsOf(domain: String) = listOf("https://$domain", "https://www.$domain", "https://m.$domain", "https://accounts.$domain")

    /** All cookies the WebView holds for a domain and its usual subdomains, as one "a=1; b=2" header. */
    fun cookiesOf(cm: CookieManager, domain: String): String? {
        val pairs = linkedMapOf<String, String>()
        for (u in urlsOf(domain)) {
            cm.getCookie(u)?.split(';')?.forEach { p ->
                val eq = p.indexOf('=')
                if (eq > 0) pairs.putIfAbsent(p.substring(0, eq).trim(), p.substring(eq + 1).trim())
            }
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
    }

    fun save(graph: AppGraph, site: Site, headers: Map<String, String?>) {
        val f = graph.cookieFile(site)
        f.parentFile?.mkdirs()
        f.writeText(toNetscapeCookies(headers))
    }

    fun isSignedIn(graph: AppGraph, site: Site): Boolean {
        val f = graph.cookieFile(site)
        return f.isFile && cookieCount(f.readText()) > 0
    }

    /** Deletes the saved file and expires the site's cookies in the WebView (other sites stay signed in). */
    fun signOut(graph: AppGraph, site: Site) {
        graph.cookieFile(site).delete()
        val cm = CookieManager.getInstance()
        for (d in site.cookieDomains) {
            for (u in urlsOf(d)) {
                cm.getCookie(u)?.split(';')?.mapNotNull { it.substringBefore('=').trim().ifEmpty { null } }?.forEach { name ->
                    cm.setCookie(u, "$name=; Max-Age=0; Path=/")
                    cm.setCookie(u, "$name=; Max-Age=0; Path=/; Domain=.$d")
                }
            }
        }
        cm.flush()
    }
}
