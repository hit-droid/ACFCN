package com.selfmod.agent.llm

/** OpenAI-style chat message types. */
data class ChatMessage(
    val role: String,            // system | user | assistant | tool
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    fun toUser() = copy(role = "user")
    fun toAssistant() = copy(role = "assistant")
}

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction,
)

data class ToolFunction(
    val name: String,
    val arguments: String,       // raw JSON string
)

/** A function-tool exposed to the LLM. parameters is a JSON schema string. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String,
)

/** Parsed chat completion result. */
data class ChatResult(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val raw: String,
    val finishReason: String = "stop",
)
