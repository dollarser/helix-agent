package com.helix.app.companions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeApkPolicyTest {
    @Test fun matchingPackageAndSignersAreAccepted() {
        assertTrue(RuntimeApkPolicy.accepts("runtime", "runtime", setOf("a", "b"), setOf("b", "a")))
    }

    @Test fun wrongPackageIsRejectedEvenWithMatchingSigner() {
        assertFalse(RuntimeApkPolicy.accepts("runtime", "other", setOf("a"), setOf("a")))
    }

    @Test fun unsignedApksAreRejected() {
        assertFalse(RuntimeApkPolicy.accepts("runtime", "runtime", emptySet(), emptySet()))
        assertFalse(RuntimeApkPolicy.accepts("runtime", "runtime", setOf("a"), emptySet()))
    }

    @Test fun differentOrPartialSignerSetsAreRejected() {
        assertFalse(RuntimeApkPolicy.accepts("runtime", "runtime", setOf("a"), setOf("b")))
        assertFalse(RuntimeApkPolicy.accepts("runtime", "runtime", setOf("a", "b"), setOf("a")))
    }
}
