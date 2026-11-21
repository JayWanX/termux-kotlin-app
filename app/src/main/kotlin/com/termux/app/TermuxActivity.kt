package com.termux.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.Toast
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager.widget.ViewPager
import com.termux.R
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.api.file.FileReceiverActivity
import com.termux.app.terminal.TermuxActivityRootView
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.app.terminal.io.TerminalToolbarViewPager
import com.termux.app.terminal.io.TermuxTerminalExtraKeys
import com.termux.shared.activities.ReportActivity
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.shared.theme.NightMode
import com.termux.shared.view.ViewUtils
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.termux.app.terminal.TerminalControlImpl
import com.termux.app.terminal.CommandCompletionNotifier
import com.termux.app.ai.AiPanelController
import com.termux.shared.terminal.TerminalControl
import com.termux.shared.termux.shell.TermuxShellManager

/**
 * A terminal emulator activity.
 */
class TermuxActivity : AppCompatActivity(), ServiceConnection {

    /** The connection to the [TermuxService]. */
    var mTermuxService: TermuxService? = null

    /** The [TerminalView] shown in [TermuxActivity] that displays the terminal. */
    lateinit var mTerminalView: TerminalView

    /**
     * The shared entry point for observing and driving the terminal.
     *
     * Optional features (notifications, the assistant panel, accessibility helpers) must go through
     * this instead of touching [TerminalSession] directly.
     *
     * This is a read-only accessor over a private backing field so other classes can check
     * [hasTerminalControl] without reaching into `lateinit` state and risking an exception.
     */
    val terminalControl: TerminalControl
        get() = terminalControlBacking
            ?: error("terminalControl accessed before it was created")

    private var terminalControlBacking: TerminalControl? = null

    /** True once [terminalControl] is safe to read. */
    val hasTerminalControl: Boolean
        get() = terminalControlBacking != null

    private var terminalControlImpl: TerminalControlImpl? = null

    /** Raises a notification when a long-running command finishes while the app is in the background. */
    private var commandCompletionNotifier: CommandCompletionNotifier? = null

    /**
     * The optional assistant panel.
     *
     * Null until the user first opens it, and only created when the assistant is enabled, so that a
     * user who never turns the feature on pays nothing for it.
     */
    private var aiPanelController: AiPanelController? = null

    /** Container the assistant panel is mounted into, created lazily. */
    private var aiPanelContainer: FrameLayout? = null

    /** True when the side panel is narrow enough that labels should be abbreviated. */
    private var aiPanelIsCompact: Boolean = false

    /** Where the current edge swipe began, and whether one is in progress. */
    private var assistantGestureStartX: Float = 0f
    private var assistantGestureTracking: Boolean = false

    /** The [com.termux.view.TerminalViewClient] interface implementation. */
    var mTermuxTerminalViewClient: TermuxTerminalViewClient? = null

    /** The [com.termux.terminal.TerminalSessionClient] interface implementation. */
    var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = null

    /** Termux app shared preferences manager. */
    private var mPreferences: TermuxAppSharedPreferences? = null

    /** Termux app SharedProperties loaded from termux.properties */
    private var mProperties: TermuxAppSharedProperties? = null

    /** The root view of the [TermuxActivity]. */
    var mTermuxActivityRootView: TermuxActivityRootView? = null

    /** The space at the bottom of mTermuxActivityRootView of the [TermuxActivity]. */
    var mTermuxActivityBottomSpaceView: View? = null

    /** The terminal extra keys view. */
    var mExtraKeysView: ExtraKeysView? = null

    /** The client for the [mExtraKeysView]. */
    var mTermuxTerminalExtraKeys: TermuxTerminalExtraKeys? = null

    /** The [TermuxActivity] broadcast receiver for various things like terminal style configuration changes. */
    private val mTermuxActivityBroadcastReceiver: BroadcastReceiver = TermuxActivityBroadcastReceiver()

    /** The last toast shown, used cancel current toast before showing new in [showToast]. */
    var mLastToast: Toast? = null

    /** If between onResume() and onStop(). */
    private var mIsVisible = false

    /** If onResume() was called after onCreate(). */
    private var mIsOnResumeAfterOnCreate = false

    /** If activity was restarted like due to call to [recreate] after receiving [TERMUX_ACTIVITY.ACTION_RELOAD_STYLE]. */
    private var mIsActivityRecreated = false

    /** The [TermuxActivity] is in an invalid state and must not be run. */
    private var mIsInvalidState = false

    private var mNavBarHeight = 0

    private var mTerminalToolbarDefaultHeight = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")
        mIsOnResumeAfterOnCreate = true

        if (savedInstanceState != null) {
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false)
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties()
        reloadProperties()

