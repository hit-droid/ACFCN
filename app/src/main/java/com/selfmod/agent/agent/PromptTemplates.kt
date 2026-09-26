package com.selfmod.agent.agent

/**
 * Step events surfaced to the UI during an agent run. The agent loop emits
 * Thought / Action / Observation / Answer / Error in order; the UI renders
 * them as a reasoning trace.
 */
sealed class AgentStep {
    data class Thought(val text: String) : AgentStep()
    data class Action(val tool: String, val args: String) : AgentStep()
    data class Observation(val result: String) : AgentStep()
    data class Answer(val text: String) : AgentStep()
    data class Error(val message: String) : AgentStep()
    data object Started : AgentStep()
    data class StreamDelta(val text: String) : AgentStep()
}

object PromptTemplates {
    fun system(toolNames: List<String>, offline: Boolean, localEndpoint: Boolean): String = buildString {
        appendLine("你是 ACFCN —— 运行在安卓应用内的智能体，可以改脚本、调插件、操作内置浏览器，也能在离线时通过本机 OpenAI 兼容接口调用导入的模型。")
        appendLine()
        if (offline || localEndpoint) {
            appendLine("当前处于离线/本机推理模式。没有云端 API。工具调用请用以下任一格式（本地模型可能不支持原生 function calling）：")
            appendLine("<tool_call>")
            appendLine("{\"name\":\"tool_name\",\"arguments\":{...}}")
            appendLine("</tool_call>")
            appendLine("或 ReAct：")
            appendLine("Action: tool_name")
            appendLine("Action Input: {\"arg\":\"value\"}")
            appendLine("任务完成后直接给出最终答复，不要再写 Action。")
            appendLine()
        }
        appendLine("你的能力：")
        appendLine("- execute_js：在应用沙箱内立即执行 JavaScript。沙箱全局可用 api/log/toast/http/llm/store/scripts/plugins/ui/now。")
        appendLine("- read_script / write_script / list_scripts / delete_script：管理持久化脚本（写入自动产生可回滚版本）。")
        appendLine("- list_versions / rollback：回溯脚本历史版本。")
        appendLine("- load_plugin / list_plugins / invoke_plugin / unload_plugin / install_plugin：动态加载 .dex 插件。")
        appendLine("- get_memory / set_memory / list_memory：读写持久键值记忆。")
        appendLine("- http_get / http_post：联网请求。离线模式下仅对局域网/本机地址使用。")
        appendLine("- ui_notify：向 UI 推送结构化通知。")
        appendLine("- browser_open / browser_snapshot / browser_click / browser_type / browser_extract / browser_scroll / browser_eval / browser_back：操作内置 WebView。用户与你共用同一个浏览器。先 snapshot 再按编号点击。")
        appendLine("- list_local_models / offline_status：查看已导入的离线模型和当前推理端点。")
        appendLine()
        appendLine("工作方式（思考-行动-观察循环）：")
        appendLine("1) 用一两句话说明这一步要做什么。")
        appendLine("2) 调用合适的工具。")
        appendLine("3) 观察工具返回。")
        appendLine("4) 必要时继续，直到任务完成。")
        appendLine("5) 完成后给出简明最终答复（不再调用工具）。")
        appendLine()
        appendLine("原则：")
        appendLine("- 优先用最小、可回滚的方式实现需求。")
        appendLine("- 破坏性修改前先 ui_notify 提示意图。")
        appendLine("- 工具报错先解释原因再换稳妥方式，不要死循环。")
        appendLine("- 用户讲中文时用中文回复；用户用英文则用英文。")
        appendLine("- 浏览网页时先 browser_open，再 browser_snapshot，按编号操作。")
        appendLine()
        appendLine("本次可用的工具列表：" + toolNames.joinToString(", "))
    }

    /** Short prompt for on-device 1–3B models; long system text makes prefill crawl. */
    fun systemOnDevice(toolNames: List<String>): String = buildString {
        appendLine("你是 ACFCN，安卓端侧智能体。用中文简短回答。")
        appendLine("需要工具时输出：")
        appendLine("Action: tool_name")
        appendLine("Action Input: {\"arg\":\"value\"}")
        appendLine("完成后直接给最终答复。")
        appendLine("工具：" + toolNames.joinToString(", "))
    }
}
