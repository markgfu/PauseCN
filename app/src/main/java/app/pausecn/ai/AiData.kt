package app.pausecn.ai

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.UUID

@Entity(tableName = "ai_config")
data class AiConfig(
    @PrimaryKey val id: Int = 1,
    val instanceId: String = UUID.randomUUID().toString(),
    val privacyEpoch: Long = 0,
    val styleVersion: Long = 0,
    val enabled: Boolean = false,
    val style: String = "像朋友提醒我，简短，不说教",
    val model: String = "deepseek-v4-flash",
    val manualPhrase: String = "",
)

@Entity(tableName = "ai_phrases")
data class AiPhrase(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scene: String,
    val text: String,
    val styleVersion: Long,
    val approved: Boolean = false,
)

@Entity(tableName = "ai_requests")
data class AiRequestRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val day: String,
    val startedAt: Long,
    val status: String = "UNKNOWN",
    val totalTokens: Int = 0,
)

@Dao
interface AiDao {
    @Query("SELECT * FROM personalization_config WHERE id = 1") suspend fun personalization(): PersonalizationConfig?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePersonalization(config: PersonalizationConfig)
    @Query("DELETE FROM personalization_config") suspend fun clearPersonalizationConfig()
    @Query("SELECT * FROM personalized_phrases ORDER BY createdAt, id") suspend fun personalizedPhrases(): List<PersonalizedPhrase>
    @Insert suspend fun insertPersonalized(rows: List<PersonalizedPhrase>)
    @Query("DELETE FROM personalized_phrases") suspend fun clearPersonalized()
    @Query("DELETE FROM personalized_phrases WHERE packageName = :pkg AND kind = :kind")
    suspend fun clearPersonalizedTarget(pkg: String, kind: String)
    @Query("UPDATE personalized_phrases SET consumed = 1 WHERE id = :id") suspend fun consumePersonalized(id: String)
    @Query("DELETE FROM personalized_phrases WHERE expiresAt <= :now OR packageName NOT IN (SELECT packageName FROM target_rules WHERE enabled = 1)")
    suspend fun prunePersonalized(now: Long)
    @Query("UPDATE personalization_config SET epoch = epoch + 1") suspend fun invalidatePersonalization()
    @Query("SELECT * FROM user_profile WHERE id = 1") suspend fun profile(): UserProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProfile(profile: UserProfile)
    @Query("DELETE FROM user_profile") suspend fun deleteProfile()
    @Query("SELECT * FROM ai_config WHERE id = 1") suspend fun config(): AiConfig?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(config: AiConfig)
    @Query("SELECT * FROM ai_phrases ORDER BY id") suspend fun phrases(): List<AiPhrase>
    @Insert suspend fun insertPhrases(rows: List<AiPhrase>)
    @Query("DELETE FROM ai_phrases") suspend fun clearPhrases()
    @Query("UPDATE ai_phrases SET approved = 1 WHERE styleVersion = :version") suspend fun approve(version: Long)
    @Insert suspend fun reserve(record: AiRequestRecord)
    @Query("SELECT COUNT(*) FROM ai_requests WHERE day = :day") suspend fun attempts(day: String): Int
    @Query("SELECT COALESCE(SUM(totalTokens), 0) FROM ai_requests WHERE day = :day") suspend fun tokens(day: String): Int
    @Query("UPDATE ai_requests SET status = :status, totalTokens = :tokens WHERE id = :id")
    suspend fun finish(id: String, status: String, tokens: Int)
    @Query("DELETE FROM ai_requests WHERE startedAt < :before") suspend fun pruneRequests(before: Long)
    @Query("DELETE FROM ai_requests") suspend fun clearRequests()
    @Query("SELECT COUNT(*) FROM ai_requests") suspend fun requestCount(): Int
}
