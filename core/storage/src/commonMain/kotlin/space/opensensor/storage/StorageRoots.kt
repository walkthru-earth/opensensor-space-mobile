package space.opensensor.storage

/**
 * The one typed lookup from [StorageRole] to an absolute directory. Rebuildable data lives in the OS
 * cache directory (`cacheDir` on Android, `Library/Caches` on iOS, #61). A role a platform does not
 * use is simply absent.
 */
class StorageRoots(private val directories: Map<StorageRole, String>) {
    init {
        for ((role, dir) in directories) {
            require(dir.startsWith("/") && !dir.endsWith("/")) { "storage root of $role must be absolute without a trailing slash" }
        }
    }

    operator fun get(role: StorageRole): String? = directories[role]

    fun rootOf(role: StorageRole): String = checkNotNull(directories[role]) { "no storage root for $role" }

    /** Every directory of [cls]. */
    fun directories(cls: StorageClass): List<String> = directories.filterKeys { it.storageClass == cls }.values.toList()

    /**
     * The class of the deepest root that contains [path], or null when no root does. A path below a
     * Durable root is Durable even when another role's root nests inside it, so the most protective
     * class wins on ties of containment.
     */
    fun classOf(path: String): StorageClass? {
        val containing = directories.filter { (_, dir) -> path == dir || path.startsWith("$dir/") }
        if (containing.isEmpty()) return null
        if (containing.keys.any { it.storageClass == StorageClass.Durable }) return StorageClass.Durable
        return containing.maxBy { it.value.length }.key.storageClass
    }
}
