package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R

/** Explicit, single-package operations. Never guesses which vendor packages are unnecessary. */
internal class AppCleanupDialog(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences("app_cleanup", 0)
    private fun text(id: Int) = activity.getString(id)
    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    fun show() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = activity.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != activity.packageName }
        val saved = preferences.getStringSet("disabled", emptySet()).orEmpty().toSet()
        val names = (apps.map { it.packageName } + saved).distinct().sorted()
        val labels = names.map { name ->
            val info = apps.firstOrNull { it.packageName == name }
            val label = info?.let { activity.packageManager.getApplicationLabel(it).toString() } ?: name
            "$label\n$name${if (name in saved) " · ${text(R.string.cleanup_restore)}" else ""}"
        }
        AlertDialog.Builder(activity).setTitle(R.string.cleanup_title)
            .setItems(labels.toTypedArray()) { _, index -> actions(names[index], apps.firstOrNull { it.packageName == names[index] }) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun actions(name: String, info: ApplicationInfo?) {
        val disabledByUs = name in preferences.getStringSet("disabled", emptySet()).orEmpty()
        val isProtected = info == null || info.uid % 100000 < 10000 ||
            info.flags and ApplicationInfo.FLAG_PERSISTENT != 0 ||
            name == "com.android.settings" || name == activity.packageName ||
            name == activity.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName ||
            activity.getSystemService(InputMethodManager::class.java).inputMethodList.any { it.packageName == name }
        val options = mutableListOf(text(R.string.cleanup_details))
        if (disabledByUs) options.add(text(R.string.cleanup_restore))
        else if (!isProtected) options.add(text(R.string.cleanup_disable))
        if (!isProtected && info != null && info.flags and ApplicationInfo.FLAG_SYSTEM == 0) options.add(text(R.string.cleanup_uninstall))
        AlertDialog.Builder(activity).setTitle(name).setItems(options.toTypedArray()) { _, index ->
            when (options[index]) {
                text(R.string.cleanup_details) -> launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$name")))
                text(R.string.cleanup_uninstall) -> launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$name")))
                else -> confirm(name, disabledByUs)
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun launch(intent: Intent) {
        runCatching { activity.startActivity(intent) }.onFailure { error(it.message ?: text(R.string.cleanup_failed)) }
    }

    private fun confirm(name: String, restore: Boolean) {
        AlertDialog.Builder(activity).setTitle(if (restore) R.string.cleanup_restore else R.string.cleanup_disable)
            .setMessage(activity.getString(R.string.cleanup_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> change(name, restore) }.show()
    }

    private fun change(name: String, restore: Boolean) {
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(name)) return
        val progress = AlertDialog.Builder(activity).setMessage(R.string.cleanup_working).setCancelable(false).show()
        Thread({
            val result = runCatching {
                LocalAdb(AdbKeys.load(activity.applicationContext)).use { adb ->
                    check(adb.connect(mayAsk = true) == LocalAdb.Access.READY) { text(R.string.cleanup_adb_required) }
                    val user = adb.shell("am get-current-user")?.trim()
                    check(user != null && user.matches(Regex("[0-9]+"))) { text(R.string.cleanup_failed) }
                    // Record before the command so a lost reply still leaves a restoration entry.
                    if (!restore) {
                        val saved = preferences.getStringSet("disabled", emptySet()).orEmpty().toMutableSet()
                        saved.add(name)
                        check(preferences.edit().putStringSet("disabled", saved).commit())
                    }
                    val output = adb.shell("pm ${if (restore) "default-state" else "disable-user"} --user $user $name")
                    val state = if (restore) "default" else "disabled-user"
                    check(output?.lineSequence()?.any { it.trim() == "Package $name new state: $state" } == true) {
                        output ?: text(R.string.cleanup_failed)
                    }
                    if (restore) {
                        val saved = preferences.getStringSet("disabled", emptySet()).orEmpty().toMutableSet()
                        saved.remove(name)
                        preferences.edit().putStringSet("disabled", saved).commit()
                    }
                }
            }
            activity.runOnUiThread {
                if (alive()) {
                    progress.dismiss()
                    result.fold({ show() }, { error(it.message ?: text(R.string.cleanup_failed)) })
                }
            }
        }, "diplay-app-cleanup").start()
    }

    private fun error(message: String) {
        if (alive()) AlertDialog.Builder(activity).setTitle(R.string.cleanup_failed).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
