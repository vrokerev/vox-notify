package com.victormeneses.yape_notifier.storage

import com.victormeneses.yape_notifier.notifications.AllowedPackages
import com.victormeneses.yape_notifier.notifications.AppReadMode
import com.victormeneses.yape_notifier.notifications.AppSelection

class AppSelectionRepository(private val store: KeyValueStore) {
    fun getAll(): List<AppSelection> {
        val stored = store.getString(KEY_APPS, "")
            .lineSequence()
            .mapNotNull { deserialize(it) }
            .associateBy { it.packageName }
        return stored
            .values
            .sortedWith(compareByDescending<AppSelection> { it.enabled }.thenBy { it.label.lowercase() })
    }

    fun getEnabledMap(): Map<String, AppSelection> =
        getAll().filter { it.enabled }.associateBy { it.packageName }

    fun upsert(selection: AppSelection) {
        val updated = (getAll().associateBy { it.packageName } + (selection.packageName to selection)).values.toList()
        save(updated)
    }

    fun update(packageName: String, enabled: Boolean, readMode: AppReadMode? = null, label: String? = null): AppSelection {
        val current = getAll().firstOrNull { it.packageName == packageName }
            ?: AppSelection(packageName, label ?: packageName, false, AppReadMode.TITLE_AND_CONTENT, true)
        val updated = current.copy(
            enabled = enabled,
            readMode = readMode ?: current.readMode,
            label = label ?: current.label,
        )
        upsert(updated)
        VoxNotifyEventBus.emit("app_selection_changed")
        return updated
    }

    fun registerDetected(packageName: String, label: String): DetectionChange {
        if (packageName == SELF_PACKAGE) {
            return DetectionChange(
                packageName = packageName,
                existed = getAll().any { it.packageName == packageName },
                previousDetected = false,
                finalDetected = false,
                changed = false,
            )
        }
        val current = getAll().firstOrNull { it.packageName == packageName }
        val updated = if (current == null) {
            AppSelection(packageName, label, false, AppReadMode.TITLE_AND_CONTENT, true)
        } else {
            current.copy(label = label, detected = true)
        }
        val changed = current == null || current.detected != updated.detected || current.label != updated.label
        if (changed) {
            upsert(updated)
            VoxNotifyEventBus.emit("app_detected")
        }
        return DetectionChange(
            packageName = packageName,
            existed = current != null,
            previousDetected = current?.detected,
            finalDetected = updated.detected,
            changed = changed,
        )
    }

    fun reconcileKnownYapeInstallation(installed: Boolean, label: String) {
        val current = getAll().firstOrNull { it.packageName == AllowedPackages.YAPE_PACKAGE }
        val updated = when {
            installed && current == null -> AppSelection(
                packageName = AllowedPackages.YAPE_PACKAGE,
                label = label,
                enabled = true,
                readMode = AppReadMode.SMART_YAPE,
                detected = false,
            )
            installed && current != null -> current.copy(
                label = label,
                enabled = true,
                readMode = AppReadMode.SMART_YAPE,
            )
            !installed && current != null && !current.detected -> current.copy(
                label = label,
                enabled = false,
            )
            else -> return
        }
        upsert(updated)
    }

    private fun save(apps: List<AppSelection>) {
        store.putString(KEY_APPS, apps.joinToString("\n") { serialize(it) })
    }

    private fun serialize(app: AppSelection): String =
        listOf(app.packageName, app.label, app.enabled.toString(), app.readMode.name, app.detected.toString())
            .joinToString("\t") { escape(it) }

    private fun deserialize(line: String): AppSelection? {
        val parts = splitEscaped(line)
        if (parts.size != 5) return null
        return AppSelection(
            packageName = parts[0],
            label = parts[1],
            enabled = parts[2].toBooleanStrictOrNull() ?: false,
            readMode = runCatching { AppReadMode.valueOf(parts[3]) }.getOrDefault(AppReadMode.TITLE_AND_CONTENT),
            detected = parts[4].toBooleanStrictOrNull() ?: true,
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun splitEscaped(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        for (char in value) {
            if (escaping) {
                current.append(when (char) {
                    't' -> '\t'
                    'n' -> '\n'
                    else -> char
                })
                escaping = false
            } else if (char == '\\') {
                escaping = true
            } else if (char == '\t') {
                result.add(current.toString())
                current.clear()
            } else {
                current.append(char)
            }
        }
        result.add(current.toString())
        return result
    }

    companion object {
        private const val KEY_APPS = "app_selections"
        private const val SELF_PACKAGE = "com.victormeneses.yape_notifier"
    }
}

data class DetectionChange(
    val packageName: String,
    val existed: Boolean,
    val previousDetected: Boolean?,
    val finalDetected: Boolean,
    val changed: Boolean,
)
