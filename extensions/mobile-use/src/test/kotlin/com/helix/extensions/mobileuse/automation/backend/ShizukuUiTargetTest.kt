package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShizukuUiTargetTest {
    private val selector = ShizukuUiSelector("com.example.installer", "android:id/button1", "更新")

    @Test
    fun localizedUniqueTargetUsesObservedBounds() {
        val target = ShizukuUiTarget.resolve(hierarchy(node()), selector)
        assertEquals(150, target.x)
        assertEquals(350, target.y)
        assertEquals("0", target.rotation)
    }

    @Test
    fun sameIdInDifferentPackageOrWithDifferentTextIsNotATarget() {
        assertRejected("TARGET_NOT_FOUND", node().replace("com.example.installer", "com.other.app"))
        assertRejected("TARGET_NOT_FOUND", node().replace("更新", "Open"))
    }

    @Test
    fun duplicatedSelectorIsRejectedInsteadOfChoosingFirst() {
        assertRejected("TARGET_AMBIGUOUS", node() + node())
    }

    @Test
    fun disabledOrUnclickableTargetIsRejected() {
        assertRejected("TARGET_NOT_ACTIONABLE", node().replace("enabled=\"true\"", "enabled=\"false\""))
        assertRejected("TARGET_NOT_ACTIONABLE", node().replace("clickable=\"true\"", "clickable=\"false\""))
    }

    @Test
    fun passwordTargetIsRejected() {
        assertRejected("SENSITIVE_TARGET", node().replace("password=\"false\"", "password=\"true\""))
    }

    @Test
    fun invalidOrOversizedBoundsAreRejected() {
        for (bounds in listOf("[-1,1][2,2]", "[100,300][100,400]", "[100,300][99999,400]")) {
            assertThrows(IllegalArgumentException::class.java) {
                ShizukuUiTarget.resolve(hierarchy(node().replace("[100,300][200,400]", bounds)), selector)
            }
        }
    }

    @Test
    fun dtdAndOversizedHierarchyAreRejectedBeforeParsing() {
        for (xml in listOf(
            "<!DOCTYPE hierarchy SYSTEM 'file:///etc/passwd'>" + hierarchy(node()),
            " ".repeat(ShizukuUiTarget.MAX_XML_CHARS + 1),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ShizukuUiTarget.resolve(xml, selector) }
        }
    }

    @Test
    fun invalidSelectorDoesNotReachCommands() {
        assertThrows(IllegalArgumentException::class.java) {
            ShizukuUiSelector("com.example;id", "android:id/button1", "Update")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShizukuUiSelector("com.example", "", "Update")
        }
    }

    private fun assertRejected(
        message: String,
        nodes: String,
    ) {
        val error =
            assertThrows(IllegalStateException::class.java) { ShizukuUiTarget.resolve(hierarchy(nodes), selector) }
        assertEquals(message, error.message)
    }
}

private fun hierarchy(nodes: String) = "<hierarchy rotation=\"0\">$nodes</hierarchy>"

private fun node() =
    "<node package=\"com.example.installer\" resource-id=\"android:id/button1\" text=\"更新\" " +
        "enabled=\"true\" clickable=\"true\" password=\"false\" bounds=\"[100,300][200,400]\"/>"
