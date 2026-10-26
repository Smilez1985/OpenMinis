package com.openminis.app.appfunctions

import android.content.Context
import com.openminis.app.tools.AgentToolSwitch

/**
 * [T-appfunctions-caller] Sub-setting of the app_functions tool switch:
 * ask before every call that is not read-only. Off by default (D6). Stored
 * next to the tool switches so a settings sync carries both.
 */
object AppFunctionsSettings {
    const val CONFIRM_WRITES_KEY = "agent.tools.appfunctions.confirmWrites"
    const val CONFIRM_WRITES_DEFAULT = false

    fun confirmWrites(context: Context): Boolean =
        AgentToolSwitch.prefs(context).getBoolean(CONFIRM_WRITES_KEY, CONFIRM_WRITES_DEFAULT)

    fun setConfirmWrites(context: Context, enabled: Boolean) {
        AgentToolSwitch.prefs(context).edit().putBoolean(CONFIRM_WRITES_KEY, enabled).apply()
    }
}
