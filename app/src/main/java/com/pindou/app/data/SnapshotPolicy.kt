package com.pindou.app.data

/**
 * 本地快照的纯策略：何时自动留一份、超出保留份数后删哪些。
 * 快照本身复用整库备份格式，存在 filesDir/snapshots 下。
 */
object SnapshotPolicy {

    const val MAX_SNAPSHOTS = 5
    const val AUTO_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    /**
     * 首次有数据时直接留一份；之后只有数据在上一份快照之后变过、
     * 且上一份已超过 [AUTO_MIN_INTERVAL_MS] 才再留，避免频繁打包。
     */
    fun shouldAutoSnapshot(now: Long, newestAt: Long?, latestChangeAt: Long): Boolean {
        if (latestChangeAt <= 0L) return false
        if (newestAt == null) return true
        if (latestChangeAt <= newestAt) return false
        return now - newestAt >= AUTO_MIN_INTERVAL_MS
    }

    /**
     * [names] 必须按从新到旧排序。保留最新 [keep] 份；
     * [protect] 是正在恢复的来源文件，即使排在保留范围外也不能先删掉。
     */
    fun namesToDelete(
        names: List<String>,
        keep: Int = MAX_SNAPSHOTS,
        protect: String? = null,
    ): List<String> {
        val retained = HashSet<String>()
        names.take(keep.coerceAtLeast(0)).forEach { retained.add(it) }
        if (protect != null) retained.add(protect)
        return names.filterNot { it in retained }
    }
}
