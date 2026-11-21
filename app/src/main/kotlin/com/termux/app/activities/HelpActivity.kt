package com.termux.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.termux.app.ui.compose.docs.CommandDocRepository
import com.termux.app.ui.compose.docs.CommandDocsScreen
import com.termux.app.ui.compose.theme.TermuxTheme

/**
 * Help screen.
 *
 * Replaces the previous embedded wiki browser with an offline command
 * documentation browser. The Termux wiki is still reachable from the button in
 * the app bar, which opens [WikiActivity].
 */
class HelpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = CommandDocRepository(this)
        val host = this

        setContent {
            TermuxTheme {
                CommandDocsScreen(
                    repository = repository,
                    onOpenWiki = {
                        host.startActivity(Intent(host, WikiActivity::class.java))
                    },
                    onBack = { host.finish() }
                )
            }
        }
    }
}
