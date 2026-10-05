package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider

private val evaluations = mutableMapOf<String, MobileUseEvaluationSelection>()

internal fun selectMobileUseForTest(
    session: String,
    packages: Set<String>,
    wholePhone: Boolean,
): Boolean =
    evaluations
        .getOrPut(session) { MobileUseEvaluationSelection(ApplicationProvider.getApplicationContext()) }
        .select(session, packages, wholePhone)

internal fun deselectMobileUseForTest(session: String) {
    evaluations.remove(session)?.close()
}
