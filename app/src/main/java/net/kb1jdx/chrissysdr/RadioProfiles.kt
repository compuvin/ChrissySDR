package com.kb1jdx.chrissysdr

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    val sampleRateOverrideHz: Double?,
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
    val sampleRateOverrideHz: Double?,
)

@Dao
interface RadioProfileDao {
    @Query("SELECT * FROM radio_profiles ORDER BY name COLLATE NOCASE, id")
    fun all(): List<RadioProfileEntity>

    @Query("SELECT * FROM radio_profiles WHERE id = :id LIMIT 1")
    fun byId(id: String): RadioProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: RadioProfileEntity)

}

@Database(entities = [RadioProfileEntity::class], version = 1, exportSchema = true)
abstract class ChrissyDatabase : RoomDatabase() {
    abstract fun radioProfiles(): RadioProfileDao

    companion object {
        @Volatile private var instance: ChrissyDatabase? = null

        fun get(context: Context): ChrissyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChrissyDatabase::class.java,
                "chrissysdr.db",
            ).build().also { instance = it }
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
    sampleRateOverrideHz = sampleRateOverrideHz,
)

fun RadioProfileEntity.toProfile(): RadioProfile {
    val json = JSONObject(deviceArgumentsJson)
    return RadioProfile(
        id = id,
        name = name,
        host = host,
        port = port,
        deviceLabel = deviceLabel,
        deviceArguments = json.keys().asSequence().associateWith { json.getString(it) },
        frequency = frequency,
        bandwidth = bandwidth,
        sampleRateOverrideHz = sampleRateOverrideHz,
    )
}

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
