package com.kb1jdx.chrissysdr

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QsoAdifTest {
    @Test fun `exports UTC date time frequency and SSB submode`() {
        val entry = QsoEntry(
            id = 1,
            callsign = "kb1jdx",
            comment = "Good signal",
            frequencyHz = 14_287_500,
            mode = "USB",
            timestampUtcMillis = Instant.parse("2026-10-09T01:02:03Z").toEpochMilli(),
        )
        val adif = QsoAdif.export(listOf(entry), LocalDate.parse("2026-10-09"))
        assertTrue(adif.contains("<QSO_DATE:8>20261009"))
        assertTrue(adif.contains("<TIME_ON:6>010203"))
        assertTrue(adif.contains("<CALL:6>KB1JDX"))
        assertTrue(adif.contains("<FREQ:7>14.2875"))
        assertTrue(adif.contains("<MODE:3>SSB<SUBMODE:3>USB"))
        assertTrue(adif.contains("<COMMENT:11>Good signal<EOR>"))
    }

    @Test fun `filters by inclusive UTC date and maps NFM to FM`() {
        val old = QsoEntry(1, "OLD1", "", 7_100_000, "AM",
            Instant.parse("2026-10-08T23:59:59Z").toEpochMilli())
        val today = QsoEntry(2, "NEW1", "", 146_520_000, "NFM",
            Instant.parse("2026-10-09T00:00:00Z").toEpochMilli())
        val adif = QsoAdif.export(listOf(today, old), LocalDate.parse("2026-10-09"))
        assertFalse(adif.contains("OLD1"))
        assertTrue(adif.contains("<CALL:4>NEW1<FREQ:6>146.52<MODE:2>FM<EOR>"))
    }

    @Test fun `ASCII export does not alter the stored comment`() {
        val entry = QsoEntry(1, "W1AW", "Café", 3_500_000, "LSB",
            Instant.parse("2026-10-09T12:00:00Z").toEpochMilli())
        val adif = QsoAdif.export(listOf(entry), LocalDate.parse("2026-10-09"))
        assertTrue(adif.contains("<COMMENT:4>Cafe"))
        assertTrue(adif.contains("<MODE:3>SSB<SUBMODE:3>LSB"))
        assertTrue(entry.comment == "Café")
    }
}
