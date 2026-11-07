package com.privateai.benchmark

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privateai.ai.ChatTurn
import com.privateai.ai.EngineHolder
import com.privateai.ai.GenerationConfig
import com.privateai.data.AppDatabase
import com.privateai.data.BenchmarkEntity
import com.privateai.data.model.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class DeviceMetrics(
    val ramAvailMb: Int,
    val ramTotalMb: Int,
    val appMemMb: Int,       // this app's PSS (includes the model): an estimate, not "AI-only" memory
    val batteryPct: Int,
    val charging: Boolean,
    val tempC: Double?,      // battery temperature, used as a proxy for device temperature
    val thermal: Int,        // Android thermal status 0..6, -1 unknown
)

data class BenchUi(
    val metrics: DeviceMetrics? = null,
    val modelName: String? = null,
    val modelBytes: Long = 0,
    val quant: String = "",
    val longPrompt: Boolean = false,
    val maxTokens: Int = 128,
    val runs: Int = 1,
    val running: Boolean = false,
    val progress: String? = null,
    val latest: BenchmarkEntity? = null,
    val history: List<BenchmarkEntity> = emptyList(),
    val error: String? = null,
)

private class RunResult(val firstMs: Long, val tps: Double, val totalMs: Long, val tokens: Int)

class BenchmarkViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val dao = AppDatabase.get(app).benchmarkDao()
    private val engine = EngineHolder.engine
    private val prefs = app.getSharedPreferences("privateai", 0)
    private val models = ModelManager(app)
    private val _ui = MutableStateFlow(BenchUi())
    val ui: StateFlow<BenchUi> = _ui
    private var job: Job? = null
    @Volatile private var stopRequested = false

    private companion object {
        const val SHORT_PROMPT = "Write a long, detailed story about a robot who learns to paint."
        const val PARAGRAPH = "Solar panels convert sunlight into electricity using photovoltaic cells. " +
            "When photons hit the cell, they free electrons, which flow as a current. " +
            "Inverters then turn the direct current into alternating current for homes. " +
            "Output depends on sunlight, temperature, panel angle and shading. "
        const val MAX_TEMP_C = 45.0
        const val SEVERE = 3 // PowerManager.THERMAL_STATUS_SEVERE
    }

    init {
        viewModelScope.launch { dao.observeAll().collect { l -> _ui.update { it.copy(history = l) } } }
    }

    fun setPrompt(long: Boolean) = _ui.update { it.copy(longPrompt = long) }
    fun setTokens(n: Int) = _ui.update { it.copy(maxTokens = n) }
    fun setRuns(n: Int) = _ui.update { it.copy(runs = n) }
    fun clearHistory() { viewModelScope.launch { dao.deleteAll() } }
    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Called while the screen is visible: refreshes device numbers every 2 seconds. */
    suspend fun monitor() {
        while (currentCoroutineContext().isActive) {
            val path = prefs.getString("model_path", null)
            val f = path?.let(::File)?.takeIf { it.exists() && engine.isLoaded }
            _ui.update {
                it.copy(
                    metrics = metrics(),
                    modelName = f?.name,
                    modelBytes = f?.length() ?: 0L,
                    quant = f?.let { x -> models.quantOf(x.name) } ?: "",
                )
            }
            delay(2000)
        }
    }

    private fun metrics(): DeviceMetrics {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val pssMb = try { am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss / 1024 } catch (_: Throwable) { -1 }
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val rawTemp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10.0
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1
        return DeviceMetrics(
            (mi.availMem / 1_048_576L).toInt(), (mi.totalMem / 1_048_576L).toInt(), pssMb, pct, charging, temp, thermal,
        )
    }

    fun run() {
        val s = _ui.value
        if (s.running) return
        if (!engine.isLoaded || s.modelName == null) { _ui.update { it.copy(error = "Load a model first (Models screen).") }; return }
        if (!EngineHolder.tryAcquire()) { _ui.update { it.copy(error = "The AI is busy with another task.") }; return }
        stopRequested = false
        _ui.update { it.copy(running = true, error = null, progress = "Starting…", latest = null) }
        job = viewModelScope.launch {
            try {
                val prompt = if (s.longPrompt) "Summarize the following text in detail.\n\n" + PARAGRAPH.repeat(6) else SHORT_PROMPT
                val startMetrics = metrics()
                val results = ArrayList<RunResult>()
                for (i in 1..s.runs) {
                    if (stopRequested) break
                    val m = metrics()
                    if (m.thermal >= SEVERE || (m.tempC ?: 0.0) >= MAX_TEMP_C) {
                        throw IllegalStateException(
                            "Stopped to protect your phone: it is too hot (${m.tempC?.let { "%.1f".format(it) + " °C" } ?: "thermal limit"}). Let it cool down and try again."
                        )
                    }
                    _ui.update { it.copy(progress = "Run $i of ${s.runs}…") }
                    results += oneRun(prompt, s.maxTokens)
                }
                if (results.isEmpty()) {
                    _ui.update { it.copy(error = if (stopRequested) "Stopped." else "No result.") }
                } else {
                    val end = metrics()
                    val entity = BenchmarkEntity(
                        createdAt = System.currentTimeMillis(),
                        model = s.modelName!!, modelBytes = s.modelBytes, quant = s.quant,
                        promptKind = if (s.longPrompt) "Long" else "Short",
                        maxTokens = s.maxTokens, runs = results.size,
                        firstTokenMs = results.map { it.firstMs }.average().roundToInt(),
                        tokensPerSec = results.map { it.tps }.average(),
                        durationMs = results.map { it.totalMs }.average().roundToInt(),
                        tokens = results.map { it.tokens }.average().roundToInt(),
                        availRamMb = end.ramAvailMb, appMemMb = end.appMemMb,
                        tempStartC = startMetrics.tempC ?: -1.0, tempEndC = end.tempC ?: -1.0,
                        batteryPct = end.batteryPct, thermal = end.thermal,
                    )
                    withContext(NonCancellable) { dao.insert(entity) }
                    _ui.update { it.copy(latest = entity) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.update { it.copy(error = t.message ?: "Benchmark failed") }
            } finally {
                withContext(NonCancellable) {
                    _ui.update { it.copy(running = false, progress = null) }
                    EngineHolder.release()
                }
            }
        }
    }

    fun stop() { if (_ui.value.running) { stopRequested = true; engine.stop() } }

    private suspend fun oneRun(prompt: String, maxTokens: Int): RunResult {
        val turns = listOf(ChatTurn("user", prompt))
        val t0 = SystemClock.elapsedRealtimeNanos()
        var first = 0L
        engine.generate(turns, GenerationConfig(maxTokens = maxTokens, temperature = 0f)).collect {
            if (first == 0L) first = SystemClock.elapsedRealtimeNanos()
        }
        val t1 = SystemClock.elapsedRealtimeNanos()
        val tokens = engine.lastTokenCount
        if (first == 0L) first = t1
        val decodeSec = (t1 - first) / 1e9
        val tps = when {
            tokens > 1 && decodeSec > 0.0 -> (tokens - 1) / decodeSec        // generation speed after the first token
            (t1 - t0) > 0 -> tokens / ((t1 - t0) / 1e9)
            else -> 0.0
        }
        return RunResult((first - t0) / 1_000_000L, tps, (t1 - t0) / 1_000_000L, tokens)
    }
}
