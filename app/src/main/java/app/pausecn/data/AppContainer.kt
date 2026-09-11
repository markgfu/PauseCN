package app.pausecn.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    val databaseHealthStore = DatabaseHealthStore(applicationContext)

    val database: PauseDatabase = Room.databaseBuilder(
        applicationContext,
        PauseDatabase::class.java,
        "pause.db",
    ).addMigrations(
        *PAUSE_DATABASE_MIGRATIONS,
    ).addCallback(app.pausecn.reports.REPORT_CACHE_CALLBACK).openHelperFactory(
        PreservingCorruptionOpenHelperFactory(databaseHealthStore),
    ).build()
    val repository = PauseRepository(applicationContext, database)
    val appCategories = AppCategoryRepository(database)
    val usageRepository = app.pausecn.usage.UsageRepository(database, app.pausecn.usage.AndroidUsageSource(applicationContext),
        bootId = { app.pausecn.usage.UsageClock.bootId(applicationContext) })
    private val displayBootId by lazy { app.pausecn.usage.UsageClock.bootId(applicationContext) }
    val displayRecorder = app.pausecn.usage.PauseDisplayRecorder(database) {
        app.pausecn.usage.UsageTime(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
            displayBootId, java.time.ZoneId.systemDefault().id)
    }
    val reasonMemoryCache = ReasonMemoryCache()
    val aiRepository = app.pausecn.ai.AiRepository(
        database,
        app.pausecn.ai.ApiKeyVault(applicationContext),
        phrasePrompt = {
            applicationContext.assets.open(app.pausecn.ai.PhraseProtocol.PROMPT_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        personalizedPrompt = {
            applicationContext.assets.open(app.pausecn.ai.PersonalizationProtocol.PROMPT_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        retentionDays = { settingsStore.settings.first().historyRetentionDays },
        readRuleState = {
            check(settingsHealthStore.state.value == SettingsHealthState.Healthy)
            settingsStore.ruleAdjustments.first().current
        },
        prepareAutomaticFacts = { usageRepository.refresh(settingsStore.settings.first().historyRetentionDays) },
        automaticReady = { databaseHealthStore.state.value == DatabaseHealthState.Healthy && settingsHealthStore.state.value == SettingsHealthState.Healthy },
        conversationPrompt = {
            applicationContext.assets.open(app.pausecn.ai.ConversationProtocol.PROMPT_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        reportPrompt = {
            applicationContext.assets.open(app.pausecn.reports.ReportProtocol.PROMPT_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        categoryPrompt = {
            applicationContext.assets.open(app.pausecn.ai.CategoryAiProtocol.PROMPT_ASSET)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
    )
    val databaseHealthMonitor = DatabaseHealthMonitor(
        database,
        databaseHealthStore,
        { repository.recoverInterruptedInterventions() },
    )
    val settingsHealthStore = SettingsHealthStore(applicationContext)
    val settingsStore = SettingsStore(applicationContext, healthStore = settingsHealthStore)
    val settingsHealthMonitor = SettingsHealthMonitor(
        settingsStore,
        settingsHealthStore,
        requireNotNull(settingsStore.recoveryManager),
    )
    val localDataExporter = LocalDataExporter(applicationContext, repository, settingsStore)
    val sessionGate = SessionGate(applicationContext)
    internal val reportShareStore by lazy { app.pausecn.reports.ReportShareStore(this) }
    val healthStore = HealthStore(applicationContext)
    val localDataResetter = LocalDataResetter(
        repository,
        settingsStore,
        settingsHealthMonitor,
        sessionGate,
        healthStore,
        aiRepository,
    )
}
