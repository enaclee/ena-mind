package com.enamind.ai.db

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

/** Выполненный анализ (прогон цепочки). */
@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val symbol: String,
    val interval: String,
    val regime: String?,
    val headline: String,
    val report: String,
    val resultJson: String
)

/** Прогноз из памяти (синхронизируется с forecasts.csv Python-ядра). */
@Entity(tableName = "forecasts")
data class ForecastEntity(
    @PrimaryKey val fid: String,
    val ts: String,
    val symbol: String,
    val interval: String,
    val regime: String?,
    val side: String,
    val confidence: Double,
    val p: Double?,
    val horizonH: Double,
    val entryPrice: Double?,
    val councilStatus: String?,
    val gateStatus: String?,
    val dataGrade: String?,
    val verifyTs: String? = null,
    val exitPrice: Double? = null,
    val movePct: Double? = null,
    val hit: Int? = null, // null = не проверен, 1 = совпал, 0 = мимо
    val note: String? = null
)

/** Кэш новостей (дедуп по заголовку). */
@Entity(tableName = "news")
data class NewsItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val source: String,
    val url: String,
    val publishedTs: Long,
    val fetchedTs: Long,
    val importance: String,
    val impact: Double,
    val assets: String
)

/** Результат проверки API. */
@Entity(tableName = "api_checks")
data class ApiCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val name: String,
    val status: String,
    val detail: String
)

@Dao
interface AnalysisDao {
    @Insert
    suspend fun insert(a: AnalysisEntity): Long

    @Query("SELECT * FROM analyses ORDER BY ts DESC LIMIT :limit")
    suspend fun last(limit: Int): List<AnalysisEntity>

    @Query("SELECT COUNT(*) FROM analyses")
    suspend fun count(): Int

    @Query("DELETE FROM analyses WHERE id NOT IN (SELECT id FROM analyses ORDER BY ts DESC LIMIT :keep)")
    suspend fun pruneOld(keep: Int)
}

@Dao
interface ForecastDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ForecastEntity>)

    @Query("SELECT * FROM forecasts ORDER BY ts DESC LIMIT :limit")
    suspend fun last(limit: Int): List<ForecastEntity>

    @Query("SELECT * FROM forecasts WHERE hit IS NOT NULL")
    suspend fun checked(): List<ForecastEntity>
}

@Dao
interface NewsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<NewsItemEntity>)

    @Query("SELECT * FROM news ORDER BY publishedTs DESC LIMIT :limit")
    suspend fun last(limit: Int): List<NewsItemEntity>

    @Query("DELETE FROM news WHERE publishedTs < :beforeTs")
    suspend fun prune(beforeTs: Long)
}

@Dao
interface ApiCheckDao {
    @Insert
    suspend fun insert(c: ApiCheckEntity): Long

    @Query("SELECT * FROM api_checks ORDER BY ts DESC LIMIT :limit")
    suspend fun last(limit: Int): List<ApiCheckEntity>

    @Query("DELETE FROM api_checks WHERE id NOT IN (SELECT id FROM api_checks ORDER BY ts DESC LIMIT :keep)")
    suspend fun pruneOld(keep: Int)
}

@Database(
    entities = [AnalysisEntity::class, ForecastEntity::class, NewsItemEntity::class, ApiCheckEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisDao(): AnalysisDao
    abstract fun forecastDao(): ForecastDao
    abstract fun newsDao(): NewsDao
    abstract fun apiCheckDao(): ApiCheckDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "enamind.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
