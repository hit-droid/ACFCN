// hello.js — 默认引导脚本
// 沙箱内全局可用：api, log, toast, http, llm, store, scripts, plugins, ui, now
toast("SelfMod Agent 已就绪");
log("Hello from hello.js!");
log("当前时间戳: " + now());

// 演示持久记忆
var n = store.get("hello_runs") || "0";
n = String(parseInt(n) + 1);
store.set("hello_runs", n);
log("本脚本累计运行 " + n + " 次");

"hello ran #" + n;
