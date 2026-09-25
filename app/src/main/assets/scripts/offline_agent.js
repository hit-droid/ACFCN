// offline_agent.js — 离线智能体自检
log("offline check");
log("scripts: " + JSON.stringify(scripts.list()));
log("plugins: " + JSON.stringify(plugins.list()));
var n = store.get("offline_runs") || "0";
n = String(parseInt(n) + 1);
store.set("offline_runs", n);
log("offline_runs=" + n);
toast("离线脚本 OK #" + n);
"offline ok #" + n;