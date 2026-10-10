package dev.walcott.data

import android.util.AtomicFile
import java.io.File

/**
 * Small text files written whole or not at all.
 *
 * `writeText` truncates first and writes after, so power lost in between — or a full disk, which
 * fails the write after the truncate has succeeded — leaves an empty file. For most of what this
 * app keeps in plain files that empties a counter; for the blocklists' state it was every list
 * downloaded again, megabytes each. A write here goes to a side file that replaces the real one
 * only once complete, and a failed one leaves the previous contents where they were.
 */
object AtomicText {

    fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
    }

    /** The file's text, or null when there is none. A write a crash interrupted is rolled back, not read. */
    fun read(file: File): String? {
        val atomic = AtomicFile(file)
        return runCatching { atomic.readFully().toString(Charsets.UTF_8) }.getOrNull()
    }
}
