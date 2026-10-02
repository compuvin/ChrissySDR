package com.kb1jdx.chrissysdr

/** A plain-text snapshot suitable for both viewing and sharing with a tester. */
internal fun diagnosticsText(state: RadioUiState, version: String): String = buildString {
    appendLine("ChrissySDR $version diagnostics")
    appendLine()
    appendLine("Connection")
    appendLine("Server: ${state.host}:${state.port}")
    appendLine("State: ${state.connectionState}")
    appendLine("Status: ${state.connectionStatus}")
    appendLine("Device: ${state.selectedDeviceLabel.ifBlank { "Not selected" }}")
    if (state.deviceDetails.isNotBlank()) {
        appendLine()
        appendLine("Device information")
        appendLine(state.deviceDetails.trim())
    }
    if (state.additionalDeviceDetails.isNotBlank()) {
        appendLine()
        appendLine("Additional device information")
        appendLine(state.additionalDeviceDetails.trim())
    }
    appendLine()
    appendLine("RX configuration")
    appendLine("Mode: ${state.mode}")
    appendLine("Frequency: ${state.frequency.toDoubleOrNull()?.let(::formatHz) ?: "Not available"}")
    appendLine("DSP passband: ${state.bandwidth.toDoubleOrNull()?.let(::formatHz) ?: "Not available"}")
    appendLine("Stream format: ${state.rxStreamFormat ?: "Not negotiated"}")
    appendLine("Requested sample rate: ${state.rxRequestedSampleRateHz?.let(::formatHz) ?: "Not requested"}")
    appendLine("Applied sample rate: ${state.appliedSampleRateHz?.let(::formatHz) ?: "Not reported"}")
    appendLine("Selected hardware bandwidth: ${state.rxHardwareBandwidthHz?.let(::formatHz) ?: "Not selected"}")
    appendLine("Applied hardware bandwidth: ${state.rxAppliedHardwareBandwidthHz?.let(::formatHz) ?: "Not reported"}")
    appendLine()
    appendLine("Stream health")
    appendLine("RX: ${if (state.rxActive) "active" else "inactive"}")
    appendLine("${if (state.rxActive) "RX stream rate" else "Last RX stream rate"}: " +
        (state.rxStreamRateHz?.let { "%.1f samples/s".format(it) } ?: "Not measured"))
    appendLine("RX sequence gaps: ${state.rxSequenceGaps?.toString() ?: "Not measured"}")
    appendLine("Observed RX overflow errors: ${state.observedRxOverflows}")
    appendLine("Observed TX underflow errors: ${state.observedTxUnderflows}")
    appendLine("Other hardware underrun/overrun counters: Not reported by this client")
    appendLine()
    appendLine("Recent errors")
    if (state.recentErrors.isEmpty()) appendLine("None recorded this app session")
    else state.recentErrors.forEach { appendLine("- $it") }
}

/** Only classify actual Soapy stream result codes, not incidental numbers in other errors. */
internal fun Throwable.isSoapyStreamCode(code: Int): Boolean =
    Regex("(?:stream status|stream error) ${Regex.escape(code.toString())}(?!\\d)")
        .containsMatchIn(message.orEmpty())
