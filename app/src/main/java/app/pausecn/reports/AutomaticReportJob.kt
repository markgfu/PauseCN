package app.pausecn.reports

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.room.withTransaction
import app.pausecn.PauseApplication
import app.pausecn.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

object AutomaticReportScheduling {
    private const val JOB_ID = 7102
    fun enabled(database: PauseDatabase) = database.invalidationTracker.createFlow("automatic_report", "report_config", "ai_config").map {
        database.withTransaction { database.automaticReportDao().state()?.enabled == true &&
            database.reportDao().config()?.enabled == true && database.aiDao().config()?.enabled == true }
    }
    fun reconcile(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        check(scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, AutomaticReportJob::class.java))
            .setPeriodic(24L * 60 * 60_000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS)
    }
}

/** Approximate system opportunity, never an exact alarm or a self-retrying network worker. */
class AutomaticReportJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    @Volatile private var activeParameters: JobParameters? = null
    override fun onStartJob(params: JobParameters): Boolean {
        activeParameters = params
        running = scope.launch {
            try {
                val container = (application as PauseApplication).container
                val healthy = withTimeoutOrNull(10_000) {
                    combine(container.databaseHealthStore.state, container.settingsHealthStore.state) { db, prefs ->
                        db == DatabaseHealthState.Healthy && prefs == SettingsHealthState.Healthy
                    }.first { it }
                } ?: false
                if (healthy) {
                    val reports = ReportRepository(container.database,
                        usagePermission = app.pausecn.usage.AndroidUsageSource(container.applicationContext)::hasPermission,
                        bootId = { app.pausecn.usage.UsageClock.bootId(container.applicationContext) })
                    container.aiRepository.runAutomaticYesterday(reports)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No reschedule or retry. Existing facts remain available. */ }
            finally { if (activeParameters === params) { activeParameters = null; jobFinished(params, false) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        activeParameters = null; running?.cancel(); running = null
        return false
    }
    override fun onDestroy() { activeParameters = null; scope.cancel(); super.onDestroy() }
}
