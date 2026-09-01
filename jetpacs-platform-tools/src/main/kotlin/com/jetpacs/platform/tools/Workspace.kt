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

/**
 * Canonical, read-only boundaries around every path the platform tools may inspect.
 *
 * [root] is the Jetpacs workspace. [orgSourceRoot] is an optional, separate
 * allowlisted Emacs Org checkout exposed only through the `org/` namespace.
 * Keeping two roots explicit prevents a useful external reference tree from
 * silently widening normal workspace reads.
 */
internal class Workspace private constructor(
    val root: Path,
    val orgSourceRoot: Path?,
) {
    /** Resolve one regular, size-bounded file inside its explicit namespace. */
    fun resolveFile(requested: String, maxBytes: Long = MAX_SOURCE_BYTES): Path {
        if (requested.isBlank()) throw UserInputException("path must not be blank")
        val orgPath = requested.startsWith("org/")
        val boundary = if (orgPath) {
            orgSourceRoot ?: throw UserInputException(
                "Org source is unavailable; set JETPACS_ORG_SOURCE or --org-source",
            )
        } else {
            root
        }
        val namespaced = if (orgPath) requested.removePrefix("org/") else requested
        val candidate = Path.of(namespaced).let { path ->
            if (path.isAbsolute) path else boundary.resolve(path)
        }.normalize()

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
        return when {
            real.startsWith(root) -> root.relativize(real).portable()
            orgSourceRoot != null && real.startsWith(orgSourceRoot) ->
                "org/${orgSourceRoot.relativize(real).portable()}"
            else -> throw UserInputException("path is outside every allowed source root: $path")
        }
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
            ".claude", ".git", ".gradle", ".idea", "archives", "build",
            "node_modules", "out", "target",
        )
        val result = mutableListOf<Path>()
        val roots = buildList {
            add(root)
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
        fun open(explicit: Path? = null, explicitOrgSource: Path? = null): Workspace {
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
                }
            }
            return Workspace(canonicalRoot, canonicalOrg)
        }
    }
}

private fun Path.portable(): String = toString().replace('\\', '/')

/** Bound one human/tool result and make truncation visible in the result. */
internal fun String.capped(maxChars: Int = MAX_TOOL_TEXT_CHARS): String =
    if (length <= maxChars) this else take(maxChars) + "\n… output truncated at $maxChars characters …"
