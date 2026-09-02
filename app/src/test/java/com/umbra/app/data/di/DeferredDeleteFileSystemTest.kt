package com.umbra.app.data.di

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredDeleteFileSystemTest {

    @Test
    fun `given a file when deleting then it is removed asynchronously without blocking the caller`() = runTest {
        val tempFile = File.createTempFile("deferred-delete-test", ".tmp").apply { writeText("x") }
        val path = tempFile.toOkioPath()
        val fs = DeferredDeleteFileSystem(delegate = FileSystem.SYSTEM, scope = this)

        assertTrue(tempFile.exists())

        fs.delete(path)
        // delete() must return without waiting for the actual filesystem delete to run.
        assertTrue(tempFile.exists())

        advanceUntilIdle()

        assertFalse(tempFile.exists())
    }

    @Test
    fun `given other operations when called then they pass through synchronously`() = runTest {
        val tempFile = File.createTempFile("deferred-delete-passthrough-test", ".tmp").apply { writeText("hello") }
        val path = tempFile.toOkioPath()
        val fs = DeferredDeleteFileSystem(delegate = FileSystem.SYSTEM, scope = this)

        val metadata = fs.metadataOrNull(path)

        assertTrue(metadata != null && metadata.size == 5L)
        tempFile.delete()
    }
}
