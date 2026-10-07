package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.radio.RadioEndpoint
import com.kb1jdx.chrissysdr.radio.RadioOpenCancellation
import java.net.InetSocketAddress
import java.net.Socket

/** One SoapyRemote MAKE/control connection shared by successive RX and TX streams. */
internal class SoapyRemoteDeviceConnection private constructor(
    val endpoint: RadioEndpoint,
    val deviceArguments: Map<String, String>,
    private val socket: Socket,
) : AutoCloseable {
    val isUsable: Boolean get() = socket.isConnected && !socket.isClosed

    fun registerCancellation(cancellation: RadioOpenCancellation) {
        cancellation.register(socket)
    }

    @Synchronized
    fun <T> exchange(timeoutMs: Int = DEFAULT_RPC_TIMEOUT_MS, block: (Socket) -> T): T {
        check(isUsable) { "SoapyRemote device connection is closed" }
        val previousTimeout = socket.soTimeout
        socket.soTimeout = timeoutMs
        try {
            return block(socket)
        } finally {
            if (!socket.isClosed) socket.soTimeout = previousTimeout
        }
    }

    fun <T> transact(
        request: SoapyRpcWriter,
        timeoutMs: Int = DEFAULT_RPC_TIMEOUT_MS,
        decode: (SoapyRpcReader) -> T,
    ): T = exchange(timeoutMs) { control ->
        control.getOutputStream().apply { write(request.frame().encode()); flush() }
        val reader = SoapyRpcReader(SoapyRpcFrame.readFrom(control.getInputStream()).payload)
        decode(reader).also { reader.requireFinished() }
    }

    fun invalidate() {
        runCatching { socket.close() }
    }

    @Synchronized
    override fun close() {
        if (socket.isClosed) return
        try {
            transact(SoapyRpcWriter().call(UNMAKE), CLEANUP_RPC_TIMEOUT_MS) { it.requireVoid() }
            transact(SoapyRpcWriter().call(HANGUP), CLEANUP_RPC_TIMEOUT_MS) { it.requireVoid() }
        } finally {
            socket.close()
        }
    }

    companion object {
        const val DEFAULT_RPC_TIMEOUT_MS = 10_000
        private const val CLEANUP_RPC_TIMEOUT_MS = 1_500
        private const val MAKE = 1
        private const val UNMAKE = 2
        private const val HANGUP = 3

        fun open(
            endpoint: RadioEndpoint,
            deviceArguments: Map<String, String>,
            cancellation: RadioOpenCancellation? = null,
        ): SoapyRemoteDeviceConnection {
            val socket = Socket()
            cancellation?.register(socket)
            val connection = SoapyRemoteDeviceConnection(
                endpoint.copy(host = endpoint.host.trim()),
                deviceArguments - "soapy_remote_no_deeper",
                socket,
            )
            try {
                socket.connect(InetSocketAddress(connection.endpoint.host, endpoint.port), 3_000)
                socket.soTimeout = DEFAULT_RPC_TIMEOUT_MS
                connection.transact(
                    SoapyRpcWriter().call(MAKE).kwargs(connection.deviceArguments),
                ) { it.requireVoid() }
                return connection
            } catch (error: Throwable) {
                connection.invalidate()
                throw error
            }
        }
    }
}
