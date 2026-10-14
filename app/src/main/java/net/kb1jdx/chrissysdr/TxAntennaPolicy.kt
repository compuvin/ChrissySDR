package com.kb1jdx.chrissysdr

/** Never request a TX antenna that the selected TX channel did not advertise. */
internal object TxAntennaPolicy {
    fun requested(
        advertised: List<String>,
        rxAntenna: String?,
        syncToRx: Boolean,
        manualTxAntenna: String?,
    ): String? = (if (syncToRx) rxAntenna else manualTxAntenna)
        ?.takeIf { it in advertised }
}
