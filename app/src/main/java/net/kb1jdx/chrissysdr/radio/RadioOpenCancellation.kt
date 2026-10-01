package com.kb1jdx.chrissysdr.radio

import java.net.Socket

/** Closes sockets used by an in-flight open when the operator cancels it. */
class RadioOpenCancellation {
    private val sockets = mutableSetOf<Socket>()
    private var cancelled = false

    @Synchronized fun register(socket: Socket) {
        if (cancelled) runCatching { socket.close() } else sockets += socket
    }

    @Synchronized fun release() {
        sockets.clear()
    }

    @Synchronized fun cancel() {
        cancelled = true
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    @Synchronized fun isCancelled(): Boolean = cancelled
}
