package app.pausecn.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "target_rules")
data class TargetRuleEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "intervention_events",
    indices = [Index("occurredAtEpochMs"), Index("packageName")],
)
data class InterventionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val occurredAtEpochMs: Long,
    val outcome: String,
    val purpose: String? = null,
    /** Time from the originating accessibility event to a successfully attached overlay. */
    val triggerLatencyMs: Long? = null,
)

@Entity(
    tableName = "service_sessions",
    indices = [Index("connectedAtEpochMs")],
)
data class ServiceSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectedAtEpochMs: Long,
    val connectedAtElapsedMs: Long,
    val lastHeartbeatAtEpochMs: Long,
    val lastHeartbeatAtElapsedMs: Long,
    val heartbeatCount: Int = 0,
    val maxHeartbeatGapMs: Long = 0,
)

enum class InterventionOutcome {
    SHOWN,
    EXITED,
    CONTINUED,
    DISMISSED,
    DISPLAY_FAILED,
}
