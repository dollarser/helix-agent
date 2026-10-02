package com.helix.runtime.proot.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ProotGuestMediaPermissionsTest {
    @Test fun preparedCommandStaysExecutableByOwnerAndDropsOtherAccess() {
        val path = Files.createTempFile("helix-command-permissions-", ".sh")
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"))
            ProotGuestMedia.setCommandPermissions(path.toFile())
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
