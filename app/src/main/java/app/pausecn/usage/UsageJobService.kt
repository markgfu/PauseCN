package app.pausecn.usage

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.pausecn.PauseApplication
import app.pausecn.data.DatabaseHealthState
import app.pausecn.data.SettingsHealthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One persisted, approximate daily LOCAL job. No network constraint, AI call or exact alarm. */
object UsageScheduling {
    private const val JOB_ID = 7101
    fun reconcile(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        check(scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UsageJobService::class.java))
            .setPeriodic(24L * 60 * 60_000).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS) {
            "系统未接受每日使用统计任务，可在记录页手动刷新"
        }
    }
}

class UsageJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                val container = (application as PauseApplication).container
                val ready = withTimeoutOrNull(10_000) {
                    container.databaseHealthStore.state.first { it == DatabaseHealthState.Healthy }
                    container.settingsHealthStore.state.first { it == SettingsHealthState.Healthy }
                    true
                } ?: false
                if (ready) container.usageRepository.refresh(container.settingsStore.settings.first().historyRetentionDays)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Next daily opportunity or explicit foreground refresh, no retry loop. */ }
            finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); running = null; return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
