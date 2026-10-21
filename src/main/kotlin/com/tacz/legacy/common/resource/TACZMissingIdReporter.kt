package com.tacz.legacy.common.resource

import com.tacz.legacy.TACZLegacy
import java.util.concurrent.ConcurrentHashMap

/**
 * 兼容层 ③ C2 / D-5：**首次**遇到查不到的枪包 ID 时告警一次。
 *
 * 为什么需要：远程客户端拿到的是服务端下发的数据，一旦同步没生效、某个包没发全、
 * 或者客户端本地资产没重建，表现都是"按 ID 查不到" —— 渲染入口 `return`、音效不播、
 * tooltip 显示成原始 ID。这三类原因在日志上原本**完全同形**（都是静默），
 * 用户报障只能说"枪不见了"，无法区分。
 *
 * 去重的理由：这些调用点在渲染/tick 热路径上，不去重会每秒刷几百行。
 * 同一个 ID 只报第一次，配合 [reset] 在每次枪包重载后重新给一轮。
 *
 * 与上游的关系：上游没有等价物（它假定两端数据一致），这是移植层为可诊断性额外加的。
 */
internal object TACZMissingIdReporter {

    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun report(kind: String, id: Any?, where: String) {
        val key = "$kind|$id|$where"
        if (!reported.add(key)) {
            return
        }
        TACZLegacy.logger.warn(
            "[GunPackRuntime] Missing {} '{}' (needed by {}). Reported once per pack reload; " +
                "cause is one of: the entry is absent from the loaded snapshot, its index omits the " +
                "required display/data field, or (remote client) the pack was not synced.",
            kind, id, where,
        )
    }

    /** 枪包数据换了就重新给一轮告警，否则"上次 reload 缺的 ID"会永久静音。 */
    fun reset() {
        reported.clear()
    }
}
