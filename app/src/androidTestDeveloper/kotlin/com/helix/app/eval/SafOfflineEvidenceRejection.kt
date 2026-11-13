package com.helix.app.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/** Actual captured evidence must fail acceptance when original-call delivery identity is removed. */
internal fun verifyOfflineEvidenceRejection(directory: File) {
    val requests = directory.listFiles().orEmpty().filter { it.name.matches(Regex("request-\\d+\\.json")) }
    val originals = requests.associateWith { it.readText() }
    check(originals.values.any { "Delivery facts:" in it })
    try {
        originals.forEach { (file, text) ->
            file.writeText(text.replace("Delivery facts:", "Removed delivery identity:"))
        }
        assertFalse(verifyFileLocationEvidence(directory))
    } finally {
        originals.forEach { (file, text) -> file.writeText(text) }
    }
    assertTrue(verifyFileLocationEvidence(directory))
    File(
        directory,
        "negative-evidence-check.txt",
    ).writeText("Missing delivery identity rejected; original captures restored and accepted.\n")
}
