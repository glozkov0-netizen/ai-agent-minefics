# AI Agent (Android, Accessibility-driven)

An Android app that runs an AI agent on your phone. The agent observes the screen via the
Android Accessibility API and performs taps, swipes, and text input on your behalf in
response to a natural-language instruction.

The LLM side is OpenAI-compatible (chat completions + tool calling), so you can point it at
OpenAI, OpenRouter, Together, vLLM, llama.cpp's server, or any other compatible endpoint.

> Built for personal automation and demos. Use carefully — the agent has full UI control over
> your device while its accessibility service is enabled.

## Features

- **Accessibility-driven actuation**. `AgentAccessibilityService` walks the live UI tree,
  performs gestures (tap / swipe), types into focused fields, and triggers global actions
  (Back / Home / Recents).
- **Tool-using LLM loop**. The agent exposes a small, well-typed set of tools to the model:
  `read_screen`, `tap`, `tap_at`, `swipe`, `swipe_at`, `type_text`, `press_back`,
  `press_home`, `press_recents`, `open_app`, `wait`, `done`. The loop runs until the model
  calls `done` or `maxSteps` is reached.
- **Pluggable LLM provider**. Configure base URL, API key and model from the in-app
  Settings tab. Default base URL is `https://api.openai.com/v1`.
- **Live action log**. Each step (model thought, tool call, tool result) is shown in the
  app so you can see what the agent is doing.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/aiagent/android/
│   ├── service/AgentAccessibilityService.kt   # UI tree capture + gesture dispatch
│   ├── agent/Agent.kt                         # LLM <-> device loop
│   ├── agent/Tools.kt                         # JSON-schema tool definitions
│   ├── llm/LlmClient.kt                       # OpenAI-compatible HTTP client (Ktor)
│   ├── llm/Models.kt                          # @Serializable request/response types
│   ├── data/Settings.kt                       # SharedPreferences-backed config
│   └── ui/MainActivity.kt + MainViewModel.kt  # Jetpack Compose UI
└── res/
    ├── xml/accessibility_service_config.xml
    └── values/strings.xml, themes.xml, colors.xml
```

## Build

Requirements:

- JDK 17
- Android SDK with `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`

```bash
# point Gradle at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Install and enable

1. Install the APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open the app, go to **Settings** tab, paste your API key (and model / base URL if you
   are not using OpenAI).
3. On the **Agent** tab, tap **Open settings** to launch
   *Android Settings → Accessibility*. Find **AI Agent** and enable it. Confirm the warning
   dialog that explains the service can read the screen and perform actions.
4. Return to the app, type an instruction, and press **Run**.

## How the loop works

```
user instruction
   │
   ▼
[system prompt + user] ──► LLM (with tool schemas)
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
        tool_calls                     final text
              │                              │
              ▼                              ▼
   AgentAccessibilityService             done(summary)
   (read_screen / tap / type / …)
              │
              ▼
  tool result is appended to the conversation
              │
              └──────────────► next LLM turn
```

The model is steered by a short system prompt that tells it to (a) start with
`read_screen`, (b) prefer `tap(node_id)` over coordinates, (c) re-read the screen after any
mutation, and (d) call `done` when the task is finished.

## Safety notes

The Accessibility API gives the service the ability to read sensitive screen content
(passwords, bank apps, messages) and to perform any touch action. **Only use this with an
LLM provider you trust, and disable the service when you are not actively using the
agent.**

## License

MIT
