package com.androgrid.worker.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Execution outcome classification recorded in the local task execution ledger.
 *
 * Traces to: [REQ-OP-002], [REQ-OP-003], [INV-004], ADR-008
 */
enum class TaskLedgerStatus {
    COMPLETED,
    FAILED,
    STALE,
    SUBMISSION_FAILED
}

/**
 * Immutable summary of a single task execution attempt for local dashboard observability.
 * Explicitly excludes stdout and stderr payloads to prevent storage bloat and data leakage.
 *
 * Traces to: [NFR-SEC-001], [REQ-VER-001], [REQ-VER-002], ADR-008
 */
data class TaskLedgerEntry(
    val taskId: String,
    val runAttempt: Int,
    val route: String,
    val status: TaskLedgerStatus,
    val exitCode: Int,
    val errorCode: String,
    val executionDurationMs: Long,
    val completedAtEpochMs: Long
)

/**
 * Immutable UI-facing snapshot of cumulative execution counts and recent execution history.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], ADR-008
 */
data class TaskLedgerSnapshot(
    val tasksCompletedCount: Int = 0,
    val tasksFailedCount: Int = 0,
    val recentEntries: List<TaskLedgerEntry> = emptyList()
) {
    companion object {
        val EMPTY: TaskLedgerSnapshot = TaskLedgerSnapshot()
    }
}

/**
 * Single source of truth for durable task execution telemetry across Wi-Fi and cellular routes.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [INV-004], [NFR-SEC-001], ADR-008
 */
interface TaskLedgerRepository {
    /**
     * Reactive stream emitting updated execution snapshots to UI consumers.
     */
    val ledgerSnapshot: StateFlow<TaskLedgerSnapshot>

    /**
     * Records a task execution outcome atomically with deduplication on (taskId, runAttempt).
     *
     * @param entry metadata summary for the executed attempt.
     * @return true if newly recorded, false if skipped as an already-recorded terminal duplicate.
     */
    suspend fun recordTaskExecution(entry: TaskLedgerEntry): Boolean

    /**
     * Checks whether the specified (taskId, runAttempt) pair is present in the terminal
     * deduplication window.
     *
     * @param taskId unique orchestrator task identifier.
     * @param runAttempt monotonic execution attempt number.
     * @return true if already recorded in the deduplication index.
     */
    suspend fun isRecorded(taskId: String, runAttempt: Int): Boolean
}

/**
 * Shared in-memory accumulator encapsulating bounded FIFO deduplication and ring-buffer logic
 * used identically by both in-memory and persistent repository implementations.
 *
 * Traces to: [INV-004], ADR-008
 */
internal class LedgerAccumulator(
    var tasksCompletedCount: Int = 0,
    var tasksFailedCount: Int = 0,
    val seenKeys: LinkedHashSet<String> = LinkedHashSet(),
    val recentEntries: ArrayDeque<TaskLedgerEntry> = ArrayDeque()
) {
    /**
     * Applies [entry] if its (taskId, runAttempt) key is not already present in [seenKeys].
     *
     * @return true if state mutated, false if skipped as a duplicate.
     */
    fun record(entry: TaskLedgerEntry): Boolean {
        val key = dedupKey(entry.taskId, entry.runAttempt)
        if (seenKeys.contains(key)) {
            return false
        }

        // Only terminal outcomes enter the dedup set; SUBMISSION_FAILED stays non-terminal
        // so subsequent terminal retries for the same attempt are not suppressed.
        if (entry.status != TaskLedgerStatus.SUBMISSION_FAILED) {
            while (seenKeys.size >= MAX_DEDUP_KEYS) {
                val oldest = seenKeys.iterator().next()
                seenKeys.remove(oldest)
            }
            seenKeys.add(key)
        }

        when (entry.status) {
            TaskLedgerStatus.COMPLETED -> tasksCompletedCount += 1
            TaskLedgerStatus.FAILED -> tasksFailedCount += 1
            TaskLedgerStatus.STALE, TaskLedgerStatus.SUBMISSION_FAILED -> Unit
        }

        recentEntries.addFirst(entry)
        while (recentEntries.size > MAX_RECENT_ENTRIES) {
            recentEntries.removeLast()
        }

        return true
    }

    fun isRecorded(taskId: String, runAttempt: Int): Boolean =
        seenKeys.contains(dedupKey(taskId, runAttempt))

    fun toSnapshot(): TaskLedgerSnapshot = TaskLedgerSnapshot(
        tasksCompletedCount = tasksCompletedCount,
        tasksFailedCount = tasksFailedCount,
        recentEntries = recentEntries.toList()
    )

    companion object {
        const val MAX_RECENT_ENTRIES: Int = 20
        const val MAX_DEDUP_KEYS: Int = 512

        fun dedupKey(taskId: String, runAttempt: Int): String = "$taskId:$runAttempt"
    }
}

