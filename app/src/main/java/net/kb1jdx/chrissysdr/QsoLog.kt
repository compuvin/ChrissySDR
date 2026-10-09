package com.kb1jdx.chrissysdr

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Entity(tableName = "qso_entries")
data class QsoEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callsign: String,
    val comment: String,
    val frequencyHz: Long,
    val mode: String,
    val timestampUtcMillis: Long,
)

@Dao
interface QsoLogDao {
    @Query("SELECT * FROM qso_entries ORDER BY timestampUtcMillis DESC, id DESC")
    fun all(): List<QsoEntry>

    @Insert
    fun insert(entry: QsoEntry): Long

    @Query("DELETE FROM qso_entries WHERE id = :id")
    fun deleteById(id: Long): Int
}

/** ADI export of only the details captured by the quick log; unknown fields are omitted. */
internal object QsoAdif {
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val timeFormat = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT)

    fun export(entries: List<QsoEntry>, fromDateUtc: LocalDate): String = buildString {
        append("<ADIF_VER:5>3.1.6<PROGRAMID:10>ChrissySDR<EOH>\n")
        entries.asSequence()
            .filter { !Instant.ofEpochMilli(it.timestampUtcMillis).atZone(ZoneOffset.UTC)
                .toLocalDate().isBefore(fromDateUtc) }
            .sortedWith(compareBy<QsoEntry> { it.timestampUtcMillis }.thenBy { it.id })
            .forEach { entry ->
                val utc = Instant.ofEpochMilli(entry.timestampUtcMillis).atZone(ZoneOffset.UTC)
                append(tag("QSO_DATE", utc.toLocalDate().format(dateFormat)))
                append(tag("TIME_ON", utc.toLocalTime().format(timeFormat)))
                append(tag("CALL", entry.callsign.uppercase(Locale.ROOT)))
                val mhz = BigDecimal.valueOf(entry.frequencyHz)
                    .movePointLeft(6).stripTrailingZeros().toPlainString()
                append(tag("FREQ", mhz))
                when (entry.mode) {
                    "NFM" -> append(tag("MODE", "FM"))
                    "USB", "LSB" -> {
                        append(tag("MODE", "SSB"))
                        append(tag("SUBMODE", entry.mode))
                    }
                    else -> append(tag("MODE", entry.mode))
                }
                entry.comment.takeIf { it.isNotBlank() }?.let { append(tag("COMMENT", it)) }
                append("<EOR>\n")
            }
    }

    private fun tag(name: String, value: String): String {
        // ADI String fields are ASCII; preserve richer comments in Room and
        // transliterate only the exported copy.
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .map { if (it.code in 32..126) it else ' ' }
            .joinToString("")
        return "<$name:${ascii.length}>$ascii"
    }
}
