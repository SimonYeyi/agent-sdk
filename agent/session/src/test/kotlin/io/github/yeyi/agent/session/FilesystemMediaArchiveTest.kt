package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.MediaSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilesystemMediaArchiveTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("archive-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `store and resolve round-trips base64 bytes`() = runTest {
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val data = MediaSource.Data("image/jpeg", "SGVsbG8=")  // "Hello" in base64
        val local = archive.store(data)

        assertEquals("image/jpeg", local.mimeType)
        val resolved = archive.resolve(local)
        assertEquals("SGVsbG8=", resolved.base64)
        assertEquals("image/jpeg", resolved.mimeType)
    }

    @Test
    fun `store generates unique fileId for each call`() = runTest {
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val data = MediaSource.Data("image/jpeg", "SAME")

        val local1 = archive.store(data)
        val local2 = archive.store(data)

        assertTrue(local1.fileId != local2.fileId, "each store should produce unique UUID")
    }

    @Test
    fun `resolve throws IllegalStateException for missing fileId`() = runTest {
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val ghost = MediaSource.Local("ghost-id-not-on-disk", "image/jpeg")

        val ex = assertFailsWith<IllegalStateException> {
            archive.resolve(ghost)
        }
        assertTrue(ex.message!!.contains("ghost-id-not-on-disk"))
    }

    @Test
    fun `init creates rootDir if not exists`() {
        val root = File(tempDir, "nested/media")
        assertTrue(!root.exists())

        FilesystemMediaArchive(root)

        assertTrue(root.exists())
        assertTrue(root.isDirectory)
    }

    @Test
    fun `large base64 round-trips correctly`() = runTest {
        // 用 ~10KB base64 (7680B 原始字节) 验证 base64 解码路径不走捷径
        val big = "A".repeat(10_240)
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val data = MediaSource.Data("image/png", big)
        val local = archive.store(data)

        val resolved = archive.resolve(local)
        assertEquals(big, resolved.base64)
    }
}