/**
 * Hermetic in-memory [TaskLedgerRepository] implementation for fast unit and Robolectric tests.
 *
 * Traces to: [INV-004], ADR-008
 */
class InMemoryTaskLedgerRepository : TaskLedgerRepository {
    private val mutex = Mutex()
    private val accumulator = LedgerAccumulator()
    private val _ledgerSnapshot = MutableStateFlow(TaskLedgerSnapshot.EMPTY)
    override val ledgerSnapshot: StateFlow<TaskLedgerSnapshot> = _ledgerSnapshot.asStateFlow()

    override suspend fun recordTaskExecution(entry: TaskLedgerEntry): Boolean = mutex.withLock {
        val recorded = accumulator.record(entry)
        if (recorded) {
            _ledgerSnapshot.value = accumulator.toSnapshot()
        }
        recorded
    }

    override suspend fun isRecorded(taskId: String, runAttempt: Int): Boolean = mutex.withLock {
        accumulator.isRecorded(taskId, runAttempt)
    }
}

/**
 * Production [TaskLedgerRepository] backed by SharedPreferences with versioned org.json
 * serialization, Mutex synchronization, and synchronous commit() off the Main Thread.
 *
 * Traces to: Section 6.1, [REQ-OP-002], [REQ-OP-003], [INV-004], [NFR-SEC-001], ADR-008
 */
class SharedPreferencesTaskLedgerRepository private constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    hydrationScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) : TaskLedgerRepository {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val mutex = Mutex()
    private var hydrated = false
    private var accumulator = LedgerAccumulator()

    private val _ledgerSnapshot = MutableStateFlow(TaskLedgerSnapshot.EMPTY)
    override val ledgerSnapshot: StateFlow<TaskLedgerSnapshot> = _ledgerSnapshot.asStateFlow()

    init {
        hydrationScope.launch {
            mutex.withLock {
                ensureHydratedLocked()
            }
        }
    }

    override suspend fun recordTaskExecution(entry: TaskLedgerEntry): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureHydratedLocked()
                val recorded = accumulator.record(entry)
                if (!recorded) {
                    return@withLock false
                }

