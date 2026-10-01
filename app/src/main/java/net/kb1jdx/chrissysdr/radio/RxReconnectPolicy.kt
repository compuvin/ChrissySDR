package com.kb1jdx.chrissysdr.radio

/** RX may be retried after transport failures; TX is intentionally never retried. */
object RxReconnectPolicy {
    const val MAX_RETRIES = 3

    fun delaySeconds(
        kind: RadioFailureKind,
        completedRetries: Int,
        streamWasEstablished: Boolean,
    ): Long? {
        if (!streamWasEstablished) return null
        if (kind != RadioFailureKind.NETWORK && kind != RadioFailureKind.STREAM) return null
        if (completedRetries !in 0 until MAX_RETRIES) return null
        return 1L shl completedRetries
    }
}
