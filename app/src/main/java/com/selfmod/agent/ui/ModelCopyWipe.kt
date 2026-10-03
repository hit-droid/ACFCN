package com.selfmod.agent.ui

/**
 * L9: the decision behind "删除模型副本" — what to refuse, and what to tell the
 * user afterwards. Pure so the rules are testable without an Application, a native
 * engine or a coroutine dispatcher.
 */
internal object ModelCopyWipe {

    /** Reason the wipe must not start, or null when it is safe to go ahead.
     * Checked in this order: a half-finished copy is worse than a stopped run. */
    fun refusal(engineBusy: Boolean, generating: Boolean): String? = when {
        engineBusy -> "端侧模型正在加载，请等待完成后再删除副本"
        generating -> "智能体正在使用端侧模型，请先停止对话再删除副本"
        else -> null
    }

    /** Shown while the delete runs on the IO thread. */
    const val IN_PROGRESS = "正在删除模型副本并卸载端侧引擎…"

    /** Engine status line after the wipe: must not keep claiming a loaded model. */
    const val AFTER_ENGINE = "模型副本已删除，端侧引擎已卸载。需要时重新点「在本机加载」即可。"

    /**
     * @param freed bytes the wipe actually reclaimed.
     * @param copied registry entries that had a real copy in app storage, counted
     *   *before* the delete — afterwards every entry looks copy-less, including the
     *   ones that read straight from their original file path.
     */
    fun message(freed: Long, copied: Int): String {
        val human = com.selfmod.agent.util.StorageStats.human(freed)
        return if (copied > 0) {
            "已删除模型副本，释放 $human（$copied 个模型的注册表保留，重新加载会再次复制文件）"
        } else {
            "已删除模型副本，释放 $human"
        }
    }

    fun failure(reason: String?): String = "删除模型副本失败：$reason"
}
