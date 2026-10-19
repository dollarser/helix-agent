package com.helix.app.reminder

import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** Durable state is authoritative; AlarmManager/WorkManager are replaceable wake projections. */
@Suppress("TooManyFunctions") // One serialized persistence/publication boundary for reminder mutations.
class ReminderService(
    private val store: ReminderStore,
    private val delivery: ReminderDelivery,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutation = Mutex()
    val items = store.dao.observe()
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val failure = kotlinx.coroutines.flow.MutableStateFlow(false)

    @Suppress("TooGenericExceptionCaught") // Background reconciliation must remain visibly failed, never success.
    fun recoverAsync() =
        scope.launch {
            try {
                reconcile()
                failure.value = false
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure.value = true
                android.util.Log.e("HelixReminder", "Reminder recovery failed", error)
            }
        }

    suspend fun get(id: String) = withContext(Dispatchers.IO) { store.dao.get(id) }

    suspend fun events(id: String) = withContext(Dispatchers.IO) { store.dao.events(id) }

    suspend fun save(draft: LocalReminder): String =
        withContext(Dispatchers.IO) {
            require(draft.title.isNotBlank() && draft.title.length <= 160 && draft.body.length <= 20_000)
            val time = draft.localTime.takeIf { it.isNotEmpty() }?.let { draft.time() }
            ZoneId.of(draft.zone)
            require(time != null || draft.repeat == "ONCE")
            val now = clock()
            mutation.withLock {
                val previous = store.dao.get(draft.id)
                check(previous == null || previous.revision == draft.revision) { "Reminder changed; reopen it" }
                val sameTime = sameSchedule(previous, draft)
                require(time == null || sameTime || time.first() > now) { "Choose a future time" }
                val nextAt = if (sameTime) previous?.nextAt else time?.first()
                val item =
                    draft.copy(
                        id = draft.id.ifBlank { UUID.randomUUID().toString() },
                        revision = (previous?.revision ?: 0) + 1,
                        state = "ACTIVE",
                        nextAt = nextAt,
                        scheduling =
                            when {
                                time == null -> "MEMO"
                                nextAt == null -> "NONE"
                                else -> "PENDING"
                            },
                        lastOutcome = if (sameTime) previous?.lastOutcome.orEmpty() else "",
                        updatedAt = now,
                    )
                store.dao.save(item)
                previous?.let(delivery::cancel)
                delivery.dismiss(item.id)
                schedule(item)
                item.id
            }
        }

    private fun sameSchedule(
        previous: LocalReminder?,
        draft: LocalReminder,
    ): Boolean =
        previous?.state == "ACTIVE" && previous.localTime == draft.localTime &&
            previous.zone == draft.zone && previous.repeat == draft.repeat

    suspend fun finish(
        id: String,
        cancelled: Boolean,
    ) = withContext(Dispatchers.IO) {
        mutation.withLock {
            val item = requireNotNull(store.dao.get(id))
            store.dao.save(
                item.copy(
                    state = if (cancelled) "CANCELLED" else "COMPLETED",
                    revision = item.revision + 1,
                    nextAt = null,
                    scheduling = "NONE",
                    updatedAt = clock(),
                ),
            )
            delivery.cancel(item)
            delivery.dismiss(id)
        }
    }

    @Suppress("ComplexCondition") // All four durable identity/state facts must match a callback.
    suspend fun fire(
        id: String,
        revision: Long,
        due: Long,
    ) = withContext(Dispatchers.IO) {
        mutation.withLock {
            val item = store.dao.get(id)
            if (item != null && item.state == "ACTIVE" && item.revision == revision && item.nextAt == due) {
                if (due <= clock()) occur(item) else schedule(item)
            }
        }
    }

    suspend fun reconcile() =
        withContext(Dispatchers.IO) {
            mutation.withLock {
                // A notification may have been accepted just before process death. Do not blindly replay.
                store.dao.uncertain().forEach { event ->
                    val outcome = if (delivery.posted(event)) "POSTED" else "UNKNOWN"
                    store.transaction {
                        store.dao.event(event.copy(outcome = outcome, recordedAt = clock()))
                        val item = store.dao.get(event.reminderId)
                        if (item?.revision == event.revision && item.nextAt == event.dueAt) {
                            advance(item, outcome)
                        }
                    }
                }
                store.dao.all().forEach { item ->
                    val due = item.nextAt
                    if (item.state == "ACTIVE" && due != null) {
                        if (due <= clock()) occur(item) else schedule(item)
                    } else {
                        delivery.cancel(item)
                        if (item.state != "ACTIVE" || item.localTime.isBlank()) delivery.dismiss(item.id)
                    }
                }
            }
        }

    private suspend fun occur(item: LocalReminder) {
        val now = clock()
        val due = requireNotNull(item.nextAt)
        val pending = store.dao.eventAt(item.id, item.revision, due)?.takeIf { it.outcome == "POSTING" }
        if (pending != null) {
            recordAndAdvance(item, if (delivery.posted(pending)) "POSTED" else "UNKNOWN")
        } else if (now - due > LATE_GRACE) {
            recordAndAdvance(item, "MISSED")
        } else {
            store.dao.event(ReminderEvent(item.id, item.revision, due, "POSTING", now))
            val outcome = delivery.post(item)
            recordAndAdvance(item, outcome)
        }
        store.dao.get(item.id)?.let { schedule(it) }
    }

    private suspend fun recordAndAdvance(
        item: LocalReminder,
        outcome: String,
    ) = store.transaction {
        store.dao.event(ReminderEvent(item.id, item.revision, requireNotNull(item.nextAt), outcome, clock()))
        advance(item, outcome)
    }

    private suspend fun advance(
        item: LocalReminder,
        outcome: String,
    ) {
        val next = item.time().next(maxOf(clock(), requireNotNull(item.nextAt)))
        store.dao.save(item.copy(nextAt = next, lastOutcome = outcome, scheduling = "NONE", updatedAt = clock()))
    }

    private suspend fun schedule(item: LocalReminder) {
        if (item.state == "ACTIVE" && item.nextAt != null) {
            val status = delivery.schedule(item)
            store.dao.save(item.copy(scheduling = status))
        }
    }

    fun close() = scope.cancel()

    companion object {
        const val LATE_GRACE = 15 * 60 * 1000L
    }
}
