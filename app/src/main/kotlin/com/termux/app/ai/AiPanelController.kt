package com.termux.app.ai

import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.termux.shared.logger.Logger
import com.termux.shared.terminal.TerminalControl
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Mounts the assistant panel into an existing container.
 *
 * This is the only app-side integration point besides [com.termux.app.TermuxActivity]'s call to
 * [AiAssistant.attach]: everything about how the panel looks and behaves lives in the `ai` package.
 */
class AiPanelController(
    private val lifecycleOwner: LifecycleOwner,
    private val container: FrameLayout,
    private val terminal: TerminalControl,
) {

    companion object {
        private const val LOG_TAG = "AiPanelController"
    }

    private var session: AgentSession? = null
    private var composeView: ComposeView? = null
    private var runningJob: Job? = null

    @Volatile
    private var panelVisible = false

    /** The assistant session backing the panel, creating it on first use. */
    fun session(): AgentSession? {
        session?.let { return it }
        val created = AiAssistant.createSession() ?: return null
        // Filesystem tools are confined to the Termux home directory. Shared storage is deliberately
        // not included: it holds the user's photos and documents, which an assistant has no reason to
        // rewrite without being asked explicitly.
        created.fileRoots = listOf(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH)
        session = created
        return created
    }

    /** True when the panel can be shown. */
    fun isAvailable(): Boolean = AiAssistant.isAvailable()

    /** True when the panel is currently showing. */
    fun isVisible(): Boolean = panelVisible

    /** Toggles the panel's visibility, mounting the Compose view on first show. */
    fun toggle() {
        if (panelVisible) hide() else show()
    }

    /** Shows the panel. */
    fun show() {
        val current = session() ?: run {
            Logger.logWarn(LOG_TAG, "Assistant not configured; ignoring show request")
            return
        }
        val view = composeView ?: createComposeView(current).also {
            composeView = it
            container.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        // The container itself starts hidden; both it and the child must be made visible, otherwise the
        // panel is present in the tree but never drawn.
        container.visibility = android.view.View.VISIBLE
        view.visibility = android.view.View.VISIBLE
        panelVisible = true
    }

    /** Hides the panel. Cancels any in-flight turn. */
    fun hide() {
        composeView?.visibility = android.view.View.GONE
        container.visibility = android.view.View.GONE
        panelVisible = false
        stop()
    }

    /** Cancels the current turn, if any. */
    fun stop() {
        runningJob?.cancel()
        runningJob = null
    }

    /** Releases resources. */
    fun dispose() {
        stop()
        composeView?.let { container.removeView(it) }
        composeView = null
        session = null
    }

    private fun createComposeView(current: AgentSession): ComposeView {
        // Compose resolves the view tree owners by walking up from the ComposeView to its nearest
        // ancestors. Setting them on the ComposeView is not enough when it has not been attached to a
        // window yet, so they are set on the container first; Compose then finds them while detached.
        container.setViewTreeLifecycleOwner(lifecycleOwner)
        (lifecycleOwner as? androidx.savedstate.SavedStateRegistryOwner)?.let {
            container.setViewTreeSavedStateRegistryOwner(it)
        }
        (lifecycleOwner as? androidx.lifecycle.ViewModelStoreOwner)?.let {
            container.setViewTreeViewModelStoreOwner(it)
        }

        return ComposeView(container.context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            (lifecycleOwner as? androidx.savedstate.SavedStateRegistryOwner)?.let {
                setViewTreeSavedStateRegistryOwner(it)
            }
            (lifecycleOwner as? androidx.lifecycle.ViewModelStoreOwner)?.let {
                setViewTreeViewModelStoreOwner(it)
            }
            setContent {
                var terminalState by mutableStateOf(terminal.state.value)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    terminal.state.collect { terminalState = it }
                }

                AiPanel(
                    session = current,
                    terminalState = terminalState,
                    onSend = { text -> send(current, text) },
                    onStop = { stop() },
                    onConfirm = { approved -> current.answerConfirmation(approved) },
                    onReset = { current.reset() },
                    onClose = { hide() },
                )
            }
        }
    }

    private fun send(current: AgentSession, text: String) {
        runningJob?.cancel()
        runningJob = lifecycleOwner.lifecycleScope.launch {
            try {
                current.send(text)
            } catch (e: Exception) {
                Logger.logError(LOG_TAG, "assistant turn failed: ${e.message}")
            }
        }
    }
}
