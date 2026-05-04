package com.aiagent.android.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aiagent.android.data.Settings
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.LlmClient
import com.aiagent.android.llm.LlmException
import com.aiagent.android.llm.ToolCall
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenState
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives the LLM <-> device interaction loop.
 *
 *  1. Send the user's instruction + system prompt to the LLM with the available tool schemas.
 *  2. The LLM returns one or more tool calls.
 *  3. Each tool call is executed against the AccessibilityService.
 *  4. The tool results are appended to the conversation and we loop until the LLM calls `done`
 *     (or we exceed [Settings.maxSteps]).
 */
class Agent(
    private val context: Context,
    private val settings: Settings,
    private val askUser: suspend (String) -> String,
    private val startScreenRecording: suspend () -> String,
    private val stopScreenRecording: suspend () -> String,
    private val onLog: suspend (AgentLog) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(userInstruction: String) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            onLog(AgentLog.Error("Служба Спецвозможностей не запущена. Включите в Настройки Android → Спецвозможности → AI Agent."))
            return
        }
        if (settings.apiKey.isBlank()) {
            onLog(AgentLog.Error("API-ключ не задан. Укажите его на вкладке Настройки."))
            return
        }

        val client = LlmClient(settings.baseUrl, settings.apiKey)
        val systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() } ?: SYSTEM_PROMPT
        val messages = mutableListOf<ChatMessage>(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userInstruction),
        )
        var lastScreenState: ScreenState? = null

        try {
            for (step in 1..settings.maxSteps) {
                onLog(AgentLog.Thinking(step))
                val baseRequest = ChatRequest(
                    model = settings.model,
                    messages = messages,
                    tools = Tools.toolList(),
                    toolChoice = "auto",
                    temperature = settings.temperature.toDouble(),
                    maxCompletionTokens = settings.maxTokens.takeIf { it > 0 },
                    reasoningEffort = settings.reasoningEffort.takeIf { it.isNotBlank() },
                )
                val response = try {
                    client.chat(baseRequest)
                } catch (e: LlmException) {
                    val msg = e.message.orEmpty()
                    // Some models (notably Groq's gpt-oss-*) occasionally emit a malformed tool
                    // payload that the provider rejects with HTTP 400. Recover by injecting a
                    // hint nudging the model to be terser, and retry once with the same history.
                    if (msg.startsWith("HTTP 400") && msg.contains("Parsing", ignoreCase = true)) {
                        onLog(AgentLog.Error("Модель сгенерировала некорректный tool-call. Пробую ещё раз с подсказкой быть короче."))
                        messages.add(
                            ChatMessage(
                                role = "system",
                                content = "Your previous response was rejected by the API as malformed. " +
                                    "Reply with a SINGLE short tool call. Do not embed long text or newlines " +
                                    "in tool arguments. Keep `text` arguments under 500 characters and " +
                                    "without literal newline characters.",
                            ),
                        )
                        client.chat(baseRequest.copy(temperature = 0.0))
                    } else {
                        throw e
                    }
                }
                val choice = response.choices.firstOrNull()
                    ?: run {
                        onLog(AgentLog.Error("Пустой ответ от модели"))
                        return
                    }
                val msg = choice.message
                msg.content?.takeIf { it.isNotBlank() }?.let {
                    onLog(AgentLog.Assistant(it))
                }
                messages.add(msg)
                val toolCalls = msg.toolCalls.orEmpty()
                if (toolCalls.isEmpty()) {
                    // Model decided to stop without calling `done`. Treat as completion.
                    onLog(AgentLog.Done(msg.content ?: "(остановлено без вызова инструмента)", success = true))
                    return
                }
                var sawDone = false
                for (call in toolCalls) {
                    val result = executeTool(service, call, lastScreenState)
                    lastScreenState = result.newScreenState ?: lastScreenState
                    onLog(AgentLog.ToolCall(call.function.name, call.function.arguments, result.summary))
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = call.id,
                            name = call.function.name,
                            content = result.toolContent,
                        ),
                    )
                    if (result.done != null) {
                        sawDone = true
                        onLog(AgentLog.Done(result.done.summary, result.done.success))
                    }
                }
                if (sawDone) return
            }
            onLog(AgentLog.Error("Достигнут лимит шагов (${settings.maxSteps}) без завершения."))
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop failed", e)
            onLog(AgentLog.Error(e.message ?: e.toString()))
        } finally {
            client.close()
            returnToApp()
        }
    }

    /** Bring the AI Agent app back to the foreground so the user can see the result log. */
    private fun returnToApp() {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                context.startActivity(launch)
            }
        }
    }

    private suspend fun executeTool(
        service: AgentAccessibilityService,
        call: ToolCall,
        lastScreenState: ScreenState?,
    ): ToolResult {
        val args = parseArgs(call.function.arguments)
        return when (call.function.name) {
            "read_screen" -> {
                val state = service.captureScreenState()
                ToolResult(
                    toolContent = "Foreground app: ${state.packageName}\n${state.description}",
                    summary = "экран считан → ${state.nodes.size} элементов",
                    newScreenState = state,
                )
            }
            "tap" -> {
                val nodeId = args.intOf("node_id") ?: return ToolResult.error("Missing node_id")
                val node = lastScreenState?.nodes?.getOrNull(nodeId)
                    ?: return ToolResult.error("Unknown node_id $nodeId. Call read_screen first.")
                val ok = service.tapNode(node)
                ToolResult(
                    toolContent = if (ok) "Tapped node $nodeId" else "Tap dispatch failed",
                    summary = if (ok) "нажат элемент #$nodeId" else "не удалось нажать элемент #$nodeId",
                )
            }
            "tap_at" -> {
                val x = args.intOf("x") ?: return ToolResult.error("Missing x")
                val y = args.intOf("y") ?: return ToolResult.error("Missing y")
                val ok = service.tap(x, y)
                ToolResult(
                    toolContent = if (ok) "Tapped at ($x,$y)" else "Tap dispatch failed",
                    summary = if (ok) "нажато по координатам ($x, $y)" else "не удалось нажать ($x, $y)",
                )
            }
            "swipe" -> {
                val direction = args.stringOf("direction") ?: return ToolResult.error("Missing direction")
                val distance = args.stringOf("distance") ?: "medium"
                val (x1, y1, x2, y2) = computeSwipe(direction, distance)
                val ok = service.swipe(x1, y1, x2, y2)
                val dirRu = when (direction) {
                    "up" -> "вверх"; "down" -> "вниз"; "left" -> "влево"; "right" -> "вправо"; else -> direction
                }
                val distRu = when (distance) {
                    "short" -> "коротко"; "long" -> "длинно"; else -> "средне"
                }
                ToolResult(
                    toolContent = if (ok) "Swiped $direction" else "Swipe failed",
                    summary = if (ok) "свайп $dirRu, $distRu" else "не удалось свайпнуть $dirRu",
                )
            }
            "swipe_at" -> {
                val x1 = args.intOf("x1") ?: return ToolResult.error("Missing x1")
                val y1 = args.intOf("y1") ?: return ToolResult.error("Missing y1")
                val x2 = args.intOf("x2") ?: return ToolResult.error("Missing x2")
                val y2 = args.intOf("y2") ?: return ToolResult.error("Missing y2")
                val duration = args.intOf("duration_ms")?.toLong() ?: 300L
                val ok = service.swipe(x1, y1, x2, y2, duration)
                ToolResult(
                    toolContent = if (ok) "Swiped ($x1,$y1)→($x2,$y2)" else "Swipe failed",
                    summary = if (ok) "свайп ($x1,$y1) → ($x2,$y2)" else "не удалось свайпнуть",
                )
            }
            "type_text" -> {
                val text = args.stringOf("text") ?: return ToolResult.error("Missing text")
                val nodeId = args.intOf("node_id")
                val ok = if (nodeId != null) {
                    val node = lastScreenState?.nodes?.getOrNull(nodeId)
                        ?: return ToolResult.error("Unknown node_id $nodeId")
                    service.typeTextInNode(node, text)
                } else {
                    service.typeText(text)
                }
                ToolResult(
                    toolContent = if (ok) "Typed '$text'" else "Could not find an editable field",
                    summary = if (ok) "введён текст (${text.length} симв.)" else "нет активного поля ввода",
                )
            }
            "press_back" -> {
                val ok = service.pressBack()
                ToolResult(
                    toolContent = if (ok) "Back pressed" else "Back failed",
                    summary = if (ok) "нажата Назад" else "не удалось нажать Назад",
                )
            }
            "press_home" -> {
                val ok = service.pressHome()
                ToolResult(
                    toolContent = if (ok) "Home pressed" else "Home failed",
                    summary = if (ok) "переход на Главный экран" else "не удалось перейти на Главный экран",
                )
            }
            "press_recents" -> {
                val ok = service.pressRecents()
                ToolResult(
                    toolContent = if (ok) "Recents opened" else "Recents failed",
                    summary = if (ok) "открыты Недавние приложения" else "не удалось открыть Недавние",
                )
            }
            "open_app" -> {
                val pkg = args.stringOf("package_name") ?: return ToolResult.error("Missing package_name")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent == null) {
                    ToolResult.error("App $pkg not installed or no launcher entry.")
                } else {
                    launchIntent.flags = launchIntent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    delay(500)
                    ToolResult(
                        toolContent = "Launched $pkg",
                        summary = "запущено приложение $pkg",
                    )
                }
            }
            "wait" -> {
                val ms = (args.intOf("ms") ?: 500).coerceIn(0, 5000)
                delay(ms.toLong())
                ToolResult(
                    toolContent = "Waited ${ms}ms",
                    summary = "пауза ${ms} мс",
                )
            }
            "ask_user" -> {
                val question = args.stringOf("question") ?: return ToolResult.error("Missing question")
                onLog(AgentLog.AskUser(question))
                val answer = askUser(question)
                ToolResult(
                    toolContent = answer,
                    summary = "вопрос «$question» → «$answer»",
                )
            }
            "start_screen_recording" -> {
                val res = startScreenRecording()
                ToolResult(
                    toolContent = res,
                    summary = "запись экрана: $res",
                )
            }
            "stop_screen_recording" -> {
                val res = stopScreenRecording()
                ToolResult(
                    toolContent = res,
                    summary = res,
                )
            }
            "done" -> {
                val summary = args.stringOf("summary") ?: "(без описания)"
                val success = args.boolOf("success") ?: true
                ToolResult(
                    toolContent = "Acknowledged: $summary",
                    summary = if (success) "завершено: $summary" else "прекращено: $summary",
                    done = DoneSignal(summary, success),
                )
            }
            else -> ToolResult.error("Unknown tool: ${call.function.name}")
        }
    }

    private fun computeSwipe(direction: String, distance: String): IntArray {
        // Use display metrics from the running service window when available.
        val service = AgentAccessibilityService.instance
        val metrics = service?.resources?.displayMetrics
        val w = metrics?.widthPixels ?: 1080
        val h = metrics?.heightPixels ?: 1920
        val dist = when (distance) {
            "short" -> 0.25
            "long" -> 0.75
            else -> 0.5
        }
        val cx = w / 2
        val cy = h / 2
        return when (direction) {
            "up" -> intArrayOf(cx, (h * (0.5 + dist / 2)).toInt(), cx, (h * (0.5 - dist / 2)).toInt())
            "down" -> intArrayOf(cx, (h * (0.5 - dist / 2)).toInt(), cx, (h * (0.5 + dist / 2)).toInt())
            "left" -> intArrayOf((w * (0.5 + dist / 2)).toInt(), cy, (w * (0.5 - dist / 2)).toInt(), cy)
            "right" -> intArrayOf((w * (0.5 - dist / 2)).toInt(), cy, (w * (0.5 + dist / 2)).toInt(), cy)
            else -> intArrayOf(cx, cy, cx, cy)
        }
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun JsonObject.intOf(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.stringOf(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolOf(key: String): Boolean? =
        runCatching { (get(key) as? JsonPrimitive)?.boolean }.getOrNull()

    companion object {
        private const val TAG = "Agent"
        private const val SYSTEM_PROMPT = """You are an AI agent that controls an Android phone via the system Accessibility API on behalf of the user.

You can call tools to inspect the screen and perform UI actions. Always:
1. Start by calling `read_screen` to understand the current state.
2. Decide the next single concrete action and call exactly one tool.
3. After actions that change the UI (tap, type, swipe, open_app, press_back, press_home, press_recents), call `read_screen` again before deciding the next action.
4. When the task is complete (or impossible), call `done` with a concise summary.

Do NOT call `done` prematurely. Specifically:
- If the user asked you to write/type text, you must have successfully called `type_text` AND the next `read_screen` must show that text in the field. Only THEN call `done(success=true)`.
- If the user asked you to find or open something, you must have actually navigated there and `read_screen` must confirm it before calling `done`.
- After what you believe is the final action, ALWAYS run one more `read_screen` to verify the desired state, and only then call `done`.
- If a tool result indicates failure (e.g. "нет активного поля ввода", "не удалось нажать"), recover by tapping the right node first or trying another approach — do NOT just call `done(success=false)` immediately. Try at least 2-3 alternative approaches first.

Rules:
- Prefer `tap` with a node_id from the most recent `read_screen` over `tap_at` coordinates.
- If a field is editable but not yet focused, tap it first, then call `type_text` on the next turn.
- Before calling `type_text(node_id=N)`, verify in `read_screen` that node N has class containing 'Edit' / 'EditText' / 'TextField' or attribute editable=true. If unsure, tap it first and re-read the screen.
- After every `type_text` call, immediately call `read_screen` and confirm the new text is present in the editable field. If it is not, do NOT give up: tap the field first, then retry `type_text`. If still empty after 2 attempts, call `ask_user` to confirm the field selection rather than giving up silently.
- The screen recording tools (`start_screen_recording` / `stop_screen_recording`) record the device screen as an MP4 video. Use them only when the user explicitly asked to "record a guide / video / how-to". The first call pauses for the user to grant Android's MediaProjection consent — that is normal, just wait. Always call `stop_screen_recording` once the demonstration is finished.
- If you need to scroll to find content, use `swipe up` to scroll content downward.
- Be cautious: do not perform destructive actions (deleting data, sending money, mass-messaging) unless the user explicitly asked for them. When in doubt, call `ask_user` with a yes/no question.
- When the user's instruction is ambiguous (which app, which item, which value), call `ask_user` with a short question in the user's language and use their answer; do NOT guess silently.
- Keep textual replies short. Most of your output should be tool calls. When using `type_text`, keep the text reasonable in length (under 1000 characters) and avoid embedded newlines unless absolutely required.
- If you see a permission dialog blocking the task, tap the appropriate button (Allow/While using the app) yourself.
- Reply in the same language the user used in their instruction (so Russian instructions get Russian `done` summaries and Russian `ask_user` questions).
"""
    }
}

sealed class AgentLog {
    data class Thinking(val step: Int) : AgentLog()
    data class Assistant(val text: String) : AgentLog()
    data class ToolCall(val name: String, val arguments: String, val summary: String) : AgentLog()
    data class AskUser(val question: String) : AgentLog()
    data class Done(val summary: String, val success: Boolean) : AgentLog()
    data class Error(val message: String) : AgentLog()
}

private data class ToolResult(
    val toolContent: String,
    val summary: String,
    val newScreenState: ScreenState? = null,
    val done: DoneSignal? = null,
) {
    companion object {
        fun error(message: String) = ToolResult(toolContent = "ERROR: $message", summary = "error: $message")
    }
}

private data class DoneSignal(val summary: String, val success: Boolean)
