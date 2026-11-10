package dev.uranium.launcher.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bounded game/launcher log. Lines arrive from any thread; they are folded into [lines] in
 * batches so a chatty game cannot flood the UI with one recomposition per line.
 */
class ConsoleBuffer(scope: CoroutineScope, private val capacity: Int = 3000) {
    private sealed interface Event {
        data class Line(val text: String) : Event
        data object Clear : Event
    }

    private val incoming = Channel<Event>(Channel.UNLIMITED)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> get() = _lines

    init {
        scope.launch {
            val buffer = ArrayDeque<String>()
            while (true) {
                var event: Event? = incoming.receive()
                while (event != null) {
                    when (event) {
                        is Event.Line -> buffer.addLast(event.text)
                        Event.Clear -> buffer.clear()
                    }
                    event = incoming.tryReceive().getOrNull()
                }
                while (buffer.size > capacity) buffer.removeFirst()
                _lines.value = buffer.toList()
                delay(120)
            }
        }
    }

    fun append(line: String) {
        incoming.trySend(Event.Line(line))
    }

    fun clear() {
        incoming.trySend(Event.Clear)
    }
}
