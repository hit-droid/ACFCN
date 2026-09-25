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

    fun cancel() {
        cancelled.set(true)
    }

    fun systemPrompt(): String {
        val cfg = settings.llmConfig()
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

            for (tc in result.toolCalls) {
                if (cancelled.get()) {
                    onStep(AgentStep.Error("已停止"))
                    return@withContext "已停止"
                }
                val name = tc.function.name
                val args = tc.function.arguments
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
                    history.add(
                        ChatMessage(
                            role = "user",
                            content = "Observation from $name:\n$trimmed\n\nContinue. If the task is done, answer without an Action.",
                        )
                    )
                }
            }
        }
        val msg = "已达到最大推理步数 ($MAX_ITERATIONS)，请缩小任务或换用更具体的指令。"
        onStep(AgentStep.Error(msg))
        msg
    }

    companion object {
        private const val MAX_ITERATIONS = 12
    }
}
