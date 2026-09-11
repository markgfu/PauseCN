package app.pausecn.data

import app.pausecn.nextUiStatusRefreshDelayMs
import app.pausecn.limitTargetSearchQuery
import app.pausecn.LocalDataOperationGate
import app.pausecn.retryAfterLocalDataRecovery
import app.pausecn.runFailClosedMutation
import app.pausecn.domain.ScheduleSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SettingsSanitizerTest {
    @Test
    fun corruptPersistedValuesAreClampedInsteadOfCrashing() {
        val result = sanitizedSettingsSnapshot(
            disclosureAccepted = true,
            ageEligibilityConfirmed = true,
            onboardingPreviewCompleted = true,
            scheduleEnabled = true,
            startMinutes = -10,
            endMinutes = 9_999,
            activeDaysMask = Int.MAX_VALUE,
            globallyPausedUntilEpochMs = -1,
            interventionSeconds = 100,
            temporaryPassMinutes = -10,
            historyRetentionDays = 7,
        )

        assertEquals(0, result.schedule.startMinutes)
        assertEquals(1439, result.schedule.endMinutes)
        assertEquals(ScheduleSpec.ALL_DAYS, result.schedule.activeDaysMask)
        assertEquals(0, result.globallyPausedUntilEpochMs)
        assertEquals(0, result.globallyPausedAtEpochMs)
        assertEquals(0, result.globallyPausedAtElapsedMs)
        assertEquals(0, result.globallyPausedUntilElapsedMs)
        assertEquals(15, result.interventionSeconds)
        assertEquals(1, result.temporaryPassMinutes)
        assertEquals(true, result.ageEligibilityConfirmed)
        assertEquals(true, result.onboardingPreviewCompleted)
        assertEquals(90, result.historyRetentionDays)
        val boundedSearch = limitTargetSearchQuery("搜".repeat(99) + "😀".repeat(2))
        assertEquals(100, boundedSearch.codePointCount(0, boundedSearch.length))
        assertEquals(true, boundedSearch.endsWith("😀"))
    }

    @Test
    fun validPersistedValuesArePreserved() {
        val result = sanitizedSettingsSnapshot(
            disclosureAccepted = false,
            ageEligibilityConfirmed = false,
            onboardingPreviewCompleted = false,
            scheduleEnabled = false,
            startMinutes = 540,
            endMinutes = 1080,
            activeDaysMask = 0b001_1111,
            globallyPausedUntilEpochMs = 1234,
            interventionSeconds = 6,
            temporaryPassMinutes = 5,
            historyRetentionDays = 365,
            globallyPausedAtEpochMs = 1_000,
            globallyPausedAtElapsedMs = 100,
            globallyPausedUntilElapsedMs = 334,
        )

        assertEquals(false, result.schedule.enabled)
        assertEquals(540, result.schedule.startMinutes)
        assertEquals(1080, result.schedule.endMinutes)
        assertEquals(0b001_1111, result.schedule.activeDaysMask)
        assertEquals(1234, result.globallyPausedUntilEpochMs)
        assertEquals(1_000, result.globallyPausedAtEpochMs)
        assertEquals(100, result.globallyPausedAtElapsedMs)
        assertEquals(334, result.globallyPausedUntilElapsedMs)
        assertEquals(6, result.interventionSeconds)
        assertEquals(5, result.temporaryPassMinutes)
        assertEquals(false, result.ageEligibilityConfirmed)
        assertEquals(false, result.onboardingPreviewCompleted)
        assertEquals(365, result.historyRetentionDays)

        assertEquals(true, result.isGloballyPaused(nowEpochMs = 1_100, nowElapsedMs = 200))
        assertEquals(false, result.isGloballyPaused(nowEpochMs = 900, nowElapsedMs = 200))
        assertEquals(false, result.isGloballyPaused(nowEpochMs = 1_100, nowElapsedMs = 334))
        assertEquals(
            134L,
            nextUiStatusRefreshDelayMs(
                settings = result,
                nowEpochMs = 1_100,
                nowElapsedMs = 200,
            ),
        )
        assertEquals(
            50L,
            nextUiStatusRefreshDelayMs(
                settings = SettingsSnapshot(),
                nowEpochMs = 119_950,
                nowElapsedMs = 10,
            ),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun historyRetentionOnlyAcceptsPublishedChoices() {
        assertEquals(30, sanitizeHistoryRetentionDays(30))
        assertEquals(90, sanitizeHistoryRetentionDays(90))
        assertEquals(365, sanitizeHistoryRetentionDays(365))
        assertEquals(DEFAULT_HISTORY_RETENTION_DAYS, sanitizeHistoryRetentionDays(31))

        var capturedFailure: Exception? = null
        runTest {
            assertEquals(
                true,
                runFailClosedMutation(onFailure = { capturedFailure = it }) {},
            )
            assertEquals(null, capturedFailure)
            assertEquals(
                false,
                runFailClosedMutation(onFailure = { capturedFailure = it }) {
                    throw IOException("simulated storage failure")
                },
            )

            var cancellationReportedAsFailure = false
            var mutationCancellationRethrown = false
            try {
                runFailClosedMutation(onFailure = { cancellationReportedAsFailure = true }) {
                    throw CancellationException("simulated mutation cancellation")
                }
            } catch (_: CancellationException) {
                mutationCancellationRethrown = true
            }
            assertTrue(mutationCancellationRethrown)
            assertFalse(cancellationReportedAsFailure)

            val mutationOnlyGate = LocalDataOperationGate()
            val releaseSerializedMutation = CompletableDeferred<Unit>()
            val serializedOrder = mutableListOf<String>()
            val serializedFirst = async {
                mutationOnlyGate.serializeMutation(Unit) {
                    serializedOrder += "first-start"
                    releaseSerializedMutation.await()
                    serializedOrder += "first-end"
                }
            }
            runCurrent()
            val serializedSecond = async {
                mutationOnlyGate.serializeMutation(Unit) {
                    serializedOrder += "second"
                }
            }
            runCurrent()
            assertEquals(listOf("first-start"), serializedOrder)
            releaseSerializedMutation.complete(Unit)
            serializedFirst.await()
            serializedSecond.await()
            assertEquals(listOf("first-start", "first-end", "second"), serializedOrder)

            val gate = LocalDataOperationGate()
            val firstMutationEntered = CompletableDeferred<Unit>()
            val releaseFirstMutation = CompletableDeferred<Unit>()
            val operationOrder = mutableListOf<String>()
            val firstMutation = async {
                gate.serializeMutation(false) {
                    operationOrder += "mutation-start"
                    firstMutationEntered.complete(Unit)
                    releaseFirstMutation.await()
                    operationOrder += "mutation-end"
                    true
                }
            }
            firstMutationEntered.await()
            val queuedBeforeLongOperation = async {
                gate.serializeMutation(false) {
                    operationOrder += "must-not-run-from-queue"
                    true
                }
            }
            runCurrent()
            assertTrue(gate.tryStartLongOperation())
            assertFalse(gate.tryStartLongOperation())
            val longOperation = async {
                gate.runStartedLongOperation {
                    operationOrder += "long-operation"
                }
            }
            val rejectedMutation = async {
                gate.serializeMutation(false) {
                    operationOrder += "must-not-run"
                    true
                }
            }
            assertFalse(rejectedMutation.await())
            releaseFirstMutation.complete(Unit)
            firstMutation.await()
            assertFalse(queuedBeforeLongOperation.await())
            longOperation.await()
            assertEquals(
                listOf("mutation-start", "mutation-end", "long-operation"),
                operationOrder,
            )

            val cancellationGate = LocalDataOperationGate()
            val heldMutationEntered = CompletableDeferred<Unit>()
            val releaseHeldMutation = CompletableDeferred<Unit>()
            val heldMutation = async {
                cancellationGate.serializeMutation(Unit) {
                    heldMutationEntered.complete(Unit)
                    releaseHeldMutation.await()
                }
            }
            heldMutationEntered.await()
            assertTrue(cancellationGate.tryStartLongOperation())
            val cancelledLongOperation = async {
                cancellationGate.runStartedLongOperation { error("must not enter") }
            }
            runCurrent()
            cancelledLongOperation.cancel()
            try {
                cancelledLongOperation.await()
            } catch (_: CancellationException) {
                // The gate must release its busy flag while preserving structured cancellation.
            }
            assertTrue(cancellationGate.tryStartLongOperation())
            releaseHeldMutation.complete(Unit)
            heldMutation.await()
            cancellationGate.runStartedLongOperation {}
            assertEquals(
                "accepted-after-cancellation",
                cancellationGate.serializeMutation("blocked") { "accepted-after-cancellation" },
            )

            var subscriptions = 0
            var flowFailures = 0
            val localDataHealthy = MutableStateFlow(false)
            val observed = mutableListOf<Int>()
            val collection = launch {
                flow {
                    subscriptions += 1
                    emit(subscriptions)
                    if (subscriptions == 1) throw IOException("simulated read failure")
                }.retryAfterLocalDataRecovery(
                    onFailure = { flowFailures += 1 },
                    awaitRecovery = { localDataHealthy.first { it } },
                ).take(2).toList(observed)
            }
            advanceUntilIdle()
            assertEquals(listOf(1), observed)
            assertEquals(1, flowFailures)
            assertEquals(1, subscriptions)
            localDataHealthy.value = true
            advanceUntilIdle()
            collection.join()
            assertEquals(listOf(1, 2), observed)
            assertEquals(1, flowFailures)
            assertEquals(2, subscriptions)

            val neverRecovered = MutableStateFlow(false)
            var cancelledSubscriptions = 0
            var cancelledFailures = 0
            val cancelledCollection = launch {
                flow<Int> {
                    cancelledSubscriptions += 1
                    throw IOException("simulated persistent read failure")
                }.retryAfterLocalDataRecovery(
                    onFailure = { cancelledFailures += 1 },
                    awaitRecovery = { neverRecovered.first { it } },
                ).toList()
            }
            advanceUntilIdle()
            assertEquals(1, cancelledSubscriptions)
            assertEquals(1, cancelledFailures)
            cancelledCollection.cancel()
            cancelledCollection.join()
            neverRecovered.value = true
            advanceUntilIdle()
            assertEquals(1, cancelledSubscriptions)
            assertEquals(1, cancelledFailures)

            var terminalFailures = 0
            var cancellationRethrown = false
            try {
                flow<Int> { throw CancellationException("sharing stopped") }
                    .retryAfterLocalDataRecovery(
                        onFailure = { terminalFailures += 1 },
                        awaitRecovery = {},
                    ).first()
            } catch (_: CancellationException) {
                cancellationRethrown = true
            }
            assertTrue(cancellationRethrown)

            var errorRethrown = false
            try {
                flow<Int> { throw AssertionError("fatal flow error") }
                    .retryAfterLocalDataRecovery(
                        onFailure = { terminalFailures += 1 },
                        awaitRecovery = {},
                    ).first()
            } catch (_: AssertionError) {
                errorRethrown = true
            }
            assertTrue(errorRethrown)
            assertEquals(0, terminalFailures)
        }
        assertEquals("simulated storage failure", capturedFailure?.message)
    }
}
