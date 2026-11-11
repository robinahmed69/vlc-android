/*****************************************************************************
 * MainActivity.java
 *
 * Copyright © 2011-2019 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */

package org.videolan.vlc.gui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.ACTIVITY_RESULT_OPEN
import org.videolan.resources.ACTIVITY_RESULT_PREFERENCES
import org.videolan.resources.ACTIVITY_RESULT_SECONDARY
import org.videolan.resources.AndroidDevices
import org.videolan.resources.CRASH_HAPPENED
import org.videolan.resources.EXPORT_SETTINGS_FILE
import org.videolan.resources.EXTRA_TARGET
import org.videolan.tools.KEY_INCOGNITO
import org.videolan.tools.KEY_LAST_SESSION_CRASHED
import org.videolan.tools.KEY_MEDIALIBRARY_AUTO_RESCAN
import org.videolan.tools.KEY_OBSOLETE_RESTORE_FILE_WARNED
import org.videolan.tools.KEY_SHOW_UPDATE
import org.videolan.tools.PERMISSION_NEVER_ASK
import org.videolan.tools.PERMISSION_NEXT_ASK
import org.videolan.tools.RESULT_RESCAN
import org.videolan.tools.RESULT_RESTART
import org.videolan.tools.RESULT_RESTART_APP
import org.videolan.tools.RESULT_UPDATE_ARTISTS
import org.videolan.tools.RESULT_UPDATE_SEEN_MEDIA
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.StartActivity
import org.videolan.vlc.gui.audio.AudioBrowserFragment
import org.videolan.vlc.gui.dialogs.NotificationPermissionManager
import org.videolan.vlc.gui.dialogs.PermissionListDialog
import org.videolan.vlc.gui.dialogs.UPDATE_DATE
import org.videolan.vlc.gui.dialogs.UPDATE_URL
import org.videolan.vlc.gui.dialogs.UpdateDialog
import org.videolan.vlc.gui.helpers.INavigator
import org.videolan.vlc.gui.helpers.Navigator
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.UiTools.isTablet
import org.videolan.vlc.gui.preferences.PreferencesActivity
import org.videolan.vlc.gui.preferences.search.PreferenceParser
import org.videolan.vlc.gui.video.VideoGridFragment
import org.videolan.vlc.interfaces.Filterable
import org.videolan.vlc.interfaces.IRefreshable
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.reloadLibrary
import org.videolan.vlc.util.AutoUpdate
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.util.Util
import org.videolan.vlc.util.WhatsNewManager
import org.videolan.vlc.util.WidgetMigration
import org.videolan.vlc.util.getScreenWidth
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "VLC/MainActivity"

// Sliding pill speed in milliseconds. Bigger number = slower slide (try 500 to 900).
private const val SLIDE_DURATION_MS = 650L

