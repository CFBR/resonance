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
