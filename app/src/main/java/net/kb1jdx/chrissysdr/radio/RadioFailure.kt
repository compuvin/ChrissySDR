package com.kb1jdx.chrissysdr.radio

import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteException
import java.io.IOException

enum class RadioConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

enum class RadioFailureKind { NETWORK, DRIVER, CONFIGURATION, STREAM, PROTOCOL, UNKNOWN }

data class RadioFailure(
    val kind: RadioFailureKind,
    val operation: String,
    val detail: String,
) {
    val displayMessage: String get() = "$operation: $detail"

    companion object {
        fun from(operation: String, error: Throwable): RadioFailure {
            val kind = when (error) {
                is SoapyStreamException -> RadioFailureKind.STREAM
                is IOException -> RadioFailureKind.NETWORK
                is SoapyRemoteException -> RadioFailureKind.DRIVER
                is IllegalArgumentException -> RadioFailureKind.CONFIGURATION
                is IllegalStateException -> RadioFailureKind.PROTOCOL
                else -> RadioFailureKind.UNKNOWN
            }
            return RadioFailure(kind, operation, error.message ?: error.javaClass.simpleName)
        }
    }
}

class SoapyStreamException(message: String) : IOException(message)
