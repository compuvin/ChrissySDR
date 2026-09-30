package com.kb1jdx.chrissysdr

data class StreamFormatChoice(val format: String, val fullScale: Double)

object StreamFormatPolicy {
    fun choose(
        availableFormats: List<String>,
        nativeFormat: String?,
        nativeFullScale: Double?,
    ): StreamFormatChoice? {
        val nativeIsUsable = nativeFormat in availableFormats &&
            nativeFormat in SUPPORTED_FORMATS &&
            nativeFullScale != null && nativeFullScale.isFinite() && nativeFullScale > 0.0
        val format = when {
            nativeIsUsable && nativeFormat in listOf("CS8", "CS16") -> nativeFormat
            else -> PREFERRED_FORMATS.firstOrNull { it in availableFormats }
        } ?: return null
        val fullScale = if (nativeIsUsable && format == nativeFormat) nativeFullScale!! else when (format) {
            "CS8" -> 128.0
            "CS16" -> 32768.0
            "CF32" -> 1.0
            else -> error("Unsupported format $format")
        }
        return StreamFormatChoice(format, fullScale)
    }

    private val PREFERRED_FORMATS = listOf("CS16", "CF32", "CS8")
    private val SUPPORTED_FORMATS = PREFERRED_FORMATS.toSet()
}
