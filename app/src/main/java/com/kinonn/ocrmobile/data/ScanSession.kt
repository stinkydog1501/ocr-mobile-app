package com.kinonn.ocrmobile.data

/**
 * Process-scoped holder for the image being worked on during a capture → edit
 * → review flow. The bitmap is persisted to a cache file and referenced by
 * path here, so the working image survives navigation and configuration
 * changes without bloating navigation arguments.
 */
object ScanSession {
    /** Absolute path of the current working image (JPEG) in the cache dir. */
    var imagePath: String? = null
}
