package app.pausecn.reports

import androidx.room.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One durable high-water date: failed, interrupted and unknown attempts are not automatically retried. */
@Entity(tableName = "automatic_report")
data class AutomaticReportState(@PrimaryKey val id: Int = 1, val enabled: Boolean = false, val epoch: Long = 0,
    val lastDate: String = "", val lastZone: String = "", val claimId: String = "",
    val status: String = "NEVER", val updatedAt: Long = 0)

@Dao
interface AutomaticReportDao {
    @Query("SELECT * FROM automatic_report WHERE id = 1") suspend fun state(): AutomaticReportState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(state: AutomaticReportState)
    @Query("UPDATE automatic_report SET status = :status, updatedAt = :now WHERE id = 1 AND claimId = :claim")
    suspend fun finish(claim: String, status: String, now: Long)
    @Query("DELETE FROM automatic_report") suspend fun clear()
}

internal data class AutomaticReportTicket(val date: String, val zone: String, val epoch: Long, val claimId: String)
internal object AutomaticReportPolicy {
    fun yesterday(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
    fun claim(state: AutomaticReportState, now: Long, zone: ZoneId, id: String): Pair<AutomaticReportState, AutomaticReportTicket>? {
        if (!state.enabled) return null
        val date = yesterday(now, zone)
        if (state.lastDate.isNotBlank()) {
            val previous = runCatching { LocalDate.parse(state.lastDate) }.getOrNull() ?: return null
            if (date <= previous) return null
        }
        require(id.isNotBlank())
        return state.copy(lastDate = date.toString(), lastZone = zone.id, claimId = id, status = "UNKNOWN", updatedAt = now) to
            AutomaticReportTicket(date.toString(), zone.id, state.epoch, id)
    }
    fun current(state: AutomaticReportState?, ticket: AutomaticReportTicket): Boolean = state != null && state.enabled &&
        state.epoch == ticket.epoch && state.claimId == ticket.claimId && state.lastDate == ticket.date &&
        state.lastZone == ticket.zone && state.status == "UNKNOWN"
    fun matches(ticket: AutomaticReportTicket, facts: ReportFacts): Boolean = facts.window.period == ReportPeriod.YESTERDAY &&
        facts.window.startDate.toString() == ticket.date && facts.window.zoneId == ticket.zone &&
        facts.window.endDateExclusive == facts.window.startDate.plusDays(1) && !facts.window.ongoing
}
