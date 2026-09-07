package com.jetpacs.platform.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal const val MAX_SOURCE_BYTES: Long = 2L * 1024L * 1024L
internal const val MAX_TOOL_TEXT_CHARS: Int = 60_000

/** Repositories that are addressable through an explicit source namespace. */
internal val SIBLING_REPOSITORIES = listOf(
    "ebp",
    "ebp.el",
    "ebp-kmp",
    "ebp-compose",
    "ebp-org",
    "glasspane",
    "jetpacs-authoring",
)

/**
 * Canonical, read-only boundaries around every path the platform tools may inspect.
 *
 * [root] is the Jetpacs workspace. [orgSourceRoot] is an optional, separate
 * allowlisted Emacs Org checkout exposed only through the `org/` namespace.
 * [repositoriesRoot] is only the parent locator for the fixed named sibling
 * roots; arbitrary directories below it are never scanned. Keeping each root
 * explicit prevents a useful external reference tree from silently widening
 * normal workspace reads.
 */
internal class Workspace private constructor(
    val root: Path,
    val orgSourceRoot: Path?,
    val repositoriesRoot: Path?,
    private val siblingRoots: Map<String, Path>,
) {
    /** Resolve one regular, size-bounded file inside its explicit namespace. */
    fun resolveFile(requested: String, maxBytes: Long = MAX_SOURCE_BYTES): Path {
        if (requested.isBlank()) throw UserInputException("path must not be blank")
        val namespace = requested.substringBefore('/', missingDelimiterValue = "")
        val (boundary, namespaced) = when {
            requested == "org" || requested.startsWith("org/") ->
                (orgSourceRoot ?: throw UserInputException(
                    "Org source is unavailable; set JETPACS_ORG_SOURCE or --org-source",
                )) to requested.removePrefix("org/")
            namespace in SIBLING_REPOSITORIES ->
                (siblingRoots[namespace] ?: throw UserInputException(
                    "repository '$namespace' is unavailable; set JETPACS_REPOSITORIES_ROOT or --repositories-root",
                )) to requested.removePrefix("$namespace/")
            else -> root to requested
        }
        val relativePath = Path.of(namespaced)
        if (relativePath.isAbsolute) throw UserInputException("path must be namespace-relative: $requested")
        val candidate = boundary.resolve(relativePath).normalize()

        if (!candidate.exists()) throw UserInputException("file does not exist: $requested")
        val real = candidate.toRealPath()
        if (!real.startsWith(boundary)) {
            throw UserInputException("path leaves its allowed source root: $requested")
        }
        if (!real.isRegularFile()) throw UserInputException("path is not a regular file: $requested")
        val size = Files.size(real)
        if (size > maxBytes) {
            throw UserInputException("file is $size bytes; the read-only tool limit is $maxBytes")
        }
        return real
    }

    /** Render an allowed canonical path as workspace-relative or `org/…`. */
    fun relative(path: Path): String {
        val real = path.toRealPath()
        if (real.startsWith(root)) return root.relativize(real).portable()
        if (orgSourceRoot != null && real.startsWith(orgSourceRoot)) {
            return "org/${orgSourceRoot.relativize(real).portable()}"
        }
        siblingRoots.entries.sortedByDescending { it.value.name.length }.forEach { (namespace, boundary) ->
            if (real.startsWith(boundary)) return "$namespace/${boundary.relativize(real).portable()}"
        }
        throw UserInputException("path is outside every allowed source root: $path")
    }

    /** Decode one already-resolved source file as UTF-8. */
    fun readText(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    /** Return the first readable workspace candidate, preserving caller order. */
    fun firstExisting(vararg candidates: String): Path? =
        candidates.asSequence().mapNotNull { candidate ->
            try {
                resolveFile(candidate)
            } catch (_: UserInputException) {
                null
            }
        }.firstOrNull()

    /**
     * Enumerate bounded source files in deterministic relative-path order.
     *
     * Org stays excluded unless [includeOrg] is explicit. Sorting happens
     * before [limit] so filesystem-provider iteration order cannot change the
     * selected result set.
     */
    fun sourceFiles(
        extensions: Set<String>,
        limit: Int = 20_000,
        includeOrg: Boolean = false,
    ): Sequence<Path> {
        val ignoredDirectories = setOf(
            ".claude", ".git", ".gradle", ".idea", "archives", "build", "ebp-poc", "jetpacs",
            "node_modules", "out", "target",
        )
        val result = mutableListOf<Path>()
        val roots = buildList {
            add(root)
            siblingRoots.values.forEach(::add)
            if (includeOrg) orgSourceRoot?.let(::add)
        }
        roots.forEach { sourceRoot ->
            Files.walkFileTree(
                sourceRoot,
                object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        if (dir != sourceRoot && dir.name in ignoredDirectories) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        val extension = file.name.substringAfterLast('.', "").lowercase()
                        if (attrs.isRegularFile && extension in extensions && attrs.size() <= MAX_SOURCE_BYTES) {
                            val real = file.toRealPath()
                            if (real.startsWith(sourceRoot)) result.add(real)
                        }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        }
        // File-tree iteration order is provider-specific. Sort before taking
        // the bound so identical checkouts produce identical search results.
        return result.distinct().sortedBy(::relative).take(limit).asSequence()
    }

    companion object {
        /**
         * Open the workspace and optional Org boundary from argument,
         * environment, or documented checkout discovery order.
         */
        fun open(
            explicit: Path? = null,
            explicitOrgSource: Path? = null,
            explicitRepositoriesRoot: Path? = null,
        ): Workspace {
            val environment = System.getenv("JETPACS_WORKSPACE")?.takeIf { it.isNotBlank() }?.let(Path::of)
            val starting = (explicit ?: environment ?: Path.of(System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize()
            if (!starting.exists() || !starting.isDirectory()) {
                throw UserInputException("workspace root is not a directory: $starting")
            }
            val detected = if (explicit != null || environment != null) {
                starting
            } else {
                generateSequence(starting) { it.parent }
                    .firstOrNull { candidate ->
                        candidate.resolve("README.org").exists() &&
                            (candidate.resolve(".git").exists() || candidate.resolve("jetpacs-platform-tools").exists())
                    }
                    ?: starting
            }
            val canonicalRoot = detected.toRealPath()
            val repositoriesEnvironment = System.getenv("JETPACS_REPOSITORIES_ROOT")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
            val configuredRepositories = explicitRepositoriesRoot ?: repositoriesEnvironment
            val repositoriesCandidate = configuredRepositories ?: repositoryRootDiscovery(canonicalRoot)
            val canonicalRepositories = repositoriesCandidate?.let { candidate ->
                val absolute = candidate.toAbsolutePath().normalize()
                if (!absolute.exists() || !absolute.isDirectory()) {
                    throw UserInputException("repositories root is not a directory: $absolute")
                }
                absolute.toRealPath()
            }
            val siblingRoots = canonicalRepositories?.let { repositoriesRoot ->
                val roots = SIBLING_REPOSITORIES.mapNotNull { name ->
                    val candidate = repositoriesRoot.resolve(name)
                    if (!candidate.exists()) return@mapNotNull null
                    if (!candidate.isDirectory()) {
                        throw UserInputException("repository root is not a directory: $candidate")
                    }
                    if (Files.isSymbolicLink(candidate)) {
                        throw UserInputException("repository '$name' must be a real direct sibling under $repositoriesRoot")
                    }
                    val real = candidate.toRealPath()
                    if (real.parent != repositoriesRoot) {
                        throw UserInputException("repository '$name' must be a direct sibling under $repositoriesRoot")
                    }
                    if (real.startsWith(canonicalRoot) || canonicalRoot.startsWith(real)) {
                        throw UserInputException("repository '$name' overlaps the Jetpacs workspace: $real")
                    }
                    name to real
                }.toMap()
                val distinct = roots.values.toList()
                if (distinct.size != distinct.distinct().size) {
                    throw UserInputException("repository roots must not overlap")
                }
                roots
            }.orEmpty()
            val orgEnvironment = System.getenv("JETPACS_ORG_SOURCE")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
            val explicitOrg = explicitOrgSource ?: orgEnvironment
            val discoveredOrg = canonicalRoot.resolve("../../emacs/emacs/lisp/org").normalize()
            val orgCandidate = explicitOrg ?: discoveredOrg.takeIf { it.exists() && it.isDirectory() }
            val canonicalOrg = orgCandidate?.let { candidate ->
                val absolute = candidate.toAbsolutePath().normalize()
                if (!absolute.exists() || !absolute.isDirectory()) {
                    throw UserInputException("Org source root is not a directory: $absolute")
                }
                absolute.toRealPath().also { orgRoot ->
                    if (orgRoot.startsWith(canonicalRoot) || canonicalRoot.startsWith(orgRoot)) {
                        throw UserInputException(
                            "Org source root must not overlap the Jetpacs workspace: $orgRoot",
                        )
                    }
                    if (siblingRoots.values.any { orgRoot.startsWith(it) || it.startsWith(orgRoot) }) {
                        throw UserInputException(
                            "Org source root must not overlap a named repository: $orgRoot",
                        )
                    }
                }
            }
            return Workspace(canonicalRoot, canonicalOrg, canonicalRepositories, siblingRoots)
        }

        private fun repositoryRootDiscovery(root: Path): Path? {
            generateSequence(root.parent) { it.parent }
                .take(2)
                .forEach { candidate ->
                    if (SIBLING_REPOSITORIES.any { candidate.resolve(it).isDirectory() }) return candidate
                }
            return null
        }
    }
}

private fun Path.portable(): String = toString().replace('\\', '/')

/** Bound one human/tool result and make truncation visible in the result. */
internal fun String.capped(maxChars: Int = MAX_TOOL_TEXT_CHARS): String =
    if (length <= maxChars) this else take(maxChars) + "\n… output truncated at $maxChars characters …"
