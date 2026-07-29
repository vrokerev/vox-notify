package com.victormeneses.yape_notifier.nativebridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.victormeneses.yape_notifier.notifications.AppReadMode
import com.victormeneses.yape_notifier.notifications.AppSelection
import com.victormeneses.yape_notifier.storage.AppSelectionRepository

object AppLabelResolver {
    fun labelFor(context: Context, packageName: String): String =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    fun visibleLauncherApps(context: Context, repository: AppSelectionRepository): List<AppSelection> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherApps = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map {
                AppSelection(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(context.packageManager).toString(),
                    enabled = false,
                    readMode = AppReadMode.TITLE_AND_CONTENT,
                    detected = false,
                )
            }
        val known = repository.getAll().associateBy { it.packageName }
        return (launcherApps.associateBy { it.packageName } + known)
            .values
            .sortedWith(compareByDescending<AppSelection> { it.enabled }.thenBy { it.label.lowercase() })
    }
}
