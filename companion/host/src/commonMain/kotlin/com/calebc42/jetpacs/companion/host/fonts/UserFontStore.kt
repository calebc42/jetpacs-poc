// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.fonts

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The user's own font files on this device: a directory the host owns
 * holding one copy of each picked file and a `manifest.json` that groups the
 * files into families by the name each file carries, plus which family, if
 * any, stands in for the sans, serif and mono roles.
 *
 * Receiver-owned presentation state, never EBP state and never shared: a
 * design names a family by its slug, this store says what the slug means on
 * this device. Picking a second file of the same family (its bold, its
 * italic) adds a face to the family; picking a file whose weight and style a
 * family already has replaces that face. Every write lands in a temporary
 * file and moves into place, and every read is bounded.
 */
class UserFontStore(private val directory: Path) {
    @Serializable
    data class Face(
        /** The file's name under the store directory. */
        val file: String,
        val weight: Int,
        val italic: Boolean = false,
        val variable: Boolean = false,
    )

    @Serializable
    data class Family(
        /** The bounded identifier a design names this family by. */
        val slug: String,
        /** The name the font files carry, for the settings screen. */
        val name: String,
        val faces: List<Face>,
    )

    /** The three roles a family may replace device-wide. */
    enum class Role(val wireName: String, val label: String, val bundledLabel: String) {
        SANS("sans", "Sans", "IBM Plex Sans (bundled)"),
        SERIF("serif", "Serif", "IBM Plex Serif (bundled)"),
        MONO("mono", "Mono", "IBM Plex Mono (bundled)");

        companion object {
            fun parse(wireName: String): Role? = entries.firstOrNull { it.wireName == wireName }
        }
    }

    /** Everything the manifest says, sorted by slug so two reads compare equal. */
    data class Catalog(
        val families: List<Family> = emptyList(),
        /** The slug standing in for each role; a role absent keeps the bundled family. */
        val roles: Map<Role, String> = emptyMap(),
    ) {
        fun family(slug: String): Family? = families.firstOrNull { it.slug == slug }

        companion object {
            val Empty = Catalog()
        }
    }

    @Serializable
    private data class Manifest(
        val format: Int = FORMAT,
        val families: List<Family> = emptyList(),
        val roles: Map<String, String> = emptyMap(),
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** The current catalog; an absent or unreadable manifest is empty, never an error. */
    fun catalog(): Catalog = synchronized(lock) { load().toCatalog() }

    /** Where [face]'s file is. */
    fun fileOf(face: Face): Path = directory.resolve(face.file)

    /**
     * Add the font in [bytes] under the family its own name table declares.
     * [sourceName] is the picked file's name, kept only for its extension.
     * Returns the family the face joined. Refuses, with a reason the settings
     * screen shows, anything that is not one TrueType or OpenType face, a
     * file over [MAX_FILE_BYTES], or a thirty-third family.
     */
    fun add(bytes: ByteArray, sourceName: String?): Family {
        require(bytes.size <= MAX_FILE_BYTES) { "the file is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MiB" }
        val metadata = OpenTypeMetadata.parse(bytes)
        val slug = metadata.slug
        val extension = sourceName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(6).joinToString("") { "%02x".format(it) }
        val fileName = "$slug-${metadata.weight}${if (metadata.italic) "i" else ""}-$digest.$extension"
        synchronized(lock) {
            val manifest = load()
            val existing = manifest.families.firstOrNull { it.slug == slug }
            require(existing != null || manifest.families.size < MAX_FAMILIES) {
                "no room for another family; remove one first ($MAX_FAMILIES at most)"
            }
            val replaced = existing?.faces?.filter { it.weight == metadata.weight && it.italic == metadata.italic }
                ?: emptyList()
            val kept = (existing?.faces ?: emptyList()) - replaced.toSet()
            require(kept.size < MAX_FACES) { "the family already has $MAX_FACES faces" }
            Files.createDirectories(directory)
            val temporary = directory.resolve("$fileName.tmp")
            Files.write(temporary, bytes)
            Files.move(temporary, directory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val face = Face(fileName, metadata.weight, metadata.italic, metadata.variable)
            val family = Family(
                slug = slug,
                name = existing?.name ?: metadata.familyName,
                faces = (kept + face).sortedWith(compareBy({ it.italic }, { it.weight })),
            )
            val families = manifest.families.filterNot { it.slug == slug } + family
            save(manifest.copy(families = families.sortedBy { it.slug }))
            for (old in replaced) if (old.file != fileName) deleteQuietly(directory.resolve(old.file))
            return family
        }
    }

    /** Remove the family [slug] and its files; a role it stood in for reverts to the bundled family. */
    fun remove(slug: String): Boolean = synchronized(lock) {
        val manifest = load()
        val family = manifest.families.firstOrNull { it.slug == slug } ?: return false
        save(
            manifest.copy(
                families = manifest.families - family,
                roles = manifest.roles.filterValues { it != slug },
            ),
        )
        for (face in family.faces) deleteQuietly(directory.resolve(face.file))
        true
    }

    /** Let [slug] stand in for [role] device-wide, or null to return the role to its bundled family. */
    fun assign(role: Role, slug: String?) = synchronized(lock) {
        val manifest = load()
        if (slug != null) require(manifest.families.any { it.slug == slug }) { "no installed family named $slug" }
        save(
            manifest.copy(
                roles = if (slug == null) manifest.roles - role.wireName else manifest.roles + (role.wireName to slug),
            ),
        )
    }

    private fun load(): Manifest {
        val file = directory.resolve(MANIFEST)
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_MANIFEST_BYTES) return Manifest()
        return runCatching { json.decodeFromString<Manifest>(Files.readString(file)) }
            .getOrDefault(Manifest())
            .let { manifest ->
                // A manifest from a later format, or one pointing at files that
                // are gone, degrades to what is still here.
                if (manifest.format > FORMAT) return Manifest()
                manifest.copy(
                    families = manifest.families.mapNotNull { family ->
                        val present = family.faces.filter { Files.isRegularFile(directory.resolve(it.file)) }
                        if (present.isEmpty()) null else family.copy(faces = present)
                    },
                )
            }
    }

    private fun save(manifest: Manifest) {
        Files.createDirectories(directory)
        val file = directory.resolve(MANIFEST)
        val temporary = directory.resolve("$MANIFEST.tmp")
        Files.writeString(temporary, json.encodeToString(manifest.copy(format = FORMAT)))
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun Manifest.toCatalog(): Catalog = Catalog(
        families = families.sortedBy { it.slug },
        roles = roles.mapNotNull { (role, slug) ->
            Role.parse(role)?.takeIf { families.any { family -> family.slug == slug } }?.let { it to slug }
        }.toMap(),
    )

    private fun deleteQuietly(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val FORMAT = 1
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val MAX_FAMILIES = 32
        const val MAX_FACES = 18
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
