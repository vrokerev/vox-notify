package com.victormeneses.yape_notifier.notifications

import com.victormeneses.yape_notifier.BuildConfig

object AllowedPackages {
    const val YAPE_PACKAGE = "com.bcp.innovacxion.yapeapp"
    const val TEST_SENDER_PACKAGE = "com.victormeneses.yape_notifier.test_sender"

    fun current(): Set<String> =
        if (BuildConfig.ALLOW_TEST_SENDER) {
            setOf(YAPE_PACKAGE, TEST_SENDER_PACKAGE)
        } else {
            setOf(YAPE_PACKAGE)
        }
}
