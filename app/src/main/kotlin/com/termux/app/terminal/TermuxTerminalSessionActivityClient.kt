package com.termux.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.text.TextUtils
import android.view.Menu
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupMenu
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.app.styling.FontManager
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.termux.terminal.io.BellHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/** The [TerminalSessionClient] implementation that may require an [Activity] for its interface methods. */
class TermuxTerminalSessionActivityClient(private val mActivity: TermuxActivity) : TermuxTerminalSessionClientBase() {

    private var mBellSoundPool: SoundPool? = null
    private var mBellSoundId: Int = 0

    /** The currently shown session list dialog, if any. Used to refresh it live. */
    private var mSessionListDialog: AlertDialog? = null
    private var mSessionListAdapter: ArrayAdapter<String>? = null

    /**
     * Should be called when mActivity.onCreate() is called
     */
    fun onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    fun onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        if (mActivity.termuxService != null) {
            setCurrentSession(currentStoredSessionOrLast)
            termuxSessionListNotifyUpdated()
        }

        // The current terminal session may have changed while being away
        mActivity.terminalView.onScreenUpdated()
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    fun onResume() {
        loadBellSoundPool()
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    fun onStop() {
        setCurrentStoredSession()
        releaseBellSoundPool()
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    fun onReloadActivityStyling() {
        checkForFontAndColors()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        if (!mActivity.isVisible) return

        if (mActivity.currentSession === changedSession) mActivity.terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(updatedSession: TerminalSession) {
        if (!mActivity.isVisible) return

        if (updatedSession !== mActivity.currentSession) {
            mActivity.showToast(toToastTitle(updatedSession), true)
        }

        termuxSessionListNotifyUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val service = mActivity.termuxService

        if (service == null || service.wantsToStop()) {
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val index = service.getIndexOfSession(finishedSession)

        var isPluginExecutionCommandWithPendingResult = false
        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.executionCommand.isPluginExecutionCommandWithPendingResult()
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"${finishedSession.mSessionName}\" session will be force finished automatically since result in pending.")
        }

        if (mActivity.isVisible && finishedSession !== mActivity.currentSession) {
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true)
        }

        if (mActivity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            if (service.termuxSessionsSize > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        } else {
            // Remove the finished session right away unless it is the only
            // session left, which is the current one. In that case keep it so
            // the user can still read the final output before pressing Enter.
            val isOnlySession = service.termuxSessionsSize <= 1
            val isCurrentSession = finishedSession === mActivity.currentSession
            if (!(isOnlySession && isCurrentSession) || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (!mActivity.isVisible) return

        ShareUtils.copyTextToClipboard(mActivity, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (!mActivity.isVisible) return

        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null)
            mActivity.terminalView.mEmulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {
        if (!mActivity.isVisible) return

        when (mActivity.properties?.getBellBehaviour()) {
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE ->
                BellHandler.getInstance(mActivity).doBell()
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP -> {
                loadBellSoundPool()
                mBellSoundPool?.play(mBellSoundId, 1f, 1f, 1, 0, 1f)
            }
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE -> {
                // Ignore the bell character.
            }
        }
    }

    override fun onColorsChanged(changedSession: TerminalSession) {
        if (mActivity.currentSession === changedSession)
            updateBackgroundColor()
    }

    override fun onTerminalCursorStateChange(enabled: Boolean) {
        if (enabled && !mActivity.isVisible) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible")
            return
        }

        mActivity.terminalView.setTerminalCursorBlinkerState(enabled, false)
    }

    override fun setTerminalShellPid(terminalSession: TerminalSession, pid: Int) {
        val service = mActivity.termuxService ?: return

        val termuxSession = service.getTermuxSessionForTerminalSession(terminalSession)
        if (termuxSession != null)
            termuxSession.executionCommand.mPid = pid
    }

    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    fun onResetTerminalSession() {
        mActivity.terminalView.setTerminalCursorBlinkerState(true, true)
    }

    override fun getTerminalCursorStyle(): Int? {
        return mActivity.properties?.getTerminalCursorStyle()
    }

    /** Load mBellSoundPool */
    @Synchronized
    private fun loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
            ).build()

            try {
                mBellSoundId = mBellSoundPool!!.load(mActivity, com.termux.shared.R.raw.bell, 1)
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e)
            }
        }
    }

