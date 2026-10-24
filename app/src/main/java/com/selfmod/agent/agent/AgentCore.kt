package com.selfmod.agent.agent

import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.container.ContainerManager
import com.selfmod.agent.container.PackManager
import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.LlmClient
import com.selfmod.agent.llm.LlmException
import com.selfmod.agent.llm.ToolSpec
import com.selfmod.agent.memory.MemoryService
import com.selfmod.agent.offline.LocalModelStore
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.script.ScriptApi
import com.selfmod.agent.script.ScriptEngine
import com.selfmod.agent.store.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AgentCore(
    private val llmClient: LlmClient,
    private val settings: SettingsStore,
    scriptEngine: ScriptEngine,
    scriptHost: ScriptApi,
    repo: CodeRepository,
    plugins: PluginRegistry,
    private val     uiNotifier: (String, String) -> Unit,
    browser: BrowserController,
    models: LocalModelStore,
    private val container: ContainerManager,
    private val packs: PackManager,
    private val memory: MemoryService,
) {
    private val tools: ToolRegistry = ToolRegistry().apply {
        registerAll(Tools.all(scriptEngine, scriptHost, repo, plugins, settings, uiNotifier, browser, models, container, packs, memory))
    }

    private val cancelled = AtomicBoolean(false)

    /**
     * Gate for high-risk tools (see [ToolRisk]). Default rejects everything
     * (fail-closed): a caller that never configures it cannot silently run
     * code-execution tools. UI hosts inject a gate that suspends until the user
     * decides, then resumes with the verdict.
     */
    var approvalGate: suspend (name: String, args: String) -> Boolean = { _, _ -> false }

    /** Detects the model re-issuing an identical call (H4). */
    private val loopGuard = ToolLoopGuard()

    fun cancel() {
        cancelled.set(true)
        llmClient.cancel()
        container.cancelCurrent()
    }

    fun systemPrompt(): String {
        val cfg = settings.llmConfig()
        if (cfg.kind == com.selfmod.agent.llm.LlmConfig.KIND_ONDEVICE) {
            return PromptTemplates.systemOnDevice(tools.names())
        }
        return PromptTemplates.system(
            tools.names(),
            settings.offlineMode(),
            cfg.isLocalHost() || cfg.kind != com.selfmod.agent.llm.LlmConfig.KIND_CLOUD,
        )
    }

    suspend fun run(
        history: MutableList<ChatMessage>,
        userText: String,
        onStep: (AgentStep) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        cancelled.set(false)
        llmClient.clearAbort()
        loopGuard.reset()
        // P0-1: surface relevant long-term memory + the user profile at the top of
        // the turn so the agent "remembers" across sessions. Query-scoped, so each
        // turn recalls what is actually relevant rather than a fixed blob.
        val memCtx = buildMemoryContext(userText)
        history.add(ChatMessage("user", if (memCtx != null) "$memCtx\n\n$userText" else userText))
        onStep(AgentStep.Started)

        var iteration = 0
        while (iteration++ < MAX_ITERATIONS) {
            if (cancelled.get()) {
                onStep(AgentStep.Error("已停止"))
                return@withContext "已停止"
            }
            val cfg = settings.llmConfig()
            val result = try {
                llmClient.chat(
                    config = cfg,
                    messages = history,
                    tools = tools.specs(),
                    onDelta = { piece -> onStep(AgentStep.StreamDelta(piece)) },
                    cancelled = { cancelled.get() },
                )
            } catch (e: LlmException) {
                if (e.code == 499 || cancelled.get()) {
                    onStep(AgentStep.Error("已停止"))
                    return@withContext "已停止"
                }
                onStep(AgentStep.Error(e.message ?: e.toString()))
                return@withContext "ERROR: ${e.message}"
            } catch (e: Throwable) {
                if (cancelled.get()) {
                    onStep(AgentStep.Error("已停止"))
                    return@withContext "已停止"
                }
                onStep(AgentStep.Error(e.message ?: e.toString()))
                return@withContext "ERROR: ${e.message}"
            }

            val nativeTools = cfg.supportsNativeTools
            history.add(
                ChatMessage(
                    role = "assistant",
                    content = if (nativeTools) result.content else result.content.ifBlank {
                        result.toolCalls.joinToString("\n") { "Action: ${it.function.name}\nAction Input: ${it.function.arguments}" }
                    },
                    toolCalls = if (nativeTools) result.toolCalls else emptyList(),
                )
            )

            if (result.content.isNotBlank()) {
                onStep(AgentStep.Thought(result.content))
            }

            if (result.toolCalls.isEmpty()) {
                onStep(AgentStep.Answer(result.content))
                return@withContext result.content
            }

            // Non-native mode: collect this turn's observations and append them as
            // a single user message. Stacking one user message per tool result
            // breaks the alternating role sequence (H4).
            val observations = ArrayList<Pair<String, String>>()
            var skippedRepeats = 0

            for (tc in result.toolCalls) {
                if (cancelled.get()) {
                    onStep(AgentStep.Error("已停止"))
                    return@withContext "已停止"
                }
                val name = tc.function.name
                val args = tc.function.arguments
                val hits = loopGuard.countOf(name, args)
                if (hits >= MAX_REPEAT_CALLS) {
                    // The model is stuck re-issuing the same call; running it again
                    // only burns context, so feed back a warning instead (H4).
                    val note = "重复调用检测：$name 已用完全相同的参数调用 $hits 次，已跳过执行。请不要重复同样的操作，换一种参数或直接给出答案。"
                    onStep(AgentStep.Observation(note))
                    skippedRepeats++
                    if (nativeTools) {
                        history.add(ChatMessage(role = "tool", content = note, name = name, toolCallId = tc.id))
                    } else {
                        observations.add(name to note)
                    }
                    continue
                }
                if (tools.riskOf(name) == ToolRisk.HIGH) {
                    val approved = approvalGate(name, args)
                    if (!approved) {
                        val note = "工具 $name 属于高危操作，未获授权，已跳过执行。"
                        onStep(AgentStep.Observation(note))
                        if (nativeTools) {
                            history.add(ChatMessage(role = "tool", content = note, name = name, toolCallId = tc.id))
                        } else {
                            observations.add(name to note)
                        }
                        continue
                    }
                }
                onStep(AgentStep.Action(name, args))
                val out = tools.invoke(name, args)
                val trimmed = if (out.length > 6000) out.substring(0, 6000) + "...(truncated)" else out
                onStep(AgentStep.Observation(trimmed))
                if (nativeTools) {
                    history.add(
                        ChatMessage(
                            role = "tool",
                            content = out,
                            name = name,
                            toolCallId = tc.id,
                        )
                    )
                } else {
                    observations.add(name to trimmed)
                }
            }

            if (!nativeTools && observations.isNotEmpty()) {
                history.add(
                    ChatMessage(
                        role = "user",
                        content = ToolLoopGuard.formatObservations(observations),
                    )
                )
            }

            // Nothing actually ran this turn — every call was a repeat. Without
            // stopping we would just spin to MAX_ITERATIONS and waste the context.
            if (skippedRepeats == result.toolCalls.size) {
                val msg = "检测到模型陷入重复调用，已停止。请换一种问法或缩小任务。"
                onStep(AgentStep.Error(msg))
                return@withContext msg
            }
        }
        val msg = "已达到最大推理步数 ($MAX_ITERATIONS)，请缩小任务或换用更具体的指令。"
        onStep(AgentStep.Error(msg))
        msg
    }

    companion object {
        private const val MAX_ITERATIONS = 12

        /** A call repeated this many times (same name+args) is treated as a stuck loop (H4). */
        private const val MAX_REPEAT_CALLS = 3
    }

    /**
     * Builds the memory preamble for a turn: the user's profile (P2-8) plus the
     * most relevant long-term facts for [query]. Returns null when there is nothing
     * to add so the prompt stays clean.
     */
    private fun buildMemoryContext(query: String): String? {
        val profile = memory.userProfile().take(3)
        val facts = memory.recall(query, MemoryService.DEFAULT_K)
        val parts = mutableListOf<String>()
        if (profile.isNotEmpty()) {
            parts += "【用户画像】\n" + profile.joinToString("\n") { "· ${it.text}" }
        }
        if (facts.isNotEmpty()) {
            parts += "【长期记忆】\n" + facts.joinToString("\n") { "· ${it.text}" }
        }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    /**
     * P0-1: fold a finished conversation into a few durable facts. Best-effort and
     * fully suspended — a failure here never affects the answer that was already
     * produced. Skipped when auto-memory is off or the transcript is too short.
     */
    suspend fun distillMemory(history: List<ChatMessage>) {
        if (!settings.autoMemory()) return
        val transcript = history
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(40)
            .joinToString("\n") { "${it.role}: ${it.content.take(1500)}" }
        if (transcript.length < 200) return
        val prompt = buildString {
            append("从下面这段对话里提取 0~5 条值得长期记住的事实（用户偏好、重要约定、踩坑经验、关于用户的信息）。")
            append("只输出一个 JSON 数组，每条是字符串；没有可记的就输出 []。不要输出其他内容。\n\n")
            append(transcript)
        }
        val cfg = settings.llmConfig()
        val out = withContext(Dispatchers.IO) {
            runCatching {
                llmClient.chat(
                    config = cfg,
                    messages = listOf(ChatMessage("user", prompt)),
                    tools = emptyList<ToolSpec>(),
                    onDelta = {},
                    cancelled = { false },
                ).content
            }.getOrNull()
        } ?: return
        parseFacts(out).forEach { fact ->
            runCatching { memory.record(fact, MemoryService.Kind.LESSON) }
        }
    }

    /** Pulls a JSON string array out of model output that may be wrapped in ``` fences. */
    private fun parseFacts(text: String): List<String> {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arrText = runCatching { JSONObject(cleaned) }.getOrNull()
            ?.optJSONArray("facts")
            ?: runCatching { JSONArray(cleaned) }.getOrNull()
            ?: runCatching {
                // Fall back to grabbing the first [...] block if prose leaked in.
                val start = cleaned.indexOf('[')
                val end = cleaned.lastIndexOf(']')
                if (start >= 0 && end > start) JSONArray(cleaned.substring(start, end + 1)) else null
            }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arrText.length()) {
            arrText.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }
}
