package com.victormeneses.yape_notifier.nativebridge

import android.content.Context
import android.content.pm.PackageManager
import com.victormeneses.yape_notifier.notifications.AllowedPackages
import com.victormeneses.yape_notifier.notifications.AppReadMode
import com.victormeneses.yape_notifier.notifications.AppSelection
import com.victormeneses.yape_notifier.storage.AppSelectionRepository

object AppLabelResolver {
    fun labelFor(context: Context, packageName: String): String =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        }.getOrDefault(packageName)

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun visibleLauncherApps(context: Context, repository: AppSelectionRepository): List<AppSelection> {
        val installedApps = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .map {
                val label = context.packageManager.getApplicationLabel(it).toString().ifBlank { it.packageName }
                AppSelection(
                    packageName = it.packageName,
                    label = label,
                    enabled = false,
                    readMode = AppReadMode.TITLE_AND_CONTENT,
                    detected = false,
                    installed = true,
                    userProfile = "user 0",
                )
            }
        val known = repository.getAll().associateBy { it.packageName }
        val installedByPackage = installedApps.associateBy { it.packageName }
        val merged = (installedByPackage + known.mapValues { (packageName, selection) ->
            val installed = installedByPackage[packageName]
            if (installed != null) {
                selection.copy(
                    label = labelForKnownPackage(packageName, installed.label),
                    installed = true,
                    userProfile = installed.userProfile,
                )
            } else {
                selection.copy(
                    enabled = selection.enabled && selection.detected,
                    installed = false,
                    userProfile = "perfil actual no confirmado",
                )
            }
        }).toMutableMap()

        if (!merged.containsKey(AllowedPackages.YAPE_PACKAGE)) {
            merged[AllowedPackages.YAPE_PACKAGE] = AppSelection(
                packageName = AllowedPackages.YAPE_PACKAGE,
                label = "Yape no encontrada",
                enabled = false,
                readMode = AppReadMode.SMART_YAPE,
                detected = false,
                installed = false,
                userProfile = "perfil actual no confirmado",
            )
        }

        return merged
            .values
            .sortedWith(compareByDescending<AppSelection> { it.enabled }.thenBy { it.label.lowercase() })
    }

    private fun labelForKnownPackage(packageName: String, label: String): String =
        if (packageName == AllowedPackages.YAPE_PACKAGE && label == packageName) "Yape" else label
}
