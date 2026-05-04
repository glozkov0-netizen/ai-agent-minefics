package com.aiagent.android.agent

import com.aiagent.android.llm.FunctionDef
import com.aiagent.android.llm.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** All tool schemas exposed to the LLM. */
object Tools {

    private val json = Json { encodeDefaults = true }

    fun toolList(): List<Tool> = listOf(
        Tool(function = readScreen),
        Tool(function = tap),
        Tool(function = tapAt),
        Tool(function = swipe),
        Tool(function = swipeAt),
        Tool(function = typeText),
        Tool(function = pressBack),
        Tool(function = pressHome),
        Tool(function = pressRecents),
        Tool(function = openApp),
        Tool(function = waitMs),
        Tool(function = askUser),
        Tool(function = startScreenRecording),
        Tool(function = stopScreenRecording),
        Tool(function = done),
    )

    private val readScreen = FunctionDef(
        name = "read_screen",
        description = "Capture a textual snapshot of the currently visible UI. " +
            "Returns the foreground app package and a numbered list of interactive nodes. " +
            "Always call this once at the start of a task and again after navigation actions.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val tap = FunctionDef(
        name = "tap",
        description = "Tap a UI node by the integer id from the most recent read_screen call.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("node_id") {
                    put("type", "integer")
                    put("description", "Node id from read_screen output")
                }
            }
            put("required", arr("node_id"))
        },
    )

    private val tapAt = FunctionDef(
        name = "tap_at",
        description = "Tap at absolute screen coordinates in pixels. Prefer tap(node_id) when possible.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            put("required", arr("x", "y"))
        },
    )

    private val swipe = FunctionDef(
        name = "swipe",
        description = "Swipe in a cardinal direction (up/down/left/right). " +
            "Use 'up' to scroll content downward (move finger up).",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    put("enum", arr("up", "down", "left", "right"))
                }
                putJsonObject("distance") {
                    put("type", "string")
                    put("enum", arr("short", "medium", "long"))
                    put("description", "Defaults to medium")
                }
            }
            put("required", arr("direction"))
        },
    )

    private val swipeAt = FunctionDef(
        name = "swipe_at",
        description = "Swipe between two specific screen coordinates.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x1") { put("type", "integer") }
                putJsonObject("y1") { put("type", "integer") }
                putJsonObject("x2") { put("type", "integer") }
                putJsonObject("y2") { put("type", "integer") }
                putJsonObject("duration_ms") { put("type", "integer") }
            }
            put("required", arr("x1", "y1", "x2", "y2"))
        },
    )

    private val typeText = FunctionDef(
        name = "type_text",
        description = "Type the given text into an editable field. If node_id is provided, types into that node; " +
            "otherwise types into the currently focused editable field.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("node_id") {
                    put("type", "integer")
                    put("description", "Optional node id of an editable field from read_screen")
                }
            }
            put("required", arr("text"))
        },
    )

    private val pressBack = FunctionDef(
        name = "press_back",
        description = "Press the system Back button.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val pressHome = FunctionDef(
        name = "press_home",
        description = "Go to the home screen.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val pressRecents = FunctionDef(
        name = "press_recents",
        description = "Open the recents/overview screen.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val openApp = FunctionDef(
        name = "open_app",
        description = "Launch an installed app by its package name (e.g. 'com.android.settings').",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("package_name") { put("type", "string") }
            }
            put("required", arr("package_name"))
        },
    )

    private val waitMs = FunctionDef(
        name = "wait",
        description = "Wait for the given number of milliseconds (max 5000) for animations or content to load.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("ms") { put("type", "integer") }
            }
            put("required", arr("ms"))
        },
    )

    private val askUser = FunctionDef(
        name = "ask_user",
        description = "Ask the human user a clarifying question and pause execution until they answer. " +
            "Use this when the task is ambiguous, requires a choice (yes/no, picking an item, " +
            "providing a value the user did not give), or when you need confirmation before a " +
            "potentially destructive action. The user's answer is returned as the tool result. " +
            "Prefer concrete short questions in the user's language.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "The question to show the user, in their language.")
                }
            }
            put("required", arr("question"))
        },
    )

    private val startScreenRecording = FunctionDef(
        name = "start_screen_recording",
        description = "Begin recording the device screen as an MP4 video. The first call in a session " +
            "will pause until the user grants the system MediaProjection consent dialog (this is a " +
            "hard Android requirement; the agent cannot bypass it). Use this when the user asked you " +
            "to record a guide / demo / how-to video. Always pair it with `stop_screen_recording` once " +
            "the demonstration is finished. The result string contains the path to the saved file.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val stopScreenRecording = FunctionDef(
        name = "stop_screen_recording",
        description = "Stop the current screen recording and finalize the MP4 file. Returns the path " +
            "to the saved video. Safe to call even if no recording is in progress.",
        parameters = obj { put("type", "object"); putJsonObject("properties") {} },
    )

    private val done = FunctionDef(
        name = "done",
        description = "Signal that the user's task has been completed (or cannot be completed). " +
            "Provide a short natural-language summary of what was done or why it failed.",
        parameters = obj {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("summary") { put("type", "string") }
                putJsonObject("success") { put("type", "boolean") }
            }
            put("required", arr("summary", "success"))
        },
    )

    private fun obj(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(builder)

    private fun arr(vararg values: String): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })
}
