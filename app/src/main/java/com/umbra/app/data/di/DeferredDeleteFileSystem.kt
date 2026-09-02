package com.umbra.app.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Source

/**
 * Wraps [delegate] (Coil's disk-cache filesystem) so eviction never synchronously blocks the
 * caller. Coil's disk cache calls `delete()` inline the moment a write pushes it over its size
 * limit — on whatever thread that write happened on, which for a media-heavy feed is a scroll-
 * driven image write. Deferring the actual filesystem delete onto [scope] keeps that write path
 * non-blocking; a failed deferred delete is swallowed (best-effort, same as Coil's own eviction
 * being advisory) rather than surfaced anywhere, since there's no caller left waiting for the
 * result by the time it runs.
 *
 * `deleteRecursively()` needs no override — [FileSystem]'s default implementation calls this
 * instance's own (overridden, deferred) `delete()` per entry via virtual dispatch.
 *
 * Every other operation is a plain synchronous passthrough to [delegate] — Coil's disk cache
 * doesn't need any of them deferred, only eviction.
 */
internal class DeferredDeleteFileSystem(
    private val delegate: FileSystem = FileSystem.SYSTEM,
    private val scope: CoroutineScope
) : FileSystem() {
    override fun canonicalize(path: Path): Path = delegate.canonicalize(path)
    override fun metadataOrNull(path: Path): FileMetadata? = delegate.metadataOrNull(path)
    override fun list(dir: Path): List<Path> = delegate.list(dir)
    override fun listOrNull(dir: Path): List<Path>? = delegate.listOrNull(dir)
    override fun openReadOnly(file: Path): FileHandle = delegate.openReadOnly(file)
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        delegate.openReadWrite(file, mustCreate, mustExist)
    override fun source(file: Path): Source = delegate.source(file)
    override fun sink(file: Path, mustCreate: Boolean): Sink = delegate.sink(file, mustCreate)
    override fun appendingSink(file: Path, mustExist: Boolean): Sink = delegate.appendingSink(file, mustExist)
    override fun createDirectory(dir: Path, mustCreate: Boolean) = delegate.createDirectory(dir, mustCreate)
    override fun atomicMove(source: Path, target: Path) = delegate.atomicMove(source, target)
    override fun createSymlink(source: Path, target: Path) = delegate.createSymlink(source, target)

    override fun delete(path: Path, mustExist: Boolean) {
        scope.launch { runCatching { delegate.delete(path, mustExist) } }
    }
}
