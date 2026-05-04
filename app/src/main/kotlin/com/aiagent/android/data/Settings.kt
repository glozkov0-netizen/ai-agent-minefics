package com.aiagent.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Persistent user-configurable settings for the agent. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API, value) }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value) }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
        set(value) = prefs.edit { putInt(KEY_MAX_STEPS, value) }

    /** Sampling temperature. Stored as a float; 0.0..2.0. */
    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) = prefs.edit { putFloat(KEY_TEMPERATURE, value) }

    /** Maximum completion tokens for a single LLM turn. 0 means "do not send" (use server default). */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit { putInt(KEY_MAX_TOKENS, value) }

    /** Reasoning effort for reasoning-enabled models (e.g. gpt-oss-*). Empty = do not send. */
    var reasoningEffort: String
        get() = prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT) ?: DEFAULT_REASONING_EFFORT
        set(value) = prefs.edit { putString(KEY_REASONING_EFFORT, value) }

    /** System prompt steering the agent. Empty = use built-in default. */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SYSTEM_PROMPT, value) }

    // --- Game-assistant features ---------------------------------------------------------------

    /** Frames-per-second for screen captures used by `take_screenshot` / `read_screen_text`.
     *  Special value 0.0 = "auto" (capture on demand, after each agent action). */
    var screenFps: Float
        get() = prefs.getFloat(KEY_SCREEN_FPS, DEFAULT_SCREEN_FPS)
        set(value) = prefs.edit { putFloat(KEY_SCREEN_FPS, value) }

    /** Where the agent listens by default: `mic` (always works) or `system` (MediaProjection,
     *  requires Android 10+ AND that the source app allows playback capture). */
    var audioSource: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, DEFAULT_AUDIO_SOURCE) ?: DEFAULT_AUDIO_SOURCE
        set(value) = prefs.edit { putString(KEY_AUDIO_SOURCE, value) }

    /** Speech-to-text provider: `groq` (Groq Whisper) or `android` (built-in SpeechRecognizer). */
    var sttProvider: String
        get() = prefs.getString(KEY_STT_PROVIDER, DEFAULT_STT_PROVIDER) ?: DEFAULT_STT_PROVIDER
        set(value) = prefs.edit { putString(KEY_STT_PROVIDER, value) }

    /** TTS engine name. Currently only `android` (built-in TextToSpeech) is implemented. */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, DEFAULT_TTS_ENGINE) ?: DEFAULT_TTS_ENGINE
        set(value) = prefs.edit { putString(KEY_TTS_ENGINE, value) }

    /** Speech rate for TTS. 1.0 = normal. */
    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE)
        set(value) = prefs.edit { putFloat(KEY_TTS_RATE, value) }

    /** File access mode: `saf` (only user-picked folders), `all` (MANAGE_EXTERNAL_STORAGE), or
     *  `app` (only this app's private storage). */
    var fileAccessMode: String
        get() = prefs.getString(KEY_FILE_MODE, DEFAULT_FILE_MODE) ?: DEFAULT_FILE_MODE
        set(value) = prefs.edit { putString(KEY_FILE_MODE, value) }

    /** Set of `content://` SAF tree URIs the user has granted to the agent. */
    var allowedFolders: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_ALLOWED_FOLDERS, value) }

    /** Overlay opacity (0..1). Default 0.5 = 50% as the user requested. */
    var overlayAlpha: Float
        get() = prefs.getFloat(KEY_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
        set(value) = prefs.edit { putFloat(KEY_OVERLAY_ALPHA, value) }

    /**
     * When true, the agent attaches a downscaled screenshot to the next user message every time
     * `read_screen` / `take_screenshot` runs. Requires a vision-capable model
     * (e.g. meta-llama/llama-4-scout-17b-16e-instruct on Groq, gpt-4o on OpenAI).
     * Text-only models will reject the request.
     */
    var sendScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SEND_SCREENSHOTS, DEFAULT_SEND_SCREENSHOTS)
        set(value) = prefs.edit { putBoolean(KEY_SEND_SCREENSHOTS, value) }

    /** Maximum dimension (px) for screenshots sent to the model. Smaller = fewer tokens. */
    var screenshotMaxDim: Int
        get() = prefs.getInt(KEY_SCREENSHOT_MAX_DIM, DEFAULT_SCREENSHOT_MAX_DIM)
        set(value) = prefs.edit { putInt(KEY_SCREENSHOT_MAX_DIM, value) }

    companion object {
        const val PREFS_NAME = "agent_prefs"
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_MAX_STEPS = 20
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_MAX_TOKENS = 2048
        const val DEFAULT_REASONING_EFFORT = "low"

        /** 0.0 = auto (capture-on-demand). Otherwise frames per second. */
        const val DEFAULT_SCREEN_FPS = 0.0f

        const val DEFAULT_AUDIO_SOURCE = "mic"
        const val DEFAULT_STT_PROVIDER = "groq"
        const val DEFAULT_TTS_ENGINE = "android"
        const val DEFAULT_TTS_RATE = 1.0f
        const val DEFAULT_FILE_MODE = "saf"
        const val DEFAULT_OVERLAY_ALPHA = 0.5f
        const val DEFAULT_SEND_SCREENSHOTS = false
        const val DEFAULT_SCREENSHOT_MAX_DIM = 1024

        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        private const val KEY_SCREEN_FPS = "screen_fps"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_FILE_MODE = "file_mode"
        private const val KEY_ALLOWED_FOLDERS = "allowed_folders"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_SEND_SCREENSHOTS = "send_screenshots"
        private const val KEY_SCREENSHOT_MAX_DIM = "screenshot_max_dim"
    }
}
