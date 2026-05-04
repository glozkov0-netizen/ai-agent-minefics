package com.aiagent.android.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiagent.android.agent.Agent
import com.aiagent.android.agent.AgentLog
import com.aiagent.android.data.Settings
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenRecorderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    private val _state = MutableStateFlow(
        UiState(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = settings.model,
            maxSteps = settings.maxSteps,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            reasoningEffort = settings.reasoningEffort,
            systemPrompt = settings.systemPrompt,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var pendingAnswerChannel: Channel<String>? = null
    private var pendingProjectionChannel: Channel<ProjectionGrant>? = null

    fun refreshServiceStatus() {
        _state.update { it.copy(serviceEnabled = AgentAccessibilityService.isRunning()) }
    }

    fun updateApiKey(value: String) {
        settings.apiKey = value
        _state.update { it.copy(apiKey = value) }
    }

    fun updateBaseUrl(value: String) {
        settings.baseUrl = value
        _state.update { it.copy(baseUrl = value) }
    }

    fun updateModel(value: String) {
        settings.model = value
        _state.update { it.copy(model = value) }
    }

    fun updateMaxSteps(value: Int) {
        settings.maxSteps = value
        _state.update { it.copy(maxSteps = value) }
    }

    fun updateTemperature(value: Float) {
        settings.temperature = value
        _state.update { it.copy(temperature = value) }
    }

    fun updateMaxTokens(value: Int) {
        settings.maxTokens = value
        _state.update { it.copy(maxTokens = value) }
    }

    fun updateReasoningEffort(value: String) {
        settings.reasoningEffort = value
        _state.update { it.copy(reasoningEffort = value) }
    }

    fun updateSystemPrompt(value: String) {
        settings.systemPrompt = value
        _state.update { it.copy(systemPrompt = value) }
    }

    fun updateInstruction(value: String) {
        _state.update { it.copy(instruction = value) }
    }

    fun runAgent() {
        val instruction = _state.value.instruction.trim()
        if (instruction.isEmpty()) return
        if (_state.value.running) return
        refreshServiceStatus()
        _state.update {
            it.copy(
                running = true,
                log = emptyList(),
                pendingQuestion = null,
                pendingAnswer = "",
            )
        }
        appendLog(LogEntry.System("Запуск агента: $instruction"))

        val agent = Agent(
            getApplication(),
            settings,
            askUser = { question -> waitForUserAnswer(question) },
            startScreenRecording = { startScreenRecording() },
            stopScreenRecording = { stopScreenRecording() },
        ) { entry -> appendAgentLog(entry) }

        currentJob = viewModelScope.launch {
            try {
                agent.run(instruction)
            } finally {
                _state.update { it.copy(running = false, pendingQuestion = null) }
                pendingAnswerChannel?.close()
                pendingAnswerChannel = null
                appendLog(LogEntry.System("Агент завершил работу."))
            }
        }
    }

    fun cancelAgent() {
        currentJob?.cancel()
        currentJob = null
        pendingAnswerChannel?.close()
        pendingAnswerChannel = null
        pendingProjectionChannel?.close()
        pendingProjectionChannel = null
        _state.update { it.copy(running = false, pendingQuestion = null, pendingProjection = false) }
        appendLog(LogEntry.System("Прервано пользователем."))
    }

    fun updatePendingAnswer(value: String) {
        _state.update { it.copy(pendingAnswer = value) }
    }

    fun submitAnswer() {
        val answer = _state.value.pendingAnswer.trim()
        val ch = pendingAnswerChannel ?: return
        if (answer.isEmpty()) return
        viewModelScope.launch { ch.send(answer) }
    }

    private suspend fun waitForUserAnswer(question: String): String {
        val ch = Channel<String>(capacity = 1)
        pendingAnswerChannel = ch
        _state.update { it.copy(pendingQuestion = question, pendingAnswer = "") }
        return try {
            ch.receive()
        } catch (_: Throwable) {
            "(пользователь отменил)"
        } finally {
            pendingAnswerChannel = null
            _state.update { it.copy(pendingQuestion = null, pendingAnswer = "") }
        }
    }

    /** Called from the Agent's `start_screen_recording` tool. Suspends until the user grants. */
    private suspend fun startScreenRecording(): String {
        if (ScreenRecorderService.isRecording) return "уже идёт запись"
        val ch = Channel<ProjectionGrant>(capacity = 1)
        pendingProjectionChannel = ch
        _state.update { it.copy(pendingProjection = true) }
        val grant = try {
            ch.receive()
        } catch (_: Throwable) {
            return "запись отменена"
        } finally {
            pendingProjectionChannel = null
            _state.update { it.copy(pendingProjection = false) }
        }
        if (grant.resultCode == 0 || grant.data == null) return "пользователь отказал в записи"
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_START
            putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, grant.resultCode)
            putExtra(ScreenRecorderService.EXTRA_DATA, grant.data)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        // Give the recorder a moment to start.
        kotlinx.coroutines.delay(400)
        return if (ScreenRecorderService.lastError != null) {
            "ошибка записи: ${ScreenRecorderService.lastError}"
        } else {
            "запись начата"
        }
    }

    private fun stopScreenRecording(): String {
        if (!ScreenRecorderService.isRecording) return "запись не велась"
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
        }
        app.startService(intent)
        val file = ScreenRecorderService.lastFile ?: ""
        return "запись остановлена, файл: $file"
    }

    /** Called by [MainActivity] after the system MediaProjection consent dialog returns. */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val ch = pendingProjectionChannel
        if (ch != null) {
            viewModelScope.launch { ch.send(ProjectionGrant(resultCode, data)) }
        }
    }

    fun isProjectionPending(): Boolean = pendingProjectionChannel != null

    private suspend fun appendAgentLog(entry: AgentLog) {
        val log = when (entry) {
            is AgentLog.Thinking -> LogEntry.Thinking(entry.step)
            is AgentLog.Assistant -> LogEntry.Assistant(entry.text)
            is AgentLog.ToolCall -> LogEntry.Tool(entry.name, entry.arguments, entry.summary)
            is AgentLog.AskUser -> LogEntry.AskUser(entry.question)
            is AgentLog.Done -> LogEntry.Done(entry.summary, entry.success)
            is AgentLog.Error -> LogEntry.Error(entry.message)
        }
        appendLog(log)
    }

    private fun appendLog(entry: LogEntry) {
        _state.update { state ->
            state.copy(log = state.log + entry.copyWithTime())
        }
    }
}

data class UiState(
    val instruction: String = "",
    val running: Boolean = false,
    val serviceEnabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val maxSteps: Int = 20,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 2048,
    val reasoningEffort: String = "low",
    val systemPrompt: String = "",
    val log: List<LogEntry> = emptyList(),
    /** When non-null, the agent is waiting for the user to answer this question. */
    val pendingQuestion: String? = null,
    val pendingAnswer: String = "",
    /** When true, the agent has requested screen recording and the UI must show the consent button. */
    val pendingProjection: Boolean = false,
)

data class ProjectionGrant(val resultCode: Int, val data: Intent?)

sealed class LogEntry(val time: String) {
    abstract fun copyWithTime(): LogEntry

    data class System(val text: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Thinking(val step: Int, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Assistant(val text: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Tool(val name: String, val arguments: String, val summary: String, private val t: String = now()) :
        LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class AskUser(val question: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Done(val summary: String, val success: Boolean, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Error(val message: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }

    companion object {
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun now(): String = fmt.format(Date())
    }
}
