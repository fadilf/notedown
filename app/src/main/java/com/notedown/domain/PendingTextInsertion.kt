package com.notedown.domain

/** One automatic delivery for a successful overlay session; history jobs never arm it. */
class PendingTextInsertion {
    private var pending = false
    fun begin(fromOverlay: Boolean) { pending = fromOverlay }
    fun consume(): Boolean = pending.also { pending = false }
    fun clear() { pending = false }
}