class MainActivity : ContentActivity(),
        INavigator by Navigator()
{
    private lateinit var backPressedCallback: OnBackPressedCallback
    var refreshing: Boolean = false
    private lateinit var mediaLibrary: Medialibrary
    private var scanNeeded = false
    private lateinit var toolbarIcon: ImageView

    // Sliding pill state
    private var navPillSelectedId = -1
    private var navPillTargetX = Float.NaN

    override fun getSnackAnchorView(overAudioPlayer:Boolean): View? {
        val view = super.getSnackAnchorView(overAudioPlayer)
        return if (view?.id == android.R.id.content && !isTablet()) {if(overAudioPlayer) findViewById(android.R.id.content) else findViewById(R.id.appbar)} else view
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Util.checkCpuCompatibility(this)
        /*** Start initializing the UI  */
        setContentView(R.layout.main)
        initAudioPlayerContainerActivity()
        setupNavigation(savedInstanceState)
        setupNavAnimation()
        setupNavPill()
        setupStatusBarScrim()

        /* Set up the action bar */
        prepareActionBar()
        /* Reload the latest preferences */
        scanNeeded = savedInstanceState == null && settings.getBoolean(KEY_MEDIALIBRARY_AUTO_RESCAN, true)
        mediaLibrary = Medialibrary.getInstance()

        if (!NotificationPermissionManager.launchIfNeeded(this)) {
            if (!WidgetMigration.launchIfNeeded(this)) {
               if (!Settings.firstRun)  WhatsNewManager.launchIfNeeded(this) else WhatsNewManager.markAsShown(settings)
            }
        }

        lifecycleScope.launch {
            if (!BuildConfig.DEBUG) return@launch
            AutoUpdate.clean(this@MainActivity.application)
            if (!settings.getBoolean(KEY_SHOW_UPDATE, true)) return@launch
            if (!settings.contains(KEY_SHOW_UPDATE)) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(resources.getString(R.string.update_nightly))
                    .setMessage(resources.getString(R.string.update_nightly_alert))
                    .setPositiveButton(R.string.yes){ _, _ ->
                        settings.putSingle(KEY_SHOW_UPDATE, true)
                    }
                    .setNegativeButton(R.string.no){ _, _ ->
                        settings.putSingle(KEY_SHOW_UPDATE, false)
                    }
                    .show()
                return@launch
            }
            AutoUpdate.checkUpdate(this@MainActivity.application) {url, date ->
                val updateDialog = UpdateDialog().apply {
                    arguments = bundleOf(UPDATE_URL to url, UPDATE_DATE to date.time)
                }
                updateDialog.show(supportFragmentManager, "fragment_update")
            }
        }
        if (settings.getBoolean(KEY_LAST_SESSION_CRASHED, false)) {
            settings.putSingle(KEY_LAST_SESSION_CRASHED, false)
            if (BuildConfig.BETA) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(resources.getString(R.string.report_crash))
                    .setMessage(resources.getString(R.string.serious_crash))
                    .setPositiveButton(R.string.send_log) { _, _ ->
                        startActivity(
                            Intent(applicationContext, FeedbackActivity::class.java)
                                .apply {
                                    putExtra(CRASH_HAPPENED, true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                        )
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->

                    }
                    .show()

            }
        }
        if (!settings.getBoolean(KEY_OBSOLETE_RESTORE_FILE_WARNED, false)) {
            lifecycleScope.launch {
                val file = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + EXPORT_SETTINGS_FILE)
                val fileExists = withContext(Dispatchers.IO) {
                    file.exists()
                }
                if (!fileExists) return@launch
                //check if file is restorable
                try {
                    PreferenceParser.checkRestoreFile(file.path.toUri())
                } catch (_: Exception) {
                    UiTools.snackerConfirm(this@MainActivity, getString(R.string.obsolete_restore_settings), confirmMessage = R.string.ok, indefinite = true) {
                        lifecycleScope.launch {
                            PreferencesActivity.launchWithPref(this@MainActivity, "export_settings")
                        }
                    }
                }
            }
        }
        settings.putSingle(KEY_OBSOLETE_RESTORE_FILE_WARNED, true)
        backPressedCallback = onBackPressedDispatcher.addCallback(enabled = true) {
            if (AndroidUtil.isNougatOrLater && isInMultiWindowMode) {
                UiTools.confirmExit(this@MainActivity)
                return@addCallback
            }
        }
        backPressedCallback.isEnabled = AndroidUtil.isNougatOrLater && isInMultiWindowMode
    }


    override fun onResume() {
        super.onResume()
        //Only the partial permission is granted for Android 11+
        if (!settings.getBoolean(PERMISSION_NEVER_ASK, false) && settings.getLong(PERMISSION_NEXT_ASK, 0L) < System.currentTimeMillis() && Permissions.canReadStorage(this) && !Permissions.hasAllAccess(this)) {
            UiTools.snackerMessageInfinite(this, getString(R.string.partial_content))?.setAction(R.string.more) {
                PermissionListDialog.newInstance().show(supportFragmentManager, PermissionListDialog::class.simpleName)
            }?.show()
            settings.putSingle(PERMISSION_NEXT_ASK, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2))
        }
        updateIncognitoModeIcon()
        configurationChanged(getScreenWidth())
    }

    /**
     * Immersive mode: hide the system navigation buttons.
     * They appear temporarily when swiping from the bottom edge.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.FINE_STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                forceRefresh()
            }
        }
    }


    private fun prepareActionBar() {
        toolbarIcon = findViewById(R.id.toolbar_icon)
        updateIncognitoModeIcon()
        supportActionBar?.run {
            setDisplayHomeAsUpEnabled(false)
            setHomeButtonEnabled(false)
            setDisplayShowTitleEnabled(false)
        }
    }

    /**
     * Bounce animation on the bottom navigation items when they are tapped
     */
    private fun setupNavAnimation() {
        val nav = findViewById<BottomNavigationView>(R.id.navigation) ?: return
        val navMenu = nav.menu
        for (i in 0 until navMenu.size()) {
            nav.findViewById<View>(navMenu.getItem(i).itemId)?.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) bounceNavItem(v)
                false
            }
        }
    }

    private fun bounceNavItem(v: View) {
        v.animate().cancel()
        v.scaleX = 0.8f
        v.scaleY = 0.8f
        v.animate().scaleX(1f).scaleY(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator(3.5f)).start()
    }

    /**
     * Status bar that matches the toolbar colour (red in light mode, dark in dark mode).
     * On newer Android versions the status bar is transparent and android:statusBarColor
     * is ignored, so a coloured view is drawn on top of the status bar area of the window.
     * The colour is read from the theme (background_actionbar), so it follows light/dark mode.
     */
    private fun setupStatusBarScrim() {
        val decor = window.decorView as? ViewGroup ?: return

        val tv = android.util.TypedValue()
        val attrId = resources.getIdentifier("background_actionbar", "attr", packageName)
        val color = if (attrId != 0 && theme.resolveAttribute(attrId, tv, true) &&
            tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            tv.data
        } else {
            ContextCompat.getColor(this, R.color.wave_red)
        }

        fun statusBarHeight(): Int {
            val inset = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            if (inset > 0) return inset
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        }

        val scrim = View(this).apply {
            setBackgroundColor(color)
            isClickable = false
            isFocusable = false
        }
        decor.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight(), Gravity.TOP))

        // light icons on a dark bar, dark icons on a light bar
        val darkBar = androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.5
        WindowInsetsControllerCompat(window, decor).isAppearanceLightStatusBars = !darkBar

        decor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val h = statusBarHeight()
            if (scrim.layoutParams.height != h) {
                scrim.layoutParams.height = h
                scrim.requestLayout()
            }
            val visible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
            scrim.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * Sliding pill: the grey highlight behind the selected icon glides from one
     * button to the next instead of jumping.
     * The built-in Material indicator is turned off and replaced by our own view,
     * which is placed under the icons but over the bar background.
     */
    private fun setupNavPill() {
        val nav = findViewById<BottomNavigationView>(R.id.navigation) ?: return
        // Turn off every other highlight source so only our sliding pill is visible
        nav.isItemActiveIndicatorEnabled = false
        nav.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        nav.itemBackground = null

        val density = resources.displayMetrics.density
        val pillW = (64 * density).toInt()
        val pillH = (48 * density).toInt()
        val pill = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(pillW, pillH)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = pillH / 2f
                setColor(ContextCompat.getColor(this@MainActivity, R.color.grey300))
            }
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
        }
        // index 0 = behind the menu view (icons), above the bar background
        nav.addView(pill, 0)

        nav.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                updateNavPill(nav, pill, pillW, pillH)
                return true
            }
        })
    }

    private fun updateNavPill(nav: BottomNavigationView, pill: View, pillW: Int, pillH: Int) {
        val id = nav.selectedItemId
        val item = nav.findViewById<View>(id) ?: return
        if (item.width == 0 || item.height == 0) return

        // position of the item inside the nav bar
        var x = 0f
        var y = 0f
        var cur: View = item
        while (cur !== nav) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            cur = cur.parent as? View ?: break
        }
        val targetX = x + (item.width - pillW) / 2f
        val targetY = y + (item.height - pillH) / 2f

        when {
            // first layout: place the pill without animation
            navPillSelectedId == -1 -> {
                pill.translationX = targetX
                pill.translationY = targetY
                pill.visibility = View.VISIBLE
            }
            // another tab selected: slide
            id != navPillSelectedId -> {
                pill.translationY = targetY
                slidePill(pill, targetX)
            }
            // same tab but layout moved (rotation, resize): follow without animation
            Math.abs(targetX - navPillTargetX) > 1f -> {
                cancelPillSlide(pill)
                pill.translationX = targetX
                pill.translationY = targetY
            }
        }
        navPillSelectedId = id
        navPillTargetX = targetX
    }

    private var pillSlideRunnable: Runnable? = null

    private fun cancelPillSlide(pill: View) {
        pillSlideRunnable?.let { pill.removeCallbacks(it) }
        pillSlideRunnable = null
    }

    /**
     * Slides the pill to [toX] over SLIDE_DURATION_MS.
     * It is driven frame by frame (not with the Android animator system), so it still
     * runs when the phone has animations turned off (Developer options / battery saver).
     */
    private fun slidePill(pill: View, toX: Float) {
        cancelPillSlide(pill)
        val fromX = pill.translationX
        val start = android.os.SystemClock.uptimeMillis()
        val interpolator = AccelerateDecelerateInterpolator()
        val step = object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / SLIDE_DURATION_MS).coerceIn(0f, 1f)
                pill.translationX = fromX + (toX - fromX) * interpolator.getInterpolation(t)
                if (t < 1f) pill.postOnAnimation(this) else pillSlideRunnable = null
            }
        }
        pillSlideRunnable = step
        pill.postOnAnimation(step)
    }

    override fun onStart() {
        super.onStart()
        if (mediaLibrary.isInitiated) {
            /* Load media items from database and storage */
            if (scanNeeded && Permissions.canReadStorage(this) && !mediaLibrary.isWorking) this.reloadLibrary()
        }
    }

    override fun onStop() {
        super.onStop()
        if (changingConfigurations == 0) {
            /* Check for an ongoing scan that needs to be resumed during onResume */
            scanNeeded = mediaLibrary.isWorking
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val current = currentFragment
        supportFragmentManager.putFragment(outState, "current_fragment", current!!)
        outState.putInt(EXTRA_TARGET, currentFragmentId)
        super.onSaveInstanceState(outState)
    }

    override fun onRestart() {
        super.onRestart()
        /* Reload the latest preferences */
        reloadPreferences()
    }

    override fun startSupportActionMode(callback: ActionMode.Callback): ActionMode? {
        appBarLayout.setExpanded(true)
        return super.startSupportActionMode(callback)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.ml_menu_refresh)?.isVisible = Permissions.canReadStorage(this)
        menu?.findItem(R.id.incognito_mode)?.isChecked = Settings.getInstance(this).getBoolean(KEY_INCOGNITO, false)
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Handle onClick form menu buttons
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.ml_menu_filter) UiTools.setKeyboardVisibility(appBarLayout, false)

        // Handle item selection
        return when (item.itemId) {
            // Refresh
            R.id.ml_menu_refresh -> {
                if (Permissions.canReadStorage(this)) forceRefresh()
                true
            }
            R.id.incognito_mode -> {
                lifecycleScope.launch {
                    if (!UiTools.updateIncognitoMode(this@MainActivity, item)) return@launch
                    updateIncognitoModeIcon()
                }
                true
            }
            android.R.id.home ->
                // Slide down the audio player or toggle the sidebar
                slideDownAudioPlayer()
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateIncognitoModeIcon() {
        val incognito = Settings.getInstance (this).getBoolean(KEY_INCOGNITO, false)
        // Wave icon replaces the VLC cone; incognito icon is shown only in incognito mode
        toolbarIcon.setImageDrawable(ContextCompat.getDrawable(this, if (incognito) R.drawable.ic_incognito else R.drawable.ic_wave))
        toolbarIcon.visibility = View.VISIBLE
    }

    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        return if (currentFragment is Filterable) {
            (currentFragment as Filterable).allowedToExpand()
        } else false
    }

    fun forceRefresh() {
        forceRefresh(currentFragment)
    }

    private fun forceRefresh(current: Fragment?) {
        if (!mediaLibrary.isWorking) {
            if (current != null && current is IRefreshable)
                (current as IRefreshable).refresh()
            else
                reloadLibrary()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
//        if (VLCBilling.getInstance(this.application).iabHelper.handleActivityResult(requestCode, resultCode, data)) return
        if (requestCode == ACTIVITY_RESULT_PREFERENCES) {
            when (resultCode) {
                RESULT_RESCAN -> this.reloadLibrary()
                RESULT_RESTART, RESULT_RESTART_APP -> {
                    val intent = Intent(this@MainActivity, if (resultCode == RESULT_RESTART_APP) StartActivity::class.java else MainActivity::class.java)
                    finish()
                    startActivity(intent)
                }
                RESULT_UPDATE_SEEN_MEDIA -> for (fragment in supportFragmentManager.fragments)
                    if (fragment is VideoGridFragment)
                        fragment.updateSeenMediaMarker()
                RESULT_UPDATE_ARTISTS -> {
                    val fragment = currentFragment
                    if (fragment is AudioBrowserFragment) fragment.viewModel.refresh()
                }
            }
        } else if (requestCode == ACTIVITY_RESULT_OPEN && resultCode == RESULT_OK) {
            MediaUtils.openUri(this, data!!.data)
        } else if (requestCode == ACTIVITY_RESULT_SECONDARY) {
            if (resultCode == RESULT_RESCAN) {
                forceRefresh(currentFragment)
            } else {
                scanNeeded = false
            }
        }
    }

    // Note. onKeyDown will not occur while moving within a list
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            toolbar.menu.findItem(R.id.ml_menu_filter).expandActionView()
        }
        return super.onKeyDown(keyCode, event)
    }
}
