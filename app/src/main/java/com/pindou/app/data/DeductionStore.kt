package com.pindou.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 记录哪些图纸已经扣过豆仓库存，避免重复扣减。
 * 按图纸 id 记，跟图纸内容/施工勾选的编辑无关。
 */
class DeductionStore(private val context: Context) {

    private val file = File(context.filesDir, "inventory_deductions.json")
    private val marks = HashMap<String, Long>()

    init {
        reload()
    }

    fun reload() {
        marks.clear()
        runCatching {
            val root = JSONObject(file.readText())
            for (id in root.keys()) marks[id] = root.optLong(id, 0L)
        }
    }

    fun isDeducted(patternId: String): Boolean = patternId in marks

    fun mark(patternId: String) {
        marks[patternId] = System.currentTimeMillis()
        persist()
    }

    private fun persist() {
        val root = JSONObject()
        for ((id, at) in marks) root.put(id, at)
        runCatching { file.writeText(root.toString()) }
    }
}
