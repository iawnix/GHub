package dev.remotegpt.app.ui

import dev.remotegpt.client.RemoteFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesScreenTest {
    @Test fun commonDotfilesAndExtensionlessBuildFilesAreRecognizedAsText() {
        for (name in listOf("Dockerfile", "Dockerfile.prod", "Makefile", "CMakeLists.txt", ".gitignore", ".env", ".env.local", "Cargo.lock")) {
            assertTrue("Expected $name to open directly as text", textFileName(name))
        }
    }

    @Test fun editableTextCandidateAllowsSmallUnknownFilesAndRejectsBinariesOrDirectories() {
        assertTrue(editableTextCandidate(RemoteFile("run-script", "/srv/run-script", directory = false, size = 4096, modified = 0L)))
        assertTrue(editableTextCandidate(RemoteFile(".customrc", "/srv/.customrc", directory = false, size = 512, modified = 0L)))
        assertFalse(editableTextCandidate(RemoteFile("src", "/srv/src", directory = true, size = 0, modified = 0L)))
        assertFalse(editableTextCandidate(RemoteFile("bundle.tar.gz", "/srv/bundle.tar.gz", directory = false, size = 1024, modified = 0L)))
        assertFalse(editableTextCandidate(RemoteFile("photo.png", "/srv/photo.png", directory = false, size = 1024, modified = 0L)))
        assertFalse(editableTextCandidate(RemoteFile("huge.log", "/srv/huge.log", directory = false, size = 2_000_000, modified = 0L)))
    }
}
