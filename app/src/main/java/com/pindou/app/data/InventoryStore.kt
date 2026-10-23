package com.pindou.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONObject
import java.io.File

/**
 * 豆仓库存：记每个色号「我手上有多少颗」。
 *
 * 跟色板页的「我有/没有」开关是两件事：
 * - 开关决定出图时能不能用这个色号（能不能买到）；
 * - 库存决定这张图够不够拼、还差多少颗。
 *
 * 没记过的色号返回 null = 不追踪，不算缺口（避免把整张图都算成缺货）。
 *
 * 内部用 SnapshotStateMap 存，读 count() 的 composable 会自动重组，
 * 不需要额外传版本号。
 */
class InventoryStore(private val context: Context) {

    private val file = File(context.filesDir, "inventory.json")

    /** "paletteId|code" -> 颗数 */
    private val counts: SnapshotStateMap<String, Int> = mutableStateMapOf()

    init {
        reload()
    }

    /** 备份恢复后重新读取 inventory.json。 */
    fun reload() {
        counts.clear()
        runCatching {
            val root = JSONObject(file.readText())
            for (paletteId in root.keys()) {
                val bucket = root.optJSONObject(paletteId) ?: continue
                for (code in bucket.keys()) {
                    counts["$paletteId|$code"] = bucket.optInt(code, 0)
                }
            }
        }
    }

    /** null = 没记过（不追踪） */
    fun count(paletteId: String, code: String): Int? = counts["$paletteId|$code"]

    /** 记过库存的色号数量 */
    fun trackedCount(paletteId: String): Int =
        counts.keys.count { it.startsWith("$paletteId|") }

    fun set(paletteId: String, code: String, count: Int?) {
        val key = "$paletteId|$code"
        if (count == null) counts.remove(key) else counts[key] = count.coerceAtLeast(0)
        persist()
    }

    /** 批量导入：一次更新多个色号，只落盘一次。 */
    fun setAll(paletteId: String, values: Map<String, Int>) {
        if (values.isEmpty()) return
        for ((code, count) in values) {
            counts["$paletteId|$code"] = count.coerceAtLeast(0)
        }
        persist()
    }

    private fun persist() {
        val root = JSONObject()
        for ((key, value) in counts) {
            val paletteId = key.substringBefore('|')
            val code = key.substringAfter('|')
            val bucket = root.optJSONObject(paletteId) ?: JSONObject().also { root.put(paletteId, it) }
            bucket.put(code, value)
        }
        runCatching { file.writeText(root.toString()) }
    }
}
