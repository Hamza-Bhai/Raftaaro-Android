package io.github.hamzabhai.raftaaro.ui.signin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.hamzabhai.raftaaro.core.Site

/** In-app sign-in to a site (filled in by Task 16). */
class SignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        private const val EXTRA_SITE = "site"
        fun intent(ctx: Context, site: Site): Intent = Intent(ctx, SignInActivity::class.java).putExtra(EXTRA_SITE, site.name)
    }
}
