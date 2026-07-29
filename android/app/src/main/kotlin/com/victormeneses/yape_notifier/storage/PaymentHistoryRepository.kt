package com.victormeneses.yape_notifier.storage

import com.victormeneses.yape_notifier.notifications.PaymentRecord

class PaymentHistoryRepository(private val store: KeyValueStore, private val maxItems: Int = 100) {
    fun add(record: PaymentRecord) {
        val updated = (listOf(record) + get()).take(maxItems)
        store.putString(KEY_HISTORY, serialize(updated))
    }

    fun get(): List<PaymentRecord> =
        store.getString(KEY_HISTORY, "")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { deserializeRecord(it) }
            .toList()

    fun clear() {
        store.putString(KEY_HISTORY, "")
    }

    private fun serialize(records: List<PaymentRecord>): String =
        records.joinToString("\n") {
            listOf(
                it.timestamp.toString(),
                it.amount,
                it.sender.orEmpty(),
                it.spokenText,
                it.source,
                it.announced.toString(),
            ).joinToString("\t") { value ->
                escape(value)
            }
        }

    private fun deserializeRecord(line: String): PaymentRecord? {
        val parts = splitEscaped(line)
        return when (parts.size) {
            4 -> PaymentRecord(
                timestamp = parts[0].toLongOrNull() ?: return null,
                amount = parts[1],
                sender = null,
                spokenText = parts[2],
                source = parts[3],
                announced = true,
            )
            6 -> PaymentRecord(
                timestamp = parts[0].toLongOrNull() ?: return null,
                amount = parts[1],
                sender = parts[2].ifBlank { null },
                spokenText = parts[3],
                source = parts[4],
                announced = parts[5].toBooleanStrictOrNull() ?: true,
            )
            else -> null
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun splitEscaped(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        for (char in value) {
            if (escaping) {
                current.append(
                    when (char) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> char
                    },
                )
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
        private const val KEY_HISTORY = "payment_history"
    }
}
