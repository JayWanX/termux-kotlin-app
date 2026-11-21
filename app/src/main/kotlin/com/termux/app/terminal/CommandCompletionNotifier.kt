package com.termux.app.terminal

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.shared.logger.Logger
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.terminal.TerminalSnapshot
import com.termux.shared.terminal.TerminalState

/**
 * Notifies the user when a long-running command finishes while the app is in the background.
 *
 * This is the feature that makes "terminal status control" useful without any assistant: start a long
 * `pkg install`, switch away, and get told when it is done.
 *
 * Notifications are deliberately conservative, because a false "command finished" is worse than no
 * notification at all:
 *
 * - A dead session always notifies.
 * - A quiet session notifies only if it had been busy for at least [MIN_BUSY_DURATION_MS], so that
 *   typing a single command and pressing enter does not produce a stream of notifications.
 * - Nothing is shown while the activity is visible, since the user can already see the terminal.
 * - The same session is not notified twice within [DEDUPE_WINDOW_MS].
 */
class CommandCompletionNotifier(private val context: Context) {

    companion object {
        private const val LOG_TAG = "CommandCompletionNotifier"

        /** Only interrupt the user for commands that actually took a while. */
        const val MIN_BUSY_DURATION_MS: Long = 5_000L

        /** Suppresses duplicate notifications for the same session. */
        const val DEDUPE_WINDOW_MS: Long = 3_000L

        private const val CHANNEL_ID = "termux_command_completion"
        private const val CHANNEL_NAME = "Command completion"
        private const val NOTIFICATION_ID_BASE = 2_000
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** Tracks when each session last became busy, keyed by session id. */
    private val busySince = mutableMapOf<String, Long>()

    /** Tracks the last notification time per session. */
    private val lastNotified = mutableMapOf<String, Long>()

    /** Sessions that already produced a completion notification, to avoid repeating it. */
    private val notifiedAtState = mutableMapOf<String, TerminalState>()

    init {
        setupChannel()
    }

    private fun setupChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        NotificationUtils.setupNotificationChannel(
            context,
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /**
     * Feeds one state observation for [sessionId].
     *
     * Call this whenever a session's state changes. It internally decides whether a notification is
     * warranted.
     *
     * @param nowMs elapsed-realtime timestamp.
     * @param appVisible whether the terminal activity is currently visible to the user.
     */
    fun onStateChanged(
        sessionId: String,
        sessionTitle: String,
        state: TerminalState,
        snapshot: TerminalSnapshot?,
        nowMs: Long,
        appVisible: Boolean,
    ) {
        when (state) {
            is TerminalState.Busy -> {
                busySince.putIfAbsent(sessionId, nowMs)
                notifiedAtState.remove(sessionId)
            }

            is TerminalState.Idle -> {
                val startedAt = busySince.remove(sessionId)
                if (appVisible) return
                if (startedAt == null) return
                if (nowMs - startedAt < MIN_BUSY_DURATION_MS) return
                if (!shouldNotify(sessionId, state, nowMs)) return
                notify(sessionId, sessionTitle, exitStatus = null, snapshot = snapshot)
            }

            is TerminalState.Dead -> {
                busySince.remove(sessionId)
                if (appVisible) return
                if (!shouldNotify(sessionId, state, nowMs)) return
                notify(sessionId, sessionTitle, exitStatus = state.exitStatus, snapshot = snapshot)
            }

            TerminalState.NoSession -> {
                busySince.remove(sessionId)
                lastNotified.remove(sessionId)
                notifiedAtState.remove(sessionId)
            }
        }
    }

    private fun shouldNotify(sessionId: String, state: TerminalState, nowMs: Long): Boolean {
        val previous = notifiedAtState[sessionId]
        if (previous != null && previous::class == state::class) {
            // Same terminal outcome as last time; the state did not actually change.
            return false
        }
        val last = lastNotified[sessionId] ?: 0L
        if (nowMs - last < DEDUPE_WINDOW_MS) return false
        lastNotified[sessionId] = nowMs
        notifiedAtState[sessionId] = state
        return true
    }

    private fun notify(
        sessionId: String,
        sessionTitle: String,
        exitStatus: Int?,
        snapshot: TerminalSnapshot?,
    ) {
        val manager = notificationManager ?: return
        val builder = NotificationUtils.geNotificationBuilder(
            context,
            CHANNEL_ID,
            NotificationManager.IMPORTANCE_DEFAULT,
            context.getString(R.string.command_completion_title),
            buildBody(sessionTitle, exitStatus, snapshot),
            null,
            buildContentIntent(),
            null,
            NotificationUtils.NOTIFICATION_MODE_VIBRATE,
        ) ?: return

        try {
            manager.notify(notificationIdFor(sessionId), builder.build())
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "Failed to post completion notification: ${e.message}")
        }
    }

    private fun buildBody(
        sessionTitle: String,
        exitStatus: Int?,
        snapshot: TerminalSnapshot?,
    ): String {
        val title = sessionTitle.ifBlank { context.getString(R.string.command_completion_fallback_session) }
        val status = when {
            exitStatus == null -> context.getString(R.string.command_completion_finished)
            exitStatus == 0 -> context.getString(R.string.command_completion_success)
            else -> context.getString(R.string.command_completion_failure, exitStatus)
        }
        val tail = snapshot?.lastSessionLine.orEmpty().take(120)
        return if (tail.isNotBlank()) "$title · $status\n$tail" else "$title · $status"
    }

    private fun buildContentIntent(): PendingIntent? {
        val intent = Intent(context, TermuxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable per-session id so that a second notification replaces the first. */
    private fun notificationIdFor(sessionId: String): Int =
        NOTIFICATION_ID_BASE + (sessionId.hashCode() and 0xFF)

    /** Clears bookkeeping, for example when the user dismisses everything. */
    fun reset() {
        busySince.clear()
        lastNotified.clear()
        notifiedAtState.clear()
    }
}
