package com.termux.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.View
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
import com.termux.shared.terminal.SessionInfo
import com.termux.shared.terminal.TerminalState
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
    private var mSessionListAdapter: SessionListAdapter? = null

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
     * Builds the rows shown in the session list.
     *
     * Each row carries the session's live state and working directory, so the list doubles as a health
     * view: a dead session, or one stuck in an unexpected directory, is visible without switching to it.
     */
    private fun buildSessionRows(service: TermuxService): List<SessionRow> {
        val count = service.termuxSessionsSize
        val infos = ArrayList<SessionInfo>(count)
        val names = ArrayList<String?>(count)

        // terminalControl is created during onResume; the session dialog can be opened before that on a
        // cold start, so the lookup degrades rather than throwing.
        val live = if (mActivity.hasTerminalControl) {
            runCatching { mActivity.terminalControl.sessions().associateBy { it.id } }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        for (i in 0 until count) {
            val termuxSession = service.getTermuxSession(i) ?: continue
            val terminalSession = termuxSession.terminalSession
            names.add(terminalSession.mSessionName)

            val id = terminalSession.mSessionName ?: terminalSession.getPid().toString()
            infos.add(
                live[id] ?: SessionInfo(
                    id = id,
                    title = terminalSession.title.orEmpty(),
                    pid = terminalSession.getPid(),
                    isRunning = terminalSession.isRunning,
                    exitStatus = null,
                    cwd = null,
                    isAltBuffer = false,
                    // Without a live snapshot the best available answer is liveness alone.
                    state = if (terminalSession.isRunning) {
                        TerminalState.Idle(0L)
                    } else {
                        TerminalState.Dead(null)
                    },
                ),
            )
        }

        return SessionRowBuilder.build(
            sessions = infos,
            names = names,
            currentIndex = service.getIndexOfSession(mActivity.currentSession),
        )
    }

    /**
     * Show a dialog listing all running sessions so the user can switch between them
     */
    fun showSessionListDialog() {
        val service = mActivity.termuxService ?: return

        // A custom adapter replaces the old joined-text rows; long press still renames a session in place.
        val listAdapter = SessionListAdapter(mActivity)
        listAdapter.submit(buildSessionRows(service))
        mSessionListAdapter = listAdapter

        val dialog = AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_session_list)
            .setAdapter(listAdapter) { d, which ->
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
        listAdapter: SessionListAdapter,
    ) {
        val rows = buildSessionRows(service)
        listAdapter.submit(rows)

        val currentIndex = service.getIndexOfSession(mActivity.currentSession)
        dialog.listView?.let { listView ->
            listView.choiceMode = ListView.CHOICE_MODE_SINGLE
            if (currentIndex in rows.indices) {
                listView.setItemChecked(currentIndex, true)
            } else {
                listView.clearChoices()
            }
        }
    }

    /**
     * Opens the scrollback search dialog.
     *
     * The dialog is intentionally simple: a query field, a match count and previous/next buttons. It
     * does not highlight inside the terminal view (that would require touching the renderer), it reports
     * where the matches are and scrolls the terminal to them, which covers the actual need of finding
     * something in a long log.
     */
    private fun showScrollbackSearchDialog() {
        if (!mActivity.hasTerminalControl) {
            mActivity.showToast(mActivity.getString(R.string.search_no_session), true)
            return
        }

        val container = android.widget.LinearLayout(mActivity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (mActivity.resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = android.widget.EditText(mActivity).apply {
            hint = mActivity.getString(R.string.search_hint)
            setSingleLine(true)
        }
        val resultLabel = android.widget.TextView(mActivity).apply {
            val pad = (mActivity.resources.displayMetrics.density * 4).toInt()
            setPadding(0, pad, 0, pad)
            text = ""
        }
        container.addView(input)
        container.addView(resultLabel)

        var matches: List<com.termux.shared.terminal.TranscriptSearch.Match> = emptyList()
        var index = -1
        var searchJob: kotlinx.coroutines.Job? = null

        fun showCurrent() {
            val match = matches.getOrNull(index) ?: return
            resultLabel.text = buildString {
                append(index + 1).append('/').append(matches.size).append("  ")
                append(match.line.trim().take(120))
            }
        }

        fun jump(direction: Int) {
            if (matches.isEmpty()) return
            index = if (direction > 0) {
                if (index + 1 >= matches.size) 0 else index + 1
            } else {
                if (index <= 0) matches.size - 1 else index - 1
            }
            val match = matches[index]
            // Scroll the terminal so the matching row is visible.
            mActivity.terminalView?.let { view ->
                val emulator = mActivity.currentSession?.emulator ?: return@let
                val screen = emulator.getScreen()
                val target = match.lineIndex - screen.activeTranscriptRows
                view.setTopRow(target.coerceAtMost(0))
            }
            showCurrent()
        }

        val dialog = AlertDialog.Builder(mActivity)
            .setTitle(R.string.action_search)
            .setView(container)
            .setPositiveButton(R.string.search_next) { _, _ -> jump(+1) }
            .setNegativeButton(R.string.search_prev) { _, _ -> jump(-1) }
            .setNeutralButton(R.string.search_close, null)
            .create()

        // Re-run the search as the user types, debounced by cancelling the previous job.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString().orEmpty()
                searchJob?.cancel()
                if (query.isBlank()) {
                    matches = emptyList()
                    index = -1
                    resultLabel.text = ""
                    return
                }
                searchJob = mActivity.lifecycleScope.launch {
                    val found = runCatching {
                        mActivity.terminalControl.searchScrollback(query)
                    }.getOrDefault(emptyList())
                    matches = found
                    index = if (found.isEmpty()) -1 else 0
                    resultLabel.text = com.termux.shared.terminal.TranscriptSearch
                        .summarize(found, query)
                    showCurrent()
                }
            }
        })

        dialog.setOnDismissListener { searchJob?.cancel() }
        dialog.show()
    }

    /**
     * Opens the session template picker.
     *
     * Selecting a template creates a session in the template's directory and, if it defines one, types
     * its first command. Long-pressing a user template offers deletion.
     */
    private fun showSessionTemplateDialog() {
        val service = mActivity.termuxService ?: return
        val store = SessionTemplateStore(mActivityTemplateFile())
        val templates = store.load()
        if (templates.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.template_none), true)
            return
        }

        val labels = templates.map { template ->
            buildString {
                append(template.name)
                template.workingDirectory?.let { append("  ·  ").append(it) }
                template.initialCommand?.let { append("  ›  ").append(it.take(40)) }
            }
        }.toTypedArray()

        AlertDialog.Builder(mActivity)
            .setTitle(R.string.action_new_from_template)
            .setItems(labels) { _, which ->
                launchFromTemplate(service, templates[which])
            }
            .setNeutralButton(R.string.action_manage_templates, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        dismiss()
                        showManageTemplatesDialog(store)
                    }
                }
            }
            .show()
    }

    /** Creates a session from [template] and runs its initial command once the shell is ready. */
    private fun launchFromTemplate(service: TermuxService, template: SessionTemplates.Template) {
        if (service.termuxSessionsSize >= MAX_SESSIONS) {
            mActivity.showToast(mActivity.getString(R.string.msg_max_terminals_reached), true)
            return
        }

        val workingDirectory = template.workingDirectory?.let {
            if (it.startsWith("~")) mActivity.properties?.getDefaultWorkingDirectory() ?: it else it
        } ?: mActivity.currentSession?.getCwd()

        val newTermuxSession = service.createTermuxSession(
            null,
            null,
            null,
            workingDirectory,
            false,
            template.name,
        ) ?: return
        val terminalSession = newTermuxSession.terminalSession
        setCurrentSession(terminalSession)

        val command = SessionTemplates.launchCommand(template) ?: return
        // The shell needs a moment to start before input is accepted.
        mActivity.lifecycleScope.launch {
            kotlinx.coroutines.delay(TEMPLATE_START_DELAY_MS)
            terminalSession.write(command + "\r")
        }
    }

    /** Lists templates with the ability to delete user-defined ones. */
    private fun showManageTemplatesDialog(store: SessionTemplateStore) {
        val templates = store.load()
        val labels = templates.map { template ->
            if (template.isBuiltin) {
                mActivity.getString(R.string.template_builtin_label, template.name)
            } else {
                template.name
            }
        }.toTypedArray()

        AlertDialog.Builder(mActivity)
            .setTitle(R.string.action_manage_templates)
            .setItems(labels) { _, which ->
                val template = templates[which]
                if (template.isBuiltin) {
                    mActivity.showToast(mActivity.getString(R.string.template_builtin_undeletable), false)
                } else {
                    confirmDeleteTemplate(store, template)
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDeleteTemplate(store: SessionTemplateStore, template: SessionTemplates.Template) {
        AlertDialog.Builder(mActivity)
            .setTitle(R.string.template_delete_title)
            .setMessage(template.name)
            .setPositiveButton(R.string.template_delete_confirm) { _, _ ->
                store.delete(template.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Template storage lives in app-private files, not in the user's home directory. */
    private fun mActivityTemplateFile(): java.io.File =
        java.io.File(mActivity.filesDir, "session-templates.json")

    /**
     * Opens the recorded command history.
     *
     * Selecting an entry types it into the current session rather than running it, because a recorded
     * command may well be something the user wants to adjust first.
     */
    private fun showCommandHistoryDialog() {
        val recorder = mActivity.commandHistoryRecorder
        if (recorder == null) {
            mActivity.showToast(mActivity.getString(R.string.history_unavailable), true)
            return
        }

        val entries = recorder.readAll().reversed().take(HISTORY_DIALOG_LIMIT)
        if (entries.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.history_empty), true)
            return
        }

        val labels = entries.map { entry ->
            buildString {
                entry.exitStatus?.let { append(if (it == 0) "✓ " else "✗$it ") }
                append(entry.command)
                append("   ")
                append(formatDuration(entry.durationMs))
            }
        }.toTypedArray()

        AlertDialog.Builder(mActivity)
            .setTitle(R.string.action_command_history)
            .setItems(labels) { _, which ->
                val session = mActivity.currentSession ?: return@setItems
                session.write(entries[which].command + "\r")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Formats a duration compactly for the history list. */
    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> "${millis / 1_000}s"
        else -> "${millis / 60_000}m"
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

        // Wrap the context in an AppCompat popup theme so the popup shares the
        // app's DayNight styling instead of the platform default look.
        val themedContext = ContextThemeWrapper(
            mActivity, androidx.appcompat.R.style.ThemeOverlay_AppCompat_DayNight
        )

        PopupMenu(themedContext, anchor).apply {
            menu.add(Menu.NONE, MENU_SEARCH_SCROLLBACK, Menu.NONE, R.string.action_search)
            menu.add(Menu.NONE, MENU_NEW_FROM_TEMPLATE, Menu.NONE, R.string.action_new_from_template)
            menu.add(Menu.NONE, MENU_COMMAND_HISTORY, Menu.NONE, R.string.action_command_history)
            menu.add(Menu.NONE, MENU_NEW_SYSTEM_SESSION, Menu.NONE, R.string.action_new_system_session)
            menu.add(Menu.NONE, MENU_FINISH_OTHER_SESSIONS, Menu.NONE, R.string.action_finish_other_sessions)
            menu.add(Menu.NONE, MENU_FINISH_ALL_SESSIONS, Menu.NONE, R.string.action_finish_all_sessions)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SEARCH_SCROLLBACK -> {
                        showScrollbackSearchDialog()
                        true
                    }
                    MENU_NEW_FROM_TEMPLATE -> {
                        showSessionTemplateDialog()
                        true
                    }
                    MENU_COMMAND_HISTORY -> {
                        showCommandHistoryDialog()
                        true
                    }
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
        private const val MENU_SEARCH_SCROLLBACK = 4
        private const val MENU_NEW_FROM_TEMPLATE = 5
        private const val MENU_COMMAND_HISTORY = 6

        /** How long to wait for a new shell before typing a template's initial command. */
        private const val TEMPLATE_START_DELAY_MS: Long = 350L

        /** Maximum entries shown in the history dialog. */
        private const val HISTORY_DIALOG_LIMIT: Int = 100
    }
}