        setActivityTheme()

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_termux)

        // Load termux shared preferences
        mPreferences = TermuxAppSharedPreferences.build(this, true)
        if (mPreferences == null) {
            mIsInvalidState = true
            return
        }

        setMargins()

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view)
        mTermuxActivityRootView?.setActivity(this)
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view)
        mTermuxActivityRootView?.setOnApplyWindowInsetsListener(TermuxActivityRootView.WindowInsetsListener())

        val content = findViewById<View>(android.R.id.content)
        content.setOnApplyWindowInsetsListener { _, insets ->
            mNavBarHeight = insets.systemWindowInsetBottom
            insets
        }

        if (mProperties?.isUsingFullScreen() == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setTermuxTerminalViewAndClients()
        setTerminalToolbarView(savedInstanceState)

        registerForContextMenu(mTerminalView)

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        try {
            // Start the TermuxService and make it run regardless of who is bound to it
            val serviceIntent = Intent(this, TermuxService::class.java)
            startService(serviceIntent)

            // Attempt to bind to the service
            if (!bindService(serviceIntent, this, 0)) {
                throw RuntimeException("bindService() failed")
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "TermuxActivity failed to start TermuxService", e)
            Logger.showToast(
                this,
                getString(
                    if (e.message != null && e.message!!.contains("app is in background"))
                        R.string.error_termux_service_start_failed_bg
                    else
                        R.string.error_termux_service_start_failed_general
                ),
                true
            )
            mIsInvalidState = true
            return
        }

        // Send the BROADCAST_TERMUX_OPENED broadcast to notify apps that Termux app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this)
    }

    override fun onStart() {
        super.onStart()
        Logger.logDebug(LOG_TAG, "onStart")

        if (mIsInvalidState) return

        mIsVisible = true

        mTermuxTerminalSessionActivityClient?.onStart()
        mTermuxTerminalViewClient?.onStart()

        if (mPreferences?.isTerminalMarginAdjustmentEnabled() == true) {
            addTermuxActivityRootViewGlobalLayoutListener()
        }

        registerTermuxActivityBroadcastReceiver()

        // Styles may have been changed while another activity (e.g. the built-in
        // styling screen) was in front, in which case the broadcast we send was
        // lost because this activity had stopped. Re-apply styling on return so
        // changes such as the cursor shape take effect.
        reloadProperties()
        mTermuxTerminalViewClient?.onReloadActivityStyling()
    }

    override fun onResume() {
        super.onResume()
        Logger.logVerbose(LOG_TAG, "onResume")

        if (mIsInvalidState) return

        mTermuxTerminalSessionActivityClient?.onResume()
        mTermuxTerminalViewClient?.onResume()

        startTerminalControl()

        // Check if a crash happened on last run
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)

        mIsOnResumeAfterOnCreate = false
    }

    override fun onStop() {
        super.onStop()
        Logger.logDebug(LOG_TAG, "onStop")

        if (mIsInvalidState) return

        mIsVisible = false

        stopTerminalControl()

        mTermuxTerminalSessionActivityClient?.onStop()
        mTermuxTerminalViewClient?.onStop()

        removeTermuxActivityRootViewGlobalLayoutListener()
        unregisterTermuxActivityBroadcastReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.logDebug(LOG_TAG, "onDestroy")

        if (mIsInvalidState) return

        mTermuxService?.let {
            it.unsetTermuxTerminalSessionClient()
            mTermuxService = null
        }

        try {
            unbindService(this)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState")

        super.onSaveInstanceState(savedInstanceState)
        saveTerminalToolbarTextInput(savedInstanceState)
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true)
    }

    /**
     * Part of the [ServiceConnection] interface. The service is bound with
     * [bindService] in [onCreate] which will cause a call to this callback method.
     */
    override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        mTermuxService = (service as TermuxService.LocalBinder).service
        
        val termuxService = mTermuxService
        if (termuxService == null) {
            Logger.logError(LOG_TAG, "onServiceConnected: mTermuxService is null after assignment")
            return
        }

        val intent = intent
        setIntent(null)

        if (termuxService.isTermuxSessionsEmpty) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(this@TermuxActivity) {
                    if (mTermuxService == null) return@setupBootstrapIfNeeded
                    try {
                        var launchFailsafe = false
                        if (intent?.extras != null) {
                            launchFailsafe = intent.extras?.getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false) ?: false
                        }
                        mTermuxTerminalSessionActivityClient?.addNewSession(launchFailsafe, null)
                    } catch (e: WindowManager.BadTokenException) {
                        // Activity finished - ignore.
                    }
                }
            } else {
                finishActivityIfNotFinishing()
            }
        } else {
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN == intent.action) {
                val isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                mTermuxTerminalSessionActivityClient?.addNewSession(isFailSafe, null)
            } else {
                mTermuxTerminalSessionActivityClient?.setCurrentSession(
                    mTermuxTerminalSessionActivityClient?.currentStoredSessionOrLast
                )
            }
        }

        // Update the TerminalSession and TerminalEmulator clients
        mTermuxService?.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")
        finishActivityIfNotFinishing()
    }

    private fun reloadProperties() {
        mProperties?.loadTermuxPropertiesFromDisk()
        mTermuxTerminalViewClient?.onReloadProperties()
    }

    private fun setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties?.getNightMode())

        // Set activity night mode
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().name, true)
    }

    private fun setMargins() {
        val relativeLayout = findViewById<RelativeLayout>(R.id.activity_termux_root_relative_layout)
        val marginHorizontal = mProperties?.getTerminalMarginHorizontal() ?: 0
        val marginVertical = mProperties?.getTerminalMarginVertical() ?: 0
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical)
    }

    fun addTermuxActivityRootViewGlobalLayoutListener() {
        mTermuxActivityRootView?.viewTreeObserver?.addOnGlobalLayoutListener(mTermuxActivityRootView)
    }

    fun removeTermuxActivityRootViewGlobalLayoutListener() {
        mTermuxActivityRootView?.viewTreeObserver?.removeOnGlobalLayoutListener(mTermuxActivityRootView)
    }

    private fun setTermuxTerminalViewAndClients() {
        val termuxTerminalSessionActivityClient = TermuxTerminalSessionActivityClient(this)
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient
        val termuxTerminalViewClient = TermuxTerminalViewClient(this, termuxTerminalSessionActivityClient)
        mTermuxTerminalViewClient = termuxTerminalViewClient

        mTerminalView = findViewById(R.id.terminal_view)
        mTerminalView.setTerminalViewClient(termuxTerminalViewClient)

        termuxTerminalViewClient.onCreate()
        termuxTerminalSessionActivityClient.onCreate()
    }

    private fun setTerminalToolbarView(savedInstanceState: Bundle?) {
        mTermuxTerminalExtraKeys = TermuxTerminalExtraKeys(
            this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient
        )

        val terminalToolbarViewPager = terminalToolbarViewPager
        if (mPreferences?.shouldShowTerminalToolbar() == true) {
            terminalToolbarViewPager?.visibility = View.VISIBLE
        }

        val layoutParams = terminalToolbarViewPager?.layoutParams
        mTerminalToolbarDefaultHeight = layoutParams?.height?.toFloat() ?: 0f

        setTerminalToolbarHeight()

        var savedTextInput: String? = null
        if (savedInstanceState != null) {
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT)
        }

        terminalToolbarViewPager?.adapter = TerminalToolbarViewPager.PageAdapter(this, savedTextInput)
        terminalToolbarViewPager?.addOnPageChangeListener(
            TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager)
        )
    }

    private fun setTerminalToolbarHeight() {
        val terminalToolbarViewPager = terminalToolbarViewPager ?: return

        val layoutParams = terminalToolbarViewPager.layoutParams
        val extraKeysInfo = mTermuxTerminalExtraKeys?.getExtraKeysInfo()
        val matrix = extraKeysInfo?.getMatrix()
        layoutParams.height = Math.round(
            mTerminalToolbarDefaultHeight *
                (matrix?.size ?: 0) *
                (mProperties?.getTerminalToolbarHeightScaleFactor() ?: 1f)
        ).toInt()
        terminalToolbarViewPager.layoutParams = layoutParams
    }

    /**
     * The text input page grows with its content between a minimum and a
     * maximum height, so a single line takes little space while several lines
     * stay fully visible.
     *
     * Heights are expressed in dp rather than toolbar row multiples because the
     * text input page must be tall enough to contain the send button plus the
     * container padding even for a single line of text.
     *
     * @param textInputHeightPx the measured content height, or 0 to use the
     *        page's minimum height.
     */
    fun updateTerminalToolbarHeightForPage(page: Int, textInputHeightPx: Int = 0) {
        val terminalToolbarViewPager = terminalToolbarViewPager ?: return

        val layoutParams = terminalToolbarViewPager.layoutParams
        val scaleFactor = mProperties?.getTerminalToolbarHeightScaleFactor() ?: 1f
        val density = resources.displayMetrics.density

        val newHeight = if (page == 1) {
            // The text input page has a hard floor: the send button plus the
            // container padding must always fit, regardless of the user's
            // toolbar height scale factor.
            val hardMinPx = (TEXT_INPUT_HARD_MIN_HEIGHT_DP * density).toInt()
            val scaledMinPx = (TEXT_INPUT_MIN_HEIGHT_DP * density * scaleFactor).toInt()
            val minHeight = maxOf(hardMinPx, scaledMinPx)
            val maxHeight = maxOf(
                minHeight,
                (TEXT_INPUT_MAX_HEIGHT_DP * density * scaleFactor).toInt()
            )

            // Clamp the measured content height. When the content exceeds the
            // maximum the EditText scrolls internally, so the page must stay at
            // exactly the maximum to avoid extra bottom padding.
            when {
                textInputHeightPx <= 0 -> minHeight
                textInputHeightPx < minHeight -> minHeight
                textInputHeightPx > maxHeight -> maxHeight
                else -> textInputHeightPx
            }
        } else {
            // Bail out until the default row height has been measured.
            if (mTerminalToolbarDefaultHeight <= 0f) return
            val extraKeysInfo = mTermuxTerminalExtraKeys?.getExtraKeysInfo()
            val matrix = extraKeysInfo?.getMatrix()
            Math.round(mTerminalToolbarDefaultHeight * scaleFactor * (matrix?.size ?: 0)).toInt()
        }

        // Avoid redundant layout passes when the height does not change.
        if (layoutParams.height == newHeight) return

        layoutParams.height = newHeight
        terminalToolbarViewPager.layoutParams = layoutParams
    }

    fun toggleTerminalToolbar() {
        val terminalToolbarViewPager = terminalToolbarViewPager ?: return

        val showNow = mPreferences?.toogleShowTerminalToolbar() ?: false
        Logger.showToast(
            this,
            if (showNow) getString(R.string.msg_enabling_terminal_toolbar)
            else getString(R.string.msg_disabling_terminal_toolbar),
            true
        )
        terminalToolbarViewPager.visibility = if (showNow) View.VISIBLE else View.GONE
        if (showNow && isTerminalToolbarTextInputViewSelected) {
            findViewById<View>(R.id.terminal_toolbar_text_input).requestFocus()
        }
    }

    private fun saveTerminalToolbarTextInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        val textInputView = findViewById<EditText>(R.id.terminal_toolbar_text_input)
        if (textInputView != null) {
            val textInput = textInputView.text.toString()
            if (textInput.isNotEmpty()) {
                savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput)
            }
        }
    }

    @Suppress("MissingSuperCall")
    @SuppressLint("RtlHardcoded")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back closes the assistant panel first, so reaching for the panel does not trap the user in
        // it (or exit the app by muscle memory).
        val controller = aiPanelController
        if (controller != null && controller.isVisible()) {
            controller.hide()
            return
        }
        finishActivityIfNotFinishing()
    }

    fun finishActivityIfNotFinishing() {
        if (!this@TermuxActivity.isFinishing) {
            finish()
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    fun showToast(text: String?, longDuration: Boolean) {
        if (text.isNullOrEmpty()) return
        mLastToast?.cancel()
        mLastToast = Toast.makeText(
            this@TermuxActivity, text,
            if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        )
        mLastToast?.setGravity(Gravity.TOP, 0, 0)
        mLastToast?.show()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        val currentSession = currentSession ?: return

        val autoFillEnabled = mTerminalView.isAutoFillEnabled

        if (!DataUtils.isNullOrEmpty(mTerminalView.storedSelectedText)) {
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        }
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(
            Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE,
            resources.getString(R.string.action_kill_process, currentSession.getPid())
        ).isEnabled = currentSession.isRunning
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .isCheckable = true
        menu.findItem(CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON)?.isChecked = mPreferences?.shouldKeepScreenOn() == true
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        // The assistant entry only exists when the user has enabled and configured it. This must not
        // consult aiPanelController: that is null until the panel is first opened, so gating on it
        // would leave the entry permanently unreachable.
        //
        // It is listed before Settings because the assistant is an action taken on this terminal,
        // while Settings leaves the terminal behind; the two entries the user actually alternates
        // between end up adjacent instead of separated by a navigation item.
        if (com.termux.app.ai.AiConfig.get(this).isUsable()) {
            menu.add(Menu.NONE, CONTEXT_MENU_ASSISTANT_ID, Menu.NONE, R.string.assistant_menu_entry)
        }
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
    }

    /** Hook system menu to show context menu instead. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mTerminalView.showContextMenu()
        return false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = currentSession

        return when (item.itemId) {
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                mTermuxTerminalViewClient?.shareSelectedText()
                true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                mTerminalView.requestAutoFillUsername()
                true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                mTerminalView.requestAutoFillPassword()
                true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn()
                true
            }
            CONTEXT_MENU_HELP_ID -> {
                ActivityUtils.startActivity(this, Intent(this, HelpActivity::class.java))
                true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
                true
            }
            CONTEXT_MENU_ASSISTANT_ID -> {
                toggleAssistantPanel()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        mTerminalView.onContextMenuClosed(menu)
    }

    private fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) return

        val b = AlertDialog.Builder(this)
        b.setIcon(android.R.drawable.ic_dialog_alert)
        b.setMessage(R.string.title_confirm_kill_process)
        b.setPositiveButton(android.R.string.yes) { dialog, _ ->
            dialog.dismiss()
            session.finishIfRunning()
        }
        b.setNegativeButton(android.R.string.no, null)
        b.show()
    }

    private fun onResetTerminalSession(session: TerminalSession?) {
        if (session == null) return

        val b = AlertDialog.Builder(this)
        b.setIcon(android.R.drawable.ic_dialog_alert)
        b.setMessage(R.string.title_confirm_reset_terminal)
        b.setPositiveButton(android.R.string.yes) { dialog, _ ->
            dialog.dismiss()
            session.reset()
            showToast(resources.getString(R.string.msg_terminal_reset), true)
            mTermuxTerminalSessionActivityClient?.onResetTerminalSession()
        }
        b.setNegativeButton(android.R.string.no, null)
        b.show()
    }

    private fun toggleKeepScreenOn() {
        if (mTerminalView.keepScreenOn) {
            mTerminalView.keepScreenOn = false
            mPreferences?.setKeepScreenOn(false)
        } else {
            mTerminalView.keepScreenOn = true
            mPreferences?.setKeepScreenOn(true)
        }
    }

    /**
     * For processes to access primary external storage, termux needs to be granted permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    fun requestStoragePermission(isPermissionCallback: Boolean) {
        Thread {
            val requestCode = if (isPermissionCallback) -1 else PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION

            if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    this@TermuxActivity, requestCode, !isPermissionCallback
                )
            ) {
                if (isPermissionCallback) {
                    Logger.logInfoAndShowToast(
                        this@TermuxActivity, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request)
                    )
                }
                TermuxInstaller.setupStorageSymlinks(this@TermuxActivity)
            } else {
                if (isPermissionCallback) {
                    Logger.logInfoAndShowToast(
                        this@TermuxActivity, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request)
                    )
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(
            LOG_TAG, "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, " +
                "data: ${IntentUtils.getIntentString(data)}"
        )
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.logVerbose(
            LOG_TAG, "onRequestPermissionsResult: requestCode: $requestCode, permissions: ${permissions.contentToString()}, " +
                "grantResults: ${grantResults.contentToString()}"
        )
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    val navBarHeight: Int
        get() = mNavBarHeight

    val termuxActivityRootView: TermuxActivityRootView?
        get() = mTermuxActivityRootView

    val termuxActivityBottomSpaceView: View?
        get() = mTermuxActivityBottomSpaceView

    val extraKeysView: ExtraKeysView?
        get() = mExtraKeysView

    val termuxTerminalExtraKeys: TermuxTerminalExtraKeys?
        get() = mTermuxTerminalExtraKeys

    fun setExtraKeysView(extraKeysView: ExtraKeysView?) {
        mExtraKeysView = extraKeysView
    }

    val terminalToolbarViewPager: ViewPager?
        get() = findViewById(R.id.terminal_toolbar_view_pager)

    val terminalToolbarDefaultHeight: Float
        get() = mTerminalToolbarDefaultHeight

    val isTerminalViewSelected: Boolean
        get() = terminalToolbarViewPager?.currentItem == 0

    val isTerminalToolbarTextInputViewSelected: Boolean
        get() = terminalToolbarViewPager?.currentItem == 1

    fun termuxSessionListNotifyUpdated() {
        // The left session drawer has been removed; the session list dialog
        // rebuilds its entries every time it is shown, so no refresh is needed.
    }

    val isVisible: Boolean
        get() = mIsVisible

    val isOnResumeAfterOnCreate: Boolean
        get() = mIsOnResumeAfterOnCreate

    val isActivityRecreated: Boolean
        get() = mIsActivityRecreated

    val termuxService: TermuxService?
        get() = mTermuxService

    val terminalView: TerminalView
        get() = mTerminalView

    val termuxTerminalViewClient: TermuxTerminalViewClient?
        get() = mTermuxTerminalViewClient

    val termuxTerminalSessionClient: TermuxTerminalSessionActivityClient?
        get() = mTermuxTerminalSessionActivityClient

    val currentSession: TerminalSession?
        get() = mTerminalView.currentSession

    /**
     * Creates the [TerminalControl] instance bound to this activity's lifetime.
     *
     * The provider lambdas keep this class from holding an activity reference, so the control object
     * cannot outlive the activity and leak it.
     */
    private fun createTerminalControl() {
        terminalControlImpl = TerminalControlImpl(
            scope = lifecycleScope,
            sessionProvider = { mTerminalView.currentSession },
            sessionsProvider = {
                runCatching {
                    TermuxShellManager.getShellManager()
                        ?.mTermuxSessions
                        ?.map { it.terminalSession }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            },
            selectSessionAction = { session ->
                mTermuxTerminalSessionActivityClient?.setCurrentSession(session)
            },
        )
        terminalControlBacking = terminalControlImpl

        // Start recording command history once the terminal exists. Idempotent on repeated resumes.
        if (commandHistoryRecorder == null) {
            commandHistoryRecorder = com.termux.app.terminal.CommandHistoryRecorder(
                historyFile = com.termux.app.terminal.CommandHistoryRecorder.defaultFile(
                    com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH,
                ),
                scope = lifecycleScope,
            ).also { it.start(terminalControl) }
        }

        val notifier = commandCompletionNotifier ?: CommandCompletionNotifier(this).also {
            commandCompletionNotifier = it
        }
        terminalControlImpl?.stateListener = { sessionId, state, snapshot ->
            notifier.onStateChanged(
                sessionId = sessionId,
                sessionTitle = snapshot?.title.orEmpty(),
                state = state,
                snapshot = snapshot,
                nowMs = android.os.SystemClock.elapsedRealtime(),
                appVisible = mIsVisible,
            )
        }
    }

    /** Thin strip above the terminal showing busy/exited state; hidden while idle. */
    private var terminalStatusView: com.termux.app.terminal.TerminalStatusView? = null

    /**
     * Records the commands the user runs, with exit status and duration.
     *
     * Exposed so the session menu can read it back; null until the terminal exists.
     */
    var commandHistoryRecorder: com.termux.app.terminal.CommandHistoryRecorder? = null
        private set

    private var statusJob: kotlinx.coroutines.Job? = null

    private fun startTerminalControl() {
        if (terminalControlImpl == null) createTerminalControl()
        terminalControlImpl?.start()
        startStatusStrip()
        installAssistantEdgeGesture()

        // Bind the optional assistant to the same terminal. This is the only coupling between the
        // terminal and the assistant; deleting the ai package turns it into a no-op.
        //
        // Bind whenever the assistant is enabled, not only when it is already fully configured, so
        // that turning it on in Settings while the activity is alive takes effect on the next resume.
        // Attachment is cheap and [AiAssistant.isAvailable] still gates whether it can be used.
        val aiConfig = com.termux.app.ai.AiConfig.get(this)
        if (aiConfig.enabled) {
            com.termux.app.ai.AiAssistant.attach(terminalControl, aiConfig)
        } else {
            com.termux.app.ai.AiAssistant.detach()
        }

        // The assistant may have been enabled or disabled while this activity was stopped, so the edge
        // exclusion is re-evaluated on every resume rather than once in onCreate.
        syncGestureExclusion()
    }

    private fun stopTerminalControl() {
        statusJob?.cancel()
        statusJob = null
        terminalStatusView?.hideStrip()
        terminalControlImpl?.stop()
    }

    /**
     * Keeps the status strip in sync with the live session state.
     *
     * Collected on the lifecycle scope so the subscription dies with the activity; without that, a
     * rotating device would leave several collectors writing to a stale view.
     */
    private fun startStatusStrip() {
        val view = findViewById<com.termux.app.terminal.TerminalStatusView>(
            R.id.terminal_status_view,
        ) ?: return
        terminalStatusView = view

        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            terminalControl.state.collect { state ->
                val title = currentSession?.let { it.mSessionName ?: it.title }
                val busyFor = (state as? com.termux.shared.terminal.TerminalState.Busy)?.let {
                    android.os.SystemClock.elapsedRealtime() - it.sinceMs
                }
                view.render(state, title, busyFor)
            }
        }
    }

    /**
     * Shows or hides the assistant panel.
     *
     * The panel is mounted as an overlay on top of the terminal rather than being part of the terminal
     * layout, so that enabling or deleting the assistant never affects terminal behaviour.
     */
    private fun toggleAssistantPanel() {
        try {
            openAssistantPanel()
        } catch (e: Throwable) {
            // An optional feature must never take the terminal down with it. The failure is reported on
            // screen as well as to logcat, because diagnosing it otherwise requires adb access.
            Logger.logError(LOG_TAG, "Failed to open assistant panel: ${e.message}")
            Logger.logStackTraceWithMessage(LOG_TAG, "assistant panel failure", e)

            val detail = buildString {
                append(e::class.java.simpleName)
                e.message?.take(160)?.let { append(": ").append(it) }
            }
            Toast.makeText(this, detail, Toast.LENGTH_LONG).show()

            // Also keep a copy on disk so the full stack survives even if logcat is unavailable.
            runCatching {
                val report = java.io.File(filesDir, "assistant-panel-error.txt")
                report.writeText(
                    buildString {
                        appendLine("time: ${java.util.Date()}")
                        appendLine(e.toString())
                        e.stackTrace.forEach { appendLine("  at $it") }
                        var cause = e.cause
                        while (cause != null) {
                            appendLine("caused by: $cause")
                            cause.stackTrace.take(20).forEach { appendLine("  at $it") }
                            cause = cause.cause
                        }
                    },
                )
            }
        }
    }

    private fun openAssistantPanel() {
        val config = com.termux.app.ai.AiConfig.get(this)
        if (!config.isUsable()) {
            Toast.makeText(this, R.string.assistant_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        // The panel can only open when the assistant is bound to a live TerminalControl. Re-attach
        // here so that enabling the assistant from Settings while this activity is kept alive works
        // without requiring a restart.
        com.termux.app.ai.AiAssistant.attach(terminalControl, config)
        if (!com.termux.app.ai.AiAssistant.isAvailable()) {
            Toast.makeText(this, R.string.assistant_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        val controller = aiPanelController ?: run {
            val container = ensureAiPanelContainer()
            val created = AiPanelController(
                lifecycleOwner = this,
                container = container,
                terminal = terminalControl,
                compact = aiPanelIsCompact,
            )
            aiPanelController = created
            created
        }

        if (controller.session() == null) {
            Toast.makeText(this, R.string.assistant_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        controller.toggle()
    }

    /** Creates the overlay container on first use. */
    private fun ensureAiPanelContainer(): FrameLayout {
        aiPanelContainer?.let { return it }

        // The panel is a side sheet, not a full-screen replacement: the assistant's purpose is to act on
        // the terminal, so the terminal must stay visible while reading replies. A full-width overlay
        // would force the user to close the panel to see what it did.
        val parent = findViewById<ViewGroup>(R.id.activity_termux_root_relative_layout)
            ?: findViewById<ViewGroup>(android.R.id.content)
            ?: error("no view group to host the assistant panel")

        val width = assistantPanelWidth()
        val container = FrameLayout(this)

        val params: ViewGroup.LayoutParams = when (parent) {
            is android.widget.RelativeLayout ->
                android.widget.RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT) }
            is android.widget.LinearLayout ->
                android.widget.LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            is FrameLayout ->
                FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { gravity = android.view.Gravity.END }
            else -> ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.layoutParams = params
        container.visibility = View.GONE
        // Transparent, not a coloured scrim: the panel draws its own rounded surface, and a filled
        // container would square off the corners behind it.
        container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        container.isClickable = true
        container.isFocusable = true

        parent.addView(container)
        aiPanelContainer = container
        aiPanelIsCompact = width < dp(320)
        return container
    }

    /**
     * Width of the side panel.
     *
     * The panel sits beside the terminal rather than over it; see [com.termux.app.ai.PanelMetrics] for
     * the rule and the reasons behind its bounds.
     */
    private fun assistantPanelWidth(): Int = com.termux.app.ai.PanelMetrics.widthPx(
        screenWidthPx = resources.displayMetrics.widthPixels,
        density = resources.displayMetrics.density,
    )

    /**
     * Offers the right-edge swipe to the assistant, if it is available.
     *
     * The observer is installed on the root view rather than the relative layout. A touch listener on a
     * parent is only consulted when no child consumes the event, and the terminal consumes every touch,
     * so the earlier listener never ran at all. [TermuxActivityRootView.dispatchTouchEvent] sees the
     * event first regardless of who ends up handling it.
     *
     * The observer never consumes anything: it watches the drag and opens the panel when it recognises
     * the gesture. Swallowing events would have to start at ACTION_DOWN, before the direction is known,
     * and would take the edge strip away from the terminal for scrolling and text selection.
     *
     * The decision is made per event rather than once at startup, so enabling the assistant in Settings
     * takes effect on the next swipe; while it is disabled every event is inspected and ignored.
     */
    private fun installAssistantEdgeGesture() {
        val root = mTermuxActivityRootView ?: return

        val edgeWidth = dp(ASSISTANT_EDGE_GESTURE_WIDTH_DP)
        val threshold = dp(ASSISTANT_EDGE_GESTURE_THRESHOLD_DP).toFloat()

        root.touchObserver = object : TermuxActivityRootView.TouchObserver {
            override fun onTouch(event: MotionEvent, rootWidth: Int) {
                if (rootWidth <= 0) return

                // This runs for every touch event in the app, including every MOVE of every scroll, so
                // the expensive question ("is the assistant configured?") is asked once at the start of
                // a gesture rather than on each event.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    assistantGestureTracking =
                        event.x >= rootWidth - edgeWidth &&
                            aiPanelController?.isVisible() != true &&
                            com.termux.app.ai.AiConfig.get(this@TermuxActivity).isUsable()
                    assistantGestureStartX = event.x
                    return
                }

                if (!assistantGestureTracking) return

                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.x - assistantGestureStartX
                        if (com.termux.app.ai.EdgeSwipe.shouldOpen(delta, threshold)) {
                            // Opening on the crossing rather than on release makes the panel appear
                            // while the finger is still moving, which is what makes it feel deliberate.
                            assistantGestureTracking = false
                            toggleAssistantPanel()
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        assistantGestureTracking = false
                    }
                }
            }
        }

        syncGestureExclusion()
    }

    /**
     * Claims or releases the right screen edge, depending on whether the assistant is available.
     *
     * The exclusion is what lets a swipe be seen at all under gesture navigation, but claiming it means
     * the system Back gesture no longer starts there. That cost is only worth paying for a user who has
     * actually enabled the assistant; for everyone else the edge must stay with the system.
     *
     * Called on every resume as well, so enabling or disabling the assistant in Settings takes effect
     * without a restart.
     */
    private fun syncGestureExclusion() {
        val root = mTermuxActivityRootView ?: return
        val usable = com.termux.app.ai.AiConfig.get(this).isUsable()
        root.gestureExclusionWidth = if (usable) dp(ASSISTANT_EDGE_GESTURE_WIDTH_DP) else 0
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    val preferences: TermuxAppSharedPreferences?
        get() = mPreferences

    val properties: TermuxAppSharedProperties?
        get() = mProperties

    private fun registerTermuxActivityBroadcastReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)

        // Android 14+ (API 34+) requires specifying RECEIVER_NOT_EXPORTED for internal receivers
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter)
        }
    }

    private fun unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver)
    }

    private fun fixTermuxActivityBroadcastReceiverIntent(intent: Intent?) {
        if (intent == null) return

        val extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
        if ("storage" == extraReloadStyle) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
            intent.action = TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS
        }
    }

    inner class TermuxActivityBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) return

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent)

                when (intent.action) {
                    TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH -> {
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash")
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG)
                    }
                    TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true))
                    }
                    TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                        requestStoragePermission(false)
                    }
                }
            }
        }
    }

    private fun reloadActivityStyling(recreateActivity: Boolean) {
        if (mProperties != null) {
            reloadProperties()

            mExtraKeysView?.let { view ->
                view.setButtonTextAllCaps(mProperties?.shouldExtraKeysTextBeAllCaps() == true)
                view.reload(mTermuxTerminalExtraKeys?.getExtraKeysInfo(), mTerminalToolbarDefaultHeight)
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties?.getNightMode())
        }

        setMargins()
        setTerminalToolbarHeight()

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        mTermuxTerminalSessionActivityClient?.onReloadActivityStyling()
        mTermuxTerminalViewClient?.onReloadActivityStyling()

        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity")
            this@TermuxActivity.recreate()
        }
    }

    companion object {
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID = 4
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6
        private const val CONTEXT_MENU_HELP_ID = 7
        private const val CONTEXT_MENU_SETTINGS_ID = 8
        private const val CONTEXT_MENU_ASSISTANT_ID = 9

        /** Width of the right-edge strip that accepts the open gesture. */
        private const val ASSISTANT_EDGE_GESTURE_WIDTH_DP = 20

        /** Horizontal distance that counts as a deliberate swipe. */
        private const val ASSISTANT_EDGE_GESTURE_THRESHOLD_DP = 40

        private const val ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input"
        private const val ARG_ACTIVITY_RECREATED = "activity_recreated"

        /**
         * Height bounds for the text input page, in dp.
         *
         * The hard minimum can never be undercut: it must fit the 40dp send
         * button plus the container's vertical padding (6dp top + 6dp bottom).
         *
         * The maximum is a safety net only. The real height comes from measuring
         * the visible lines, which is capped at the EditText's own maxLines, so
         * reaching this value never leaves dead space below the input.
         */
        private const val TEXT_INPUT_HARD_MIN_HEIGHT_DP = 52f
        private const val TEXT_INPUT_MIN_HEIGHT_DP = 56f
        private const val TEXT_INPUT_MAX_HEIGHT_DP = 140f

        private const val LOG_TAG = "TermuxActivity"

        @JvmStatic
        fun updateTermuxActivityStyling(context: Context, recreateActivity: Boolean) {
            val stylingIntent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity)
            context.sendBroadcast(stylingIntent)
        }

        @JvmStatic
        fun startTermuxActivity(context: Context) {
            ActivityUtils.startActivity(context, newInstance(context))
        }

        @JvmStatic
        fun newInstance(context: Context): Intent {
            val intent = Intent(context, TermuxActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return intent
        }
    }
}
