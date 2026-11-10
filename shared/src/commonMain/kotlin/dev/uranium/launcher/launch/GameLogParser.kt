package dev.uranium.launcher.launch

/**
 * Mojang's log4j config prints each log record as a `<log4j:Event>` XML block (the vanilla
 * launcher parses them too). This folds those blocks back into ordinary `[thread/LEVEL]: text`
 * lines; anything that is not part of an event passes through untouched.
 *
 * Not thread safe: feed it from the single thread that reads the game's output.
 */
class GameLogParser(private val emit: (String) -> Unit) {
    private val pending = StringBuilder()
    private var inEvent = false

    fun accept(line: String) {
        if (!inEvent) {
            if (!line.contains(EVENT_OPEN)) {
                emit(line)
                return
            }
            inEvent = true
        }
        pending.append(line).append('\n')
        if (line.contains(EVENT_CLOSE)) flushEvent()
        // A truncated event must not swallow the rest of the session.
        else if (pending.length > MAX_EVENT_CHARS) flushRaw()
    }

    /** Call when the stream ends so a half-received event is not lost. */
    fun finish() {
        if (pending.isNotEmpty()) flushRaw()
    }

    private fun flushEvent() {
        val block = pending.toString()
        reset()
        val level = attribute(block, "level") ?: "INFO"
        val thread = attribute(block, "thread") ?: "main"
        val message = cdata(block, "Message").orEmpty()
        val throwable = cdata(block, "Throwable")
        val lines = (if (throwable.isNullOrBlank()) message else "$message\n$throwable").trimEnd().split('\n')
        emit("[$thread/$level]: ${lines.first()}")
        lines.drop(1).forEach(emit)
    }

    private fun flushRaw() {
        pending.toString().trimEnd('\n').split('\n').forEach(emit)
        reset()
    }

    private fun reset() {
        pending.clear()
        inEvent = false
    }

    private fun attribute(block: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(block)?.groupValues?.get(1)

    private fun cdata(block: String, tag: String): String? {
        val open = "<log4j:$tag><![CDATA["
        val start = block.indexOf(open).takeIf { it >= 0 } ?: return null
        val end = block.indexOf("]]></log4j:$tag>", start).takeIf { it >= 0 } ?: return null
        return block.substring(start + open.length, end)
    }

    private companion object {
        const val EVENT_OPEN = "<log4j:Event"
        const val EVENT_CLOSE = "</log4j:Event>"
        const val MAX_EVENT_CHARS = 256 * 1024
    }
}
