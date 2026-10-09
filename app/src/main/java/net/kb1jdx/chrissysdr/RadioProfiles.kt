package com.kb1jdx.chrissysdr

import android.content.Context
import com.kb1jdx.chrissysdr.radio.RadioChannelCapabilities
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

data class RadioProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val deviceLabel: String,
    val deviceArguments: Map<String, String>,
    val frequency: String,
    val bandwidth: String,
    val mode: String = "AM",
    val sampleRateOverrideHz: Double?,
    val rxGains: Map<String, Double> = emptyMap(),
    val rxHardwareAgc: Boolean? = null,
    val rxAntenna: String? = null,
    val squelchThresholdsDbfs: Map<String, Double> = emptyMap(),
    val noiseReductionLevels: Map<String, Int> = emptyMap(),
)

@Entity(tableName = "radio_profiles")
data class RadioProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val deviceLabel: String,
    val deviceArgumentsJson: String,
    val frequency: String,
    val bandwidth: String,
    @ColumnInfo(defaultValue = "'AM'") val mode: String,
    val sampleRateOverrideHz: Double?,
    @ColumnInfo(defaultValue = "'{}'") val rxGainsJson: String,
    val rxHardwareAgc: Boolean?,
    val rxAntenna: String?,
    @ColumnInfo(defaultValue = "'{}'") val squelchThresholdsJson: String = "{}",
    @ColumnInfo(defaultValue = "'{}'") val noiseReductionLevelsJson: String = "{}",
)

@Dao
interface RadioProfileDao {
    @Query("SELECT * FROM radio_profiles ORDER BY name COLLATE NOCASE, id")
    fun all(): List<RadioProfileEntity>

    @Query("SELECT * FROM radio_profiles WHERE id = :id LIMIT 1")
    fun byId(id: String): RadioProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: RadioProfileEntity)

    @Query("DELETE FROM radio_profiles WHERE id = :id")
    fun deleteById(id: String): Int

}

@Database(entities = [RadioProfileEntity::class, QsoEntry::class], version = 7, exportSchema = true)
abstract class ChrissyDatabase : RoomDatabase() {
    abstract fun radioProfiles(): RadioProfileDao
    abstract fun qsoLog(): QsoLogDao

    companion object {
        @Volatile private var instance: ChrissyDatabase? = null
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE radio_profiles ADD COLUMN mode TEXT NOT NULL DEFAULT 'AM'")
            }
        }
        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE radio_profiles ADD COLUMN rxGainsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE radio_profiles ADD COLUMN rxHardwareAgc INTEGER")
                db.execSQL("ALTER TABLE radio_profiles ADD COLUMN rxAntenna TEXT")
            }
        }
        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE radio_profiles ADD COLUMN squelchThresholdsJson TEXT NOT NULL DEFAULT '{}'")
            }
        }
        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE radio_profiles ADD COLUMN noiseReductionLevelsJson TEXT NOT NULL DEFAULT '{}'")
            }
        }
        // Version 6 was used only by an unpublished DeepFilterNet test build.
        private val migration5To7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS qso_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    callsign TEXT NOT NULL, comment TEXT NOT NULL,
                    frequencyHz INTEGER NOT NULL, mode TEXT NOT NULL,
                    timestampUtcMillis INTEGER NOT NULL
                )""")
            }
        }

        fun get(context: Context): ChrissyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChrissyDatabase::class.java,
                "chrissysdr.db",
            ).addMigrations(migration1To2, migration2To3, migration3To4, migration4To5,
                migration5To7)
                .build().also { instance = it }
        }
    }
}

fun RadioProfile.toEntity() = RadioProfileEntity(
    id = id,
    name = name,
    host = host,
    port = port,
    deviceLabel = deviceLabel,
    deviceArgumentsJson = JSONObject(deviceArguments).toString(),
    frequency = frequency,
    bandwidth = bandwidth,
    mode = mode,
    sampleRateOverrideHz = sampleRateOverrideHz,
    rxGainsJson = JSONObject(rxGains).toString(),
    rxHardwareAgc = rxHardwareAgc,
    rxAntenna = rxAntenna,
    squelchThresholdsJson = JSONObject(squelchThresholdsDbfs).toString(),
    noiseReductionLevelsJson = JSONObject(noiseReductionLevels).toString(),
)

fun RadioProfileEntity.toProfile(): RadioProfile {
    val json = JSONObject(deviceArgumentsJson)
    val gains = JSONObject(rxGainsJson)
    val squelch = JSONObject(squelchThresholdsJson)
    val noiseReduction = JSONObject(noiseReductionLevelsJson)
    return RadioProfile(
        id = id,
        name = name,
        host = host,
        port = port,
        deviceLabel = deviceLabel,
        deviceArguments = json.keys().asSequence().associateWith { json.getString(it) },
        frequency = frequency,
        bandwidth = bandwidth,
        mode = mode,
        sampleRateOverrideHz = sampleRateOverrideHz,
        rxGains = gains.keys().asSequence().associateWith { gains.getDouble(it) },
        rxHardwareAgc = rxHardwareAgc,
        rxAntenna = rxAntenna,
        squelchThresholdsDbfs = squelch.keys().asSequence().mapNotNull { mode ->
            val value = squelch.optDouble(mode, Double.NaN)
            if (mode in setOf("AM", "NFM", "USB", "LSB") && value.isFinite() &&
                value in -120.0..0.0) mode to value else null
        }.toMap(),
        noiseReductionLevels = noiseReduction.keys().asSequence().mapNotNull { mode ->
            val value = noiseReduction.optInt(mode, -1)
            if (mode in setOf("AM", "NFM", "USB", "LSB") && value in 1..2)
                mode to value else null
        }.toMap(),
    )
}

internal data class SavedRxControls(
    val gains: Map<String, Double>,
    val hardwareAgc: Boolean?,
    val antenna: String?,
)

/** Ignore saved controls that the currently selected device no longer advertises. */
internal fun supportedSavedRxControls(
    profile: RadioProfile,
    capabilities: RadioChannelCapabilities?,
): SavedRxControls = SavedRxControls(
    gains = profile.rxGains.mapNotNull { (name, value) ->
        val range = capabilities?.gainRanges?.get(name)
        if (range != null && value.isFinite() && value in range.minimum..range.maximum) {
            name to value
        } else null
    }.toMap(),
    hardwareAgc = profile.rxHardwareAgc.takeIf {
        capabilities?.automaticGain == true && capabilities.currentGainMode != null
    },
    antenna = profile.rxAntenna?.takeIf { it in capabilities?.antennas.orEmpty() },
)

/** A saved profile may only select an unambiguous radio on its saved server. */
internal fun matchProfileDevice(
    profile: RadioProfile,
    devices: List<RadioDeviceChoice>,
): RadioDeviceChoice? {
    val exact = devices.filter { it.arguments == profile.deviceArguments }
    if (exact.size == 1) return exact.single()
    if (exact.size > 1) return null

    val saved = profile.deviceArguments
    val identity = when {
        !saved["serial"].isNullOrBlank() ->
            listOf("serial", "remote:driver", "driver")
        !saved["host"].isNullOrBlank() && !saved["port"].isNullOrBlank() ->
            listOf("host", "port", "remote:driver", "driver")
        else -> return null
    }.filter { !saved[it].isNullOrBlank() }
    return devices.singleOrNull { candidate ->
        identity.all { key -> candidate.arguments[key] == saved[key] }
    }
}
