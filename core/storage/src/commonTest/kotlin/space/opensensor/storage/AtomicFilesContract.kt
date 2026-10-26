package space.opensensor.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The write protocol of #60, run against both real platform file systems. */
abstract class AtomicFilesContract {
    /** A fresh directory for one test, removed after it. */
    abstract val root: String
    abstract fun newFiles(tempDir: String): AtomicFiles
    abstract fun newTree(): StorageTree

    /** Files whose directory syncs are appended to [synced], at least every directory sync. */
    abstract fun newRecordingFiles(tempDir: String, synced: MutableList<String>): AtomicFiles

    /** Writes [bytes] at [path] outside the protocol, as another writer would. */
    abstract fun writeRaw(path: String, bytes: ByteArray)

    /** The bytes at [path], or null when there is no file. */
    abstract fun read(path: String): ByteArray?

    private val temp get() = "$root/tmp"
    private val files by lazy { newFiles(temp) }

    @Test
    fun commitNoReplaceRefusesAFinalThatAppearedAfterThePlan() {
        val final = "$root/archive/thing-a/2026/day.parquet"
        val partial = files.createTemp()
        writeRaw(partial, "downloaded".encodeToByteArray())
        // Planned with the final absent. Another writer commits first.
        val existing = "verified first".encodeToByteArray()
        writeRaw(final, existing)

        assertFailsWith<AlreadyCommitted> { files.commitNoReplace(partial, final, StorageClass.Durable) }

        assertContentEquals(existing, read(final))
        assertContentEquals("downloaded".encodeToByteArray(), read(partial))
    }

    @Test
    fun commitNoReplaceMovesIntoNewDirectories() {
        val final = "$root/archive/thing-a/2026/10/day.parquet"
        val partial = files.createTemp()
        writeRaw(partial, "bytes".encodeToByteArray())

        files.commitNoReplace(partial, final, StorageClass.Durable)

        assertContentEquals("bytes".encodeToByteArray(), read(final))
        assertNull(read(partial))
    }

    @Test
    fun aCommitSyncsEveryDirectoryItCreatesUpToTheFirstThatExisted() {
        writeRaw("$root/archive/other", ByteArray(0))
        val synced = mutableListOf<String>()
        val files = newRecordingFiles(temp, synced)
        val partial = files.createTemp()
        writeRaw(partial, "bytes".encodeToByteArray())

        files.commitNoReplace(partial, "$root/archive/thing-a/year=2026/month=10/day.parquet", StorageClass.Durable)
        files.createNoReplace("$root/archive/thing-b/boot/1.anchor", "receipt".encodeToByteArray(), StorageClass.Durable)

        val expected = listOf(
            "$root/archive/thing-a/year=2026/month=10",
            "$root/archive/thing-a/year=2026",
            "$root/archive/thing-a",
            "$root/archive/thing-b/boot",
            "$root/archive/thing-b",
            "$root/archive",
        )
        for (dir in expected) assertTrue(dir in synced, "$dir was not synced, synced $synced")
        assertTrue(root !in synced, "sync stops at the first directory that already existed")
    }

    @Test
    fun writeAtomicallyReplacesAndLeavesNoTemp() {
        val target = "$root/receipts/a.json"
        files.writeAtomically(target, "one".encodeToByteArray(), StorageClass.Durable)
        files.writeAtomically(target, "two".encodeToByteArray(), StorageClass.Rebuildable)
        files.writeAtomically("$root/receipts/empty.json", ByteArray(0), StorageClass.Scratch)

        assertContentEquals("two".encodeToByteArray(), read(target))
        assertContentEquals(ByteArray(0), read("$root/receipts/empty.json"))
        assertEquals(emptyList(), newTree().list(temp).filter { !it.isDirectory })
    }

    @Test
    fun createNoReplaceWritesOnceAndKeepsTheFirst() {
        val target = "$root/anchors/a.anchor"
        files.createNoReplace(target, "first".encodeToByteArray(), StorageClass.Durable)
        assertFailsWith<AlreadyCommitted> { files.createNoReplace(target, "second".encodeToByteArray(), StorageClass.Durable) }

        assertContentEquals("first".encodeToByteArray(), read(target))
        assertEquals(emptyList(), newTree().list(temp).filter { !it.isDirectory })
    }

    @Test
    fun commitReplaceOverwritesARebuildableOutput() {
        val target = "$root/derived/day.parquet"
        writeRaw(target, "old".encodeToByteArray())
        val built = files.createTemp(".parquet")
        writeRaw(built, "new".encodeToByteArray())

        files.commitReplace(built, target, StorageClass.Rebuildable)

        assertContentEquals("new".encodeToByteArray(), read(target))
    }

    @Test
    fun tempFilesCarryThePrefixInTheTempDirectory() {
        val path = files.createTemp(".parquet")
        assertTrue(path.startsWith("$temp/${AtomicFiles.TEMP_PREFIX}"))
        assertTrue(path.endsWith(".parquet"))
        assertContentEquals(ByteArray(0), read(path))
        files.syncFile(path, StorageClass.Durable)
        files.syncDir(temp)
    }

    @Test
    fun treeListsRelativePathsAndDeletesWholeDirectories() {
        writeRaw("$root/runs/run-a/x.zip", "12345".encodeToByteArray())
        writeRaw("$root/runs/b.txt", "1".encodeToByteArray())
        val tree = newTree()

        val entries = tree.list("$root/runs").associateBy { it.path }
        assertEquals(setOf("run-a", "run-a/x.zip", "b.txt"), entries.keys)
        assertTrue(entries.getValue("run-a").isDirectory)
        assertEquals(5L, entries.getValue("run-a/x.zip").bytes)
        assertTrue(entries.getValue("b.txt").modifiedEpochMs > 0)

        tree.delete("$root/runs/run-a")
        tree.delete("$root/runs/missing")
        assertEquals(setOf("b.txt"), tree.list("$root/runs").map { it.path }.toSet())
        assertEquals(emptyList(), tree.list("$root/absent"))
    }
}
