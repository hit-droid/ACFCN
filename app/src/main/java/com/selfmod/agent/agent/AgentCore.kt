package com.selfmod.agent.agent

import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.LlmClient
import com.selfmod.agent.llm.LlmException
import com.selfmod.agent.offline.LocalModelStore
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.script.ScriptApi
import com.selfmod.agent.script.ScriptEngine
import com.selfmod.agent.store.SettingsStore
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
    private val uiNotifier: (String, String) -> Unit,
    browser: BrowserController,
    models: LocalModelStore,
) {
    private val tools: ToolRegistry = ToolRegistry().apply {
        registerAll(Tools.all(scriptEngine, scriptHost, repo, plugins, settings, uiNotifier, browser, models))
    }

    private val cancelled = AtomicBoolean(false)

    /** Detects the model re-issuing an identical call (H4). */
    private val loopGuard = ToolLoopGuard()

    fun cancel() {
        cancelled.set(true)
        llmClient.cancel()
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
        history.add(ChatMessage("user", userText))
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
}
