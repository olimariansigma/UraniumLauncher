package dev.uranium.launcher.core

/** A coarse progress report; [total] of 0 means indeterminate. */
data class Progress(val stage: String, val done: Long = 0, val total: Long = 0) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

typealias ProgressListener = (Progress) -> Unit
