package dev.remotegpt.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RemoteFileOrderTest {
    private fun dir(name: String) = RemoteFile(name, "/p/$name", true, 0, 0)
    private fun file(name: String) = RemoteFile(name, "/p/$name", false, 1, 0)

    @Test fun foldersThenFilesWithHiddenEntriesLastInEachGroup() {
        val sorted = listOf(file(".env"), dir(".git"), file("README.md"), dir("docs"), dir("Src"), file("a.py"))
            .sortedWith(remoteFileOrder).map { it.name }
        assertEquals(listOf("docs", "Src", ".git", "a.py", "README.md", ".env"), sorted)
    }
}
