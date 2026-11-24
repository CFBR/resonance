package com.resonance.music.data.download

/**
 * Splits a relative storage path into segments, rejecting anything that could
 * escape the offline root or address an absolute path.
 */
internal fun relativeSegments(relativePath: String): List<String> {
    require(relativePath.isNotEmpty()) { "Relative path is empty" }
    require(!relativePath.startsWith("/")) { "Relative path must not be absolute: $relativePath" }
    val segments = relativePath.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "Relative path has an empty or traversing segment: $relativePath"
    }
    return segments
}

/**
 * The provider's document id for [name], matching exactly first and ignoring
 * case second.
 *
 * A card is exFAT, which is case-insensitive, so a folder is listed as
 * "Alive Or Just Breathing" while a manifest written from different metadata
 * says "Alive or Just Breathing". Comparing exactly calls an existing file
 * missing: the import skips it and the queue downloads a duplicate. On a
 * case-sensitive provider two names can differ only by case, so an ambiguous
 * match stays a miss rather than serving a stranger's file.
 */
internal fun matchChild(children: Map<String, String>, name: String): String? {
    children[name]?.let { return it }
    val lowered = name.lowercase()
    val matches = children.keys.filter { it.lowercase() == lowered }
    return if (matches.size == 1) children[matches.first()] else null
}
