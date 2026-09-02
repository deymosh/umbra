package com.umbra.app.audit

import java.io.File

internal data class SourceLineMatch(
    val relativePath: String,
    val lineNumber: Int,
    val line: String
)

internal object SourceAuditTestUtils {

    private val lineCommentPrefixes = listOf("//", "*", "/*")

    fun kotlinSourceFiles(): List<File> {
        val cwd = File(requireNotNull(System.getProperty("user.dir")) { "Missing user.dir system property" })
        val parent = cwd.parentFile ?: cwd
        val candidates = listOf(
            File(cwd, "app/src/main/java"),
            File(cwd, "src/main/java"),
            File(parent, "app/src/main/java")
        )

        val sourceRoot = candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not find source root. Checked: ${candidates.joinToString { it.path }}")

        return sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    fun findLineTokenMatches(token: String): List<SourceLineMatch> {
        return kotlinSourceFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, rawLine ->
                val trimmed = rawLine.trimStart()
                if (!trimmed.contains(token)) return@mapIndexedNotNull null
                if (lineCommentPrefixes.any { trimmed.startsWith(it) }) return@mapIndexedNotNull null
                SourceLineMatch(
                    relativePath = normalizePath(file),
                    lineNumber = index + 1,
                    line = rawLine.trim()
                )
            }
        }
    }

    private fun normalizePath(file: File): String {
        val full = file.absolutePath.replace('\\', '/')
        val marker = "/app/src/main/java/"
        val markerIndex = full.indexOf(marker)
        return if (markerIndex >= 0) {
            "app/src/main/java/" + full.substring(markerIndex + marker.length)
        } else {
            full
        }
    }
}
