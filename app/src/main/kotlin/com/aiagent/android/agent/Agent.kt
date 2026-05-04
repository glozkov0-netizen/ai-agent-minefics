package com.aiagent.android.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.aiagent.android.audio.MicRecorder
import com.aiagent.android.data.Settings
import com.aiagent.android.device.DeviceInfo
import com.aiagent.android.files.FileTools
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.LlmClient
import com.aiagent.android.llm.LlmException
import com.aiagent.android.llm.ToolCall
import com.aiagent.android.ocr.OcrEngine
import com.aiagent.android.overlay.OverlayService
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenState
import com.aiagent.android.stt.SpeechToText
import com.aiagent.android.tts.TtsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the LLM <-> device interaction loop.
 *
 *  1. Send the user's instruction + system prompt to the LLM with the available tool schemas.
 *  2. The LLM returns one or more tool calls.
 *  3. Each tool call is executed against the AccessibilityService / OS APIs.
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

    // Lazy-initialised heavy components.
    private val tts: TtsManager by lazy { TtsManager(context) }
    private val stt: SpeechToText by lazy { SpeechToText(context, settings) }
    private val fileTools: FileTools by lazy { FileTools(context, settings) }
    private val micRecorder = MicRecorder()

    @Volatile private var lastScreenshotMs: Long = 0L

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
            runCatching { tts.shutdown() }
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
            "read_screen_text" -> {
                val bitmap = throttleAndCapture(service)
                if (bitmap == null) {
                    ToolResult.error("Не удалось получить изображение экрана. На Android < 11 OCR не поддерживается без MediaProjection.")
                } else {
                    val text = try {
                        OcrEngine.extractText(bitmap)
                    } catch (e: Exception) {
                        "[ошибка OCR: ${e.message}]"
                    }
                    ToolResult(
                        toolContent = "Foreground app: ${service.captureScreenState().packageName}\n--- OCR ---\n$text",
                        summary = "OCR: ${text.length} символов",
                    )
                }
            }
            "take_screenshot" -> {
                val bitmap = throttleAndCapture(service)
                if (bitmap == null) {
                    ToolResult.error("Не удалось получить скриншот.")
                } else {
                    val path = saveBitmapToPng(bitmap)
                    ToolResult(
                        toolContent = "Saved screenshot to $path",
                        summary = "сохранён скриншот: $path",
                    )
                }
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
            "ask_user_overlay" -> {
                val question = args.stringOf("question") ?: return ToolResult.error("Missing question")
                onLog(AgentLog.AskUser("[overlay] $question"))
                val deferred = CompletableDeferred<String>()
                OverlayService.Pending.deferred = deferred
                OverlayService.showQuestion(context, question)
                val choice = try {
                    deferred.await()
                } finally {
                    OverlayService.Pending.deferred = null
                    OverlayService.hide(context)
                }
                val answer = if (choice == "open") askUser(question) else choice
                ToolResult(
                    toolContent = answer,
                    summary = "overlay «$question» → «$answer»",
                )
            }
            "speak" -> {
                val text = args.stringOf("text") ?: return ToolResult.error("Missing text")
                val rate = args.floatOf("rate") ?: settings.ttsRate
                val ok = tts.speak(text, rate)
                ToolResult(
                    toolContent = if (ok) "Spoke ${text.length} chars" else "TTS failed",
                    summary = if (ok) "озвучено: «${text.take(40)}»" else "не удалось озвучить",
                )
            }
            "listen" -> {
                val lang = args.stringOf("language")
                val transcript = stt.listenLive(language = lang)
                ToolResult(
                    toolContent = transcript,
                    summary = "услышано: «${transcript.take(80)}»",
                )
            }
            "record_audio" -> {
                val seconds = (args.intOf("seconds") ?: 6).coerceIn(1, 60)
                val lang = args.stringOf("language")
                val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio").apply { mkdirs() }
                val outFile = File(outDir, "rec-${System.currentTimeMillis()}.wav")
                val started = micRecorder.start(outFile, maxMs = seconds * 1000L)
                if (!started) return ToolResult.error("Не удалось начать запись (нет разрешения RECORD_AUDIO?).")
                delay(seconds * 1000L)
                val finalFile = micRecorder.stop()
                if (finalFile == null || !finalFile.exists()) {
                    return ToolResult.error("Запись не удалась.")
                }
                val text = stt.transcribeFile(finalFile, language = lang)
                ToolResult(
                    toolContent = text,
                    summary = "${seconds}с → «${text.take(80)}»",
                )
            }
            "device_info" -> {
                val info = DeviceInfo.gather(context)
                ToolResult(
                    toolContent = info,
                    summary = "device_info (${info.length} симв.)",
                )
            }
            "list_files" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.listEntries(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "list $path")
            }
            "read_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val maxBytes = args.intOf("max_bytes") ?: 65536
                val out = try {
                    fileTools.readText(path, maxBytes)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "read $path (${out.length} симв.)")
            }
            "write_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val content = args.stringOf("content") ?: return ToolResult.error("Missing content")
                val mime = args.stringOf("mime_type") ?: "text/plain"
                val out = try {
                    fileTools.writeText(path, content, mime)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "write $path")
            }
            "make_dir" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.makeDir(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "mkdir $path")
            }
            "delete_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.deletePath(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "rm $path")
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

    /** Capture a screen bitmap, throttled by `Settings.screenFps`. */
    private suspend fun throttleAndCapture(service: AgentAccessibilityService): Bitmap? {
        val fps = settings.screenFps
        if (fps > 0f) {
            val minIntervalMs = (1000f / fps).toLong()
            val sinceLast = System.currentTimeMillis() - lastScreenshotMs
            if (sinceLast < minIntervalMs) {
                delay(minIntervalMs - sinceLast)
            }
        }
        lastScreenshotMs = System.currentTimeMillis()
        return service.captureBitmap()
    }

    private fun saveBitmapToPng(bitmap: Bitmap): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "screenshots").apply { mkdirs() }
        val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".png"
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }

    private fun computeSwipe(direction: String, distance: String): IntArray {
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

    private fun JsonObject.floatOf(key: String): Float? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

    companion object {
        private const val TAG = "Agent"
        private const val SYSTEM_PROMPT = """You are an AI agent that lives on the user's Android phone and helps them — especially during gameplay. You can observe the screen, listen to audio, speak, write files, and control the UI through Accessibility.

You have these tools:

VISION
- read_screen        → list the active app and its interactive UI nodes (use first; fast).
- read_screen_text   → on-device OCR of the current screen pixels (slower; use when read_screen returns nothing useful, e.g. inside games / video players that draw to a SurfaceView).
- take_screenshot    → save a PNG of the current screen to disk and return its path.

ACTUATION
- tap / tap_at / swipe / swipe_at / type_text → interact with the UI.
- press_back / press_home / press_recents     → system navigation.
- open_app(package_name)                      → launch an app by package.
- wait(ms)                                    → pause for animations.

VOICE / AUDIO
- speak(text, rate?)              → say something out loud through the device speaker.
- listen(language?)               → one-shot live mic listen via the platform recogniser.
- record_audio(seconds, language?) → record N seconds of mic audio and transcribe via Whisper.

USER INTERACTION
- ask_user(question)         → ask a free-form question; the answer comes back as text.
- ask_user_overlay(question) → display a 50%-transparent overlay on top of the current app (works during gameplay) with Yes/No/Open/Dismiss buttons. Result: 'yes' / 'no' / 'dismiss' / the user's typed answer.

DEVICE / FILES
- device_info  → model, OS, screen, RAM, battery, network, hardware features.
- list_files(path), read_file(path), write_file(path, content), make_dir(path), delete_file(path)
   Path rules: 'content://...' or 'name/sub/path' relative to one of the user's allowed folders, or — only when 'all-files' mode is enabled in Settings — an absolute path like '/storage/emulated/0/...'.

VIDEO RECORDING
- start_screen_recording / stop_screen_recording → MP4 of the screen via MediaProjection. The first call pauses for the system consent dialog.

DONE
- done(summary, success) → end the loop with a final report.

Workflow rules:
1. For typical UI tasks, START with `read_screen`. If the foreground is a game / SurfaceView, also call `read_screen_text` for OCR.
2. After every UI mutation (tap / type / swipe / open_app / press_*), re-call `read_screen` (and `read_screen_text` for games) BEFORE deciding the next action.
3. Prefer `tap(node_id)` over `tap_at(x,y)` whenever a node id is available.
4. If the user is in a game and asked you to comment / coach: prefer `speak` for short remarks (one sentence), and `ask_user_overlay` for yes/no questions so the game stays in focus.
5. If the user asked you to read out chat or a system message that is rendered in a game / image, use `read_screen_text` to get the text first, then `speak` it.
6. Keep `type_text` payloads under 1000 characters and avoid embedded newlines unless absolutely required.
7. When file writes / deletions are destructive, confirm with `ask_user_overlay` first.
8. When the task is finished, ALWAYS call `done(summary, success)`.
9. Reply in the user's language (default Russian) for user-facing strings (`speak`, `ask_user`, `ask_user_overlay`, `done.summary`).
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

internal data class ToolResult(
    val toolContent: String,
    val summary: String,
    val newScreenState: ScreenState? = null,
    val done: DoneSignal? = null,
) {
    companion object {
        fun error(message: String): ToolResult = ToolResult(
            toolContent = "Error: $message",
            summary = "ошибка: $message",
        )
    }
}

internal data class DoneSignal(val summary: String, val success: Boolean)
