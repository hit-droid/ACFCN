// demo.js — 演示脚本沙箱的完整能力（让智能体/用户看清能做什么）

// 1) 调用应用内嵌的大模型做一次推理
var thought = llm.ask("用一句话介绍你是谁。");
log("LLM 回复: " + thought);

// 2) 列出当前已持久化的脚本
var list = scripts.list();
log("已存脚本: " + JSON.stringify(list));

// 3) 用代码"修改自身代码"：写入一个新脚本
scripts.write(
  "generated_by_demo",
  "// 由 demo.js 自动生成\nlog('hi from generated script');\n'ok';\n"
);
log("已写入 generated_by_demo.js");

// 4) 读取刚写入的脚本验证
var back = scripts.read("generated_by_demo");
log("回读长度: " + back.length);

// 5) 列出当前已安装的 dex 插件
log("插件: " + JSON.stringify(plugins.list()));

// 6) 向 UI 推送一个事件（在「智能体」页可见）
ui.notify("toast", JSON.stringify({ msg: "demo 完成演示" }));

thought;
