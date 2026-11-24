package com.pindou.app.data

/**
 * 缩略图缓存的启动清扫策略（纯逻辑，可单测）。
 * 缩略图是可再生缓存：删错了最多重画一次，不会丢数据。
 * 文件名约定 `<图纸id>-<尺寸>-<色板>-<颜色指纹>-<cells哈希>.png`，
 * 图纸 id 是 12 位十六进制、不含连字符，所以第一个 `-` 之前就是 id。
 */
object ThumbCleanupPolicy {

    /**
     * [entries] 是 thumbs 目录下的 (文件名, 最后修改时间)，[liveIds] 是当前还存在的图纸 id。
     * 返回要删的文件名：
     * 1. 孤儿——图纸已不存在（或文件名不合约定），整组删；
     * 2. 同一张图纸只留最新一份，旧指纹/旧内容哈希的文件删掉。
     * 时间戳并列时保留文件名字典序最小的，保证结果确定。
     */
    fun namesToDelete(entries: List<Pair<String, Long>>, liveIds: Set<String>): List<String> {
        val keep = HashSet<String>()
        entries.groupBy { it.first.substringBefore('-', "") }.forEach { (id, group) ->
            if (id.isEmpty() || id !in liveIds) return@forEach
            val newest = group
                .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
                .first()
            keep.add(newest.first)
        }
        return entries.map { it.first }.filterNot { it in keep }
    }
}