    /** Release mBellSoundPool resources */
    @Synchronized
    private fun releaseBellSoundPool() {
        mBellSoundPool?.release()
        mBellSoundPool = null
    }

    /** Try switching to session. */
    fun setCurrentSession(session: TerminalSession?) {
        if (session == null) return

        if (mActivity.terminalView.attachSession(session)) {
            notifyOfSessionChange()
        }

        // Keep the session list dialog's checked item in sync with the
        // session that is actually attached to the terminal view.
        refreshSessionListDialogIfShowing()
        updateBackgroundColor()
    }

    fun notifyOfSessionChange() {
        if (!mActivity.isVisible) return

        if (mActivity.properties?.areTerminalSessionChangeToastsDisabled() != true) {
            val session = mActivity.currentSession
            mActivity.showToast(toToastTitle(session), false)
        }
    }

    fun switchToSession(forward: Boolean) {
        val service = mActivity.termuxService ?: return

        val currentTerminalSession = mActivity.currentSession
        var index = service.getIndexOfSession(currentTerminalSession)
        val size = service.termuxSessionsSize
        if (forward) {
            if (++index >= size) index = 0
        } else {
            if (--index < 0) index = size - 1
        }

        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null)
            setCurrentSession(termuxSession.terminalSession)
    }

    fun switchToSession(index: Int) {
        val service = mActivity.termuxService ?: return

        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null)
            setCurrentSession(termuxSession.terminalSession)
    }

    @SuppressLint("InflateParams")
    fun renameSession(sessionToRename: TerminalSession?) {
        if (sessionToRename == null) return

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName,
            R.string.action_rename_session_confirm, { text ->
                renameSession(sessionToRename, text)
                termuxSessionListNotifyUpdated()
            }, -1, null, -1, null, null)
    }

    private fun renameSession(sessionToRename: TerminalSession?, text: String?) {
        if (sessionToRename == null) return
        sessionToRename.mSessionName = text
        val service = mActivity.termuxService
        if (service != null) {
            val termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename)
            if (termuxSession != null)
                termuxSession.executionCommand.shellName = text
        }
    }

    fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service = mActivity.termuxService ?: return

        if (service.termuxSessionsSize >= MAX_SESSIONS) {
            AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached)
                .setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show()
        } else {
            val currentSession = mActivity.currentSession

            val workingDirectory = if (currentSession == null) {
                mActivity.properties?.getDefaultWorkingDirectory()
            } else {
                currentSession.getCwd()
            }

            val newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName)
                ?: return

            val newTerminalSession = newTermuxSession.terminalSession
            setCurrentSession(newTerminalSession)
        }
    }

    fun setCurrentStoredSession() {
        val currentSession = mActivity.currentSession
        if (currentSession != null)
            mActivity.preferences?.setCurrentSession(currentSession.mHandle)
        else
            mActivity.preferences?.setCurrentSession(null)
    }

    /** The current session as stored or the last one if that does not exist. */
    val currentStoredSessionOrLast: TerminalSession?
        get() {
            val stored = currentStoredSession

            return if (stored != null) {
                stored
            } else {
                val service = mActivity.termuxService ?: return null
                val termuxSession = service.lastTermuxSession
                termuxSession?.terminalSession
            }
        }

    private val currentStoredSession: TerminalSession?
        get() {
            val sessionHandle = mActivity.preferences?.getCurrentSession() ?: return null

            val service = mActivity.termuxService ?: return null
            return service.getTerminalSessionForHandle(sessionHandle)
        }

    fun removeFinishedSession(finishedSession: TerminalSession) {
        val service = mActivity.termuxService ?: return

        val index = service.removeTermuxSession(finishedSession)

        val size = service.termuxSessionsSize
        if (size == 0) {
            mActivity.finishActivityIfNotFinishing()
        } else {
            val newIndex = if (index >= size) size - 1 else index
            val termuxSession = service.getTermuxSession(newIndex)
            if (termuxSession != null)
                setCurrentSession(termuxSession.terminalSession)
        }
    }

    fun termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated()

        // Keep the session list dialog in sync while it is open.
        refreshSessionListDialogIfShowing()
    }

    /**
     * Rebuild the session list dialog contents if it is currently shown.
     */
    private fun refreshSessionListDialogIfShowing() {
        val dialog = mSessionListDialog
        val adapter = mSessionListAdapter
        val service = mActivity.termuxService
        if (dialog != null && dialog.isShowing && adapter != null && service != null) {
            refreshSessionListDialog(dialog, service, adapter)
        }
    }

    fun toToastTitle(session: TerminalSession?): String? {
        val service = mActivity.termuxService ?: return null

        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0) return null
        val toastTitle = StringBuilder("[${indexOfSession + 1}]")
        if (!TextUtils.isEmpty(session?.mSessionName)) {
            toastTitle.append(" ").append(session?.mSessionName)
        }
        val title = session?.title
        if (!TextUtils.isEmpty(title)) {
            toastTitle.append(if (session?.mSessionName == null) " " else "\n")
            toastTitle.append(title)
        }
        return toastTitle.toString()
    }

    fun checkForFontAndColors() {
        try {
            val colorsFile: File = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
            val fontFile: File = TermuxConstants.TERMUX_FONT_FILE

            val props = Properties()
            if (colorsFile.isFile) {
                FileInputStream(colorsFile).use { input ->
                    props.load(input)
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props)
            val session = mActivity.currentSession
            session?.emulator?.mColors?.reset()
            updateBackgroundColor()

            val newTypeface = if (fontFile.exists() && fontFile.length() > 0)
                Typeface.createFromFile(fontFile)
            else
                FontManager(mActivity).loadFont(FontManager.DEFAULT_FONT_NAME)
            mActivity.terminalView.setTypeface(newTypeface)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e)
        }
    }

    fun updateBackgroundColor() {
        if (!mActivity.isVisible) return
        val session = mActivity.currentSession
        if (session != null && session.emulator != null) {
            mActivity.window.decorView.setBackgroundColor(
                session.emulator!!.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
            )
        }
    }

    /**
     * Build the display labels for all running sessions.
     */
    private fun buildSessionLabels(service: TermuxService): ArrayList<String> {
        val sessions = ArrayList<String>()
        val sessionCount = service.termuxSessionsSize
        for (i in 0 until sessionCount) {
            val termuxSession = service.getTermuxSession(i) ?: continue
            val terminalSession = termuxSession.terminalSession
            // Label format matches "[1] <name>" style used by the session toasts
            val label = mutableListOf<String>()
            val name = terminalSession.mSessionName
            label.add(if (TextUtils.isEmpty(name)) "Session [${i + 1}]" else "$name [${i + 1}]")
            val processTitle = terminalSession.title
            if (!processTitle.isNullOrEmpty() && processTitle != name) {
                label.add(processTitle)
            }
            sessions.add(label.joinToString(" "))
        }
        return sessions
    }

    /**
     * Show a dialog listing all running sessions so the user can switch between them
     */
    fun showSessionListDialog() {
        val service = mActivity.termuxService ?: return

        // Use a plain ListView adapter instead of setSingleChoiceItems so that
        // item long presses can be handled (to rename a session in place).
        val listAdapter = ArrayAdapter(
            mActivity,
            android.R.layout.simple_list_item_single_choice,
            buildSessionLabels(service)
        )
        mSessionListAdapter = listAdapter

        val dialog = AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_session_list)
            .setAdapter(
                listAdapter
            ) { d, which ->
                switchToSession(which)
                d.dismiss()
            }
            .setNeutralButton(R.string.action_session_list_more, null)
            .setNegativeButton(R.string.action_new_session) { _, _ ->
                addNewSession(false, null)
            }
            .setPositiveButton(R.string.action_session_list_close, null)
            .create()

        // Bind the "More" button manually so that clicking it neither dismisses
        // the dialog nor rebuilds it, and so the popup can be anchored directly
        // below the button.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { moreButton ->
                showSessionMoreMenu(moreButton)
            }

            // Mark the current session as the checked item.
            dialog.listView?.apply {
                choiceMode = ListView.CHOICE_MODE_SINGLE
                setOnItemLongClickListener { _, _, position, _ ->
                    val termuxSession = service.getTermuxSession(position)
                        ?: return@setOnItemLongClickListener false
                    renameSession(termuxSession.terminalSession)
                    true
                }
            }

            refreshSessionListDialog(dialog, service, listAdapter)
        }

        mSessionListDialog = dialog

        dialog.setOnDismissListener {
            if (mSessionListDialog === dialog) {
                mSessionListDialog = null
                mSessionListAdapter = null
            }
        }

        dialog.show()
    }

    /**
     * Rebuild the session list dialog contents in place, keeping the dialog open.
     */
    private fun refreshSessionListDialog(
        dialog: AlertDialog,
        service: TermuxService,
        listAdapter: ArrayAdapter<String>
    ) {
        val labels = buildSessionLabels(service)
        listAdapter.clear()
        listAdapter.addAll(labels)
        listAdapter.notifyDataSetChanged()

        val currentIndex = service.getIndexOfSession(mActivity.currentSession)
        dialog.listView?.let { listView ->
            listView.choiceMode = ListView.CHOICE_MODE_SINGLE
            if (currentIndex in labels.indices) {
                listView.setItemChecked(currentIndex, true)
            } else {
                listView.clearChoices()
            }
        }
    }

    /**
     * Show the "More" popup menu anchored below the "More" button.
     *
     * Provides three actions:
     * - Create a new system session
     * - Finish all other sessions, keeping the current one
     * - Finish all sessions
     */
    private fun showSessionMoreMenu(anchor: View) {
        val service = mActivity.termuxService ?: return
        val currentSession = mActivity.currentSession

        PopupMenu(mActivity, anchor).apply {
            menu.add(Menu.NONE, MENU_NEW_SYSTEM_SESSION, Menu.NONE, R.string.action_new_system_session)
            menu.add(Menu.NONE, MENU_FINISH_OTHER_SESSIONS, Menu.NONE, R.string.action_finish_other_sessions)
            menu.add(Menu.NONE, MENU_FINISH_ALL_SESSIONS, Menu.NONE, R.string.action_finish_all_sessions)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_NEW_SYSTEM_SESSION -> {
                        addNewSession(false, null)
                        true
                    }
                    MENU_FINISH_OTHER_SESSIONS -> {
                        if (service.termuxSessionsSize > 1) {
                            val toFinish = service.termuxSessions
                                .filter { it.terminalSession != currentSession }
                                .map { it.terminalSession }
                            toFinish.forEach { it.finishIfRunning() }
                        }
                        true
                    }
                    MENU_FINISH_ALL_SESSIONS -> {
                        val toFinish = service.termuxSessions.map { it.terminalSession }
                        toFinish.forEach { it.finishIfRunning() }
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val LOG_TAG = "TermuxTerminalSessionActivityClient"

        /** Menu item ids for the session list "More" popup menu. */
        private const val MENU_NEW_SYSTEM_SESSION = 1
        private const val MENU_FINISH_OTHER_SESSIONS = 2
        private const val MENU_FINISH_ALL_SESSIONS = 3
    }
}