                val jsonPayload = serializeAccumulator(accumulator)
                prefs.edit().putString(KEY_LEDGER_JSON, jsonPayload).commit()
                _ledgerSnapshot.value = accumulator.toSnapshot()
                true
            }
        }

    override suspend fun isRecorded(taskId: String, runAttempt: Int): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureHydratedLocked()
                accumulator.isRecorded(taskId, runAttempt)
            }
        }

    private fun ensureHydratedLocked() {
        if (hydrated) return
        val rawJson = prefs.getString(KEY_LEDGER_JSON, null)
        accumulator = if (rawJson.isNullOrBlank()) {
            LedgerAccumulator()
        } else {
            deserializeAccumulator(rawJson)
        }
        hydrated = true
        _ledgerSnapshot.value = accumulator.toSnapshot()
    }

    private fun serializeAccumulator(state: LedgerAccumulator): String {
        val dedupArray = JSONArray()
        for (key in state.seenKeys) {
            dedupArray.put(key)
        }

        val entriesArray = JSONArray()
        for (entry in state.recentEntries) {
            entriesArray.put(
                JSONObject().apply {
                    put("taskId", entry.taskId)
                    put("runAttempt", entry.runAttempt)
                    put("route", entry.route)
                    put("status", entry.status.name)
                    put("exitCode", entry.exitCode)
                    put("errorCode", entry.errorCode)
                    put("executionDurationMs", entry.executionDurationMs)
                    put("completedAtEpochMs", entry.completedAtEpochMs)
                }
            )
        }

        return JSONObject().apply {
            put("version", CURRENT_SCHEMA_VERSION)
            put("completedCount", state.tasksCompletedCount)
            put("failedCount", state.tasksFailedCount)
            put("dedupKeys", dedupArray)
            put("recentEntries", entriesArray)
        }.toString()
    }

    private fun deserializeAccumulator(rawJson: String): LedgerAccumulator {
        return try {
            val root = JSONObject(rawJson)
            val version = root.optInt("version", -1)
            if (version != CURRENT_SCHEMA_VERSION) {
                Log.w(TAG, "Unsupported ledger schema version $version; resetting ledger state.")
                return LedgerAccumulator()
            }

            val completedCount = root.optInt("completedCount", 0).coerceAtLeast(0)
            val failedCount = root.optInt("failedCount", 0).coerceAtLeast(0)

            val seenKeys = LinkedHashSet<String>()
            val dedupArray = root.optJSONArray("dedupKeys")
            if (dedupArray != null) {
                for (i in 0 until dedupArray.length()) {
                    val key = dedupArray.optString(i, "")
                    if (key.isNotEmpty()) {
                        while (seenKeys.size >= LedgerAccumulator.MAX_DEDUP_KEYS) {
                            seenKeys.remove(seenKeys.iterator().next())
                        }
                        seenKeys.add(key)
                    }
                }
            }

            val recentEntries = ArrayDeque<TaskLedgerEntry>()
            val entriesArray = root.optJSONArray("recentEntries")
            if (entriesArray != null) {
                val limit = minOf(entriesArray.length(), LedgerAccumulator.MAX_RECENT_ENTRIES)
                for (i in 0 until limit) {
                    val obj = entriesArray.optJSONObject(i) ?: continue
                    val taskId = obj.optString("taskId", "")
                    if (taskId.isEmpty()) continue
                    val runAttempt = obj.optInt("runAttempt", -1)
                    if (runAttempt < 0) continue
                    val statusName = obj.optString("status", "")
                    val status = TaskLedgerStatus.entries.firstOrNull { it.name == statusName }
                        ?: continue
                    recentEntries.addLast(
                        TaskLedgerEntry(
                            taskId = taskId,
                            runAttempt = runAttempt,
                            route = obj.optString("route", ""),
                            status = status,
                            exitCode = obj.optInt("exitCode", -1),
                            errorCode = obj.optString("errorCode", ""),
                            executionDurationMs = obj.optLong("executionDurationMs", 0L),
                            completedAtEpochMs = obj.optLong("completedAtEpochMs", 0L)
                        )
                    )
                }
            }

            LedgerAccumulator(
                tasksCompletedCount = completedCount,
                tasksFailedCount = failedCount,
                seenKeys = seenKeys,
                recentEntries = recentEntries
            )
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt task ledger JSON payload; resetting ledger state.", e)
            LedgerAccumulator()
        }
    }

    companion object {
        private const val TAG = "TaskLedgerRepository"
        internal const val PREFS_NAME = "androgrid_task_ledger"
        internal const val KEY_LEDGER_JSON = "ledger_state_json"
        internal const val CURRENT_SCHEMA_VERSION = 1

        @Volatile
        private var instance: SharedPreferencesTaskLedgerRepository? = null

        /**
         * Returns the application-scoped singleton instance of [SharedPreferencesTaskLedgerRepository].
         */
        fun getInstance(context: Context): SharedPreferencesTaskLedgerRepository {
            return instance ?: synchronized(this) {
                instance ?: SharedPreferencesTaskLedgerRepository(context).also { instance = it }
            }
        }

        /**
         * Creates an isolated [SharedPreferencesTaskLedgerRepository] instance with injected
         * dispatchers/scopes for hermetic unit and Robolectric tests.
         */
        @VisibleForTesting
        internal fun createForTesting(
            context: Context,
            ioDispatcher: CoroutineDispatcher,
            hydrationScope: CoroutineScope
        ): SharedPreferencesTaskLedgerRepository {
            return SharedPreferencesTaskLedgerRepository(
                context = context,
                ioDispatcher = ioDispatcher,
                hydrationScope = hydrationScope
            )
        }

        /**
         * Resets the singleton instance for hermetic unit and Robolectric tests.
         */
        @VisibleForTesting
        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
