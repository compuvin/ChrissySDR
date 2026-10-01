package com.kb1jdx.chrissysdr.soapyremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal enum class RpcType(val id: Int) {
    CHAR(0), BOOL(1), INT32(2), INT64(3), FLOAT64(4), STRING(6), RANGE(7), RANGE_LIST(8),
    STRING_LIST(9), FLOAT64_LIST(10), KWARGS(11), KWARGS_LIST(12), EXCEPTION(13), VOID(14), CALL(15),
    ARG_INFO(17), ARG_INFO_LIST(18)
}

data class SoapyRange(val minimum: Double, val maximum: Double, val step: Double)

data class SoapyArgInfo(
    val key: String,
    val value: String,
    val name: String,
    val description: String,
    val units: String,
    val type: Int,
    val range: SoapyRange,
    val options: List<String>,
    val optionNames: List<String>,
)

class SoapyRpcWriter {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun call(id: Int) = apply { type(RpcType.CALL); int32(id) }
    fun char(value: Int) = apply { type(RpcType.CHAR); output.writeByte(value) }
    fun bool(value: Boolean) = apply { type(RpcType.BOOL); output.writeBoolean(value) }
    fun int32(value: Int) = apply { type(RpcType.INT32); output.writeInt(value) }
    fun int64(value: Long) = apply { type(RpcType.INT64); output.writeLong(value) }

    fun float64(value: Double) = apply {
        type(RpcType.FLOAT64)
        if (value == 0.0) {
            int32(0)
            int64(0)
        } else {
            val exponent = Math.getExponent(value) + 1
            val mantissa = Math.scalb(value, 53 - exponent).toLong()
            int32(exponent)
            int64(mantissa)
        }
    }

    fun string(value: String) = apply {
        type(RpcType.STRING)
        val encoded = value.toByteArray(Charsets.UTF_8)
        int32(encoded.size)
        output.write(encoded)
    }

    fun kwargs(values: Map<String, String>) = apply {
        type(RpcType.KWARGS)
        int32(values.size)
        values.forEach { (key, value) -> string(key).string(value) }
    }

    fun emptyStringList() = apply { type(RpcType.STRING_LIST); int32(0) }
    fun emptyFloat64List() = apply { type(RpcType.FLOAT64_LIST); int32(0) }
    fun emptyRangeList() = apply { type(RpcType.RANGE_LIST); int32(0) }
    fun argInfo(value: SoapyArgInfo) = apply {
        type(RpcType.ARG_INFO)
        string(value.key).string(value.value).string(value.name)
            .string(value.description).string(value.units).int32(value.type)
        range(value.range)
        stringList(value.options)
        stringList(value.optionNames)
    }
    fun argInfoList(values: List<SoapyArgInfo>) = apply {
        type(RpcType.ARG_INFO_LIST)
        int32(values.size)
        values.forEach { argInfo(it) }
    }
    fun range(value: SoapyRange) = apply {
        type(RpcType.RANGE)
        float64(value.minimum).float64(value.maximum).float64(value.step)
    }
    fun stringList(values: List<String>) = apply {
        type(RpcType.STRING_LIST)
        int32(values.size)
        values.forEach { string(it) }
    }
    fun sizeList(values: List<Int>) = apply {
        output.writeByte(16)
        int32(values.size)
        values.forEach(::int32)
    }

    fun voidValue() = apply { type(RpcType.VOID) }

    fun frame(): SoapyRpcFrame = SoapyRpcFrame(payload = bytes.toByteArray())
    private fun type(type: RpcType) = output.writeByte(type.id)
}

class SoapyRpcReader(payload: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(payload))

    fun call(): Int { expect(RpcType.CALL); return int32() }
    fun char(): Int { expect(RpcType.CHAR); return input.readUnsignedByte() }
    fun bool(): Boolean { expect(RpcType.BOOL); return input.readBoolean() }

    fun string(): String {
        expect(RpcType.STRING)
        val length = int32()
        require(length >= 0 && length <= input.available()) { "Invalid string length $length" }
        val encoded = ByteArray(length)
        input.readFully(encoded)
        return encoded.toString(Charsets.UTF_8)
    }

    fun kwargs(): Map<String, String> {
        expect(RpcType.KWARGS)
        val count = int32()
        require(count >= 0) { "Invalid map size $count" }
        return buildMap(count) { repeat(count) { put(string(), string()) } }
    }

    fun kwargsList(): List<Map<String, String>> {
        expect(RpcType.KWARGS_LIST)
        val count = int32()
        require(count >= 0) { "Invalid list size $count" }
        return List(count) { kwargs() }
    }

    fun stringList(): List<String> {
        expect(RpcType.STRING_LIST)
        val count = int32()
        require(count >= 0) { "Invalid list size $count" }
        return List(count) { string() }
    }

    fun float64List(): List<Double> {
        expect(RpcType.FLOAT64_LIST)
        val count = int32()
        require(count >= 0) { "Invalid list size $count" }
        return List(count) { float64() }
    }

    fun rangeList(): List<SoapyRange> {
        expect(RpcType.RANGE_LIST)
        val count = int32()
        require(count >= 0) { "Invalid list size $count" }
        return List(count) { range() }
    }

    fun range(): SoapyRange {
        expect(RpcType.RANGE)
        return SoapyRange(float64(), float64(), float64())
    }

    fun argInfoList(): List<SoapyArgInfo> {
        expect(RpcType.ARG_INFO_LIST)
        val count = int32()
        require(count >= 0) { "Invalid argument-info list size $count" }
        return List(count) { argInfo() }
    }

    fun argInfo(): SoapyArgInfo {
        expect(RpcType.ARG_INFO)
        return SoapyArgInfo(
            key = string(),
            value = string(),
            name = string(),
            description = string(),
            units = string(),
            type = int32(),
            range = range(),
            options = stringList(),
            optionNames = stringList(),
        )
    }

    fun float64(): Double {
        expect(RpcType.FLOAT64)
        val exponent = int32()
        expect(RpcType.INT64)
        val mantissa = input.readLong()
        return Math.scalb(mantissa.toDouble(), exponent - 53)
    }

    fun int32(): Int { expect(RpcType.INT32); return input.readInt() }

    fun requireVoid() {
        expect(RpcType.VOID)
        require(input.available() == 0) { "Unexpected data after void response" }
    }

    fun requireFinished() {
        require(input.available() == 0) { "Unexpected trailing SoapyRemote RPC data" }
    }

    private fun expect(expected: RpcType) {
        val actual = input.readUnsignedByte()
        if (actual == RpcType.EXCEPTION.id) throw SoapyRemoteException(string())
        require(actual == expected.id) { "Expected RPC type ${expected.id}, received $actual" }
    }
}

class SoapyRemoteException(message: String) : Exception(message)
