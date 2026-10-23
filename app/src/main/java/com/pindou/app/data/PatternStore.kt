package com.pindou.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 图纸存储：每张图纸一个 JSON 文件，放在 filesDir/patterns 下。
 * 不引 Room / 序列化库，纯文件 + org.json，够用且没有额外依赖。
 */
object PatternStore {

    private const val VERSION = 1

    private fun dir(context: Context): File =
        File(context.filesDir, "patterns").apply { if (!exists()) mkdirs() }

    private fun fileOf(context: Context, id: String): File = File(dir(context), "$id.json")

    fun list(context: Context): List<Pattern> {
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    fun load(context: Context, id: String): Pattern? =
        fileOf(context, id).takeIf { it.exists() }?.let { runCatching { read(it) }.getOrNull() }

    fun save(context: Context, pattern: Pattern) {
        fileOf(context, pattern.id).writeText(serialize(pattern))
    }

    /** 备份导入时先写到临时目录，校验全部通过后再整体替换。 */
    fun serialize(pattern: Pattern): String {
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("id", pattern.id)
        root.put("name", pattern.name)
        root.put("w", pattern.width)
        root.put("h", pattern.height)
        root.put("paletteId", pattern.paletteId)
        root.put("createdAt", pattern.createdAt)
        root.put("updatedAt", pattern.updatedAt)
        root.put("cells", JSONArray(pattern.cells.toList()))
        root.put("done", JSONArray(pattern.done.sorted()))
        return root.toString()
    }

    fun delete(context: Context, id: String): Boolean = fileOf(context, id).delete()

    fun rename(context: Context, id: String, name: String): Pattern? {
        val p = load(context, id) ?: return null
        val copy = Pattern(
            id = p.id, name = name, width = p.width, height = p.height, paletteId = p.paletteId,
            cells = p.cells, createdAt = p.createdAt, updatedAt = System.currentTimeMillis(),
            done = p.done.toMutableSet(),
        )
        save(context, copy)
        return copy
    }

    private fun read(file: File): Pattern {
        val root = JSONObject(file.readText())
        val w = root.getInt("w")
        val h = root.getInt("h")
        val cellsArr = root.getJSONArray("cells")
        val cells = IntArray(cellsArr.length()) { cellsArr.getInt(it) }
        val doneArr = root.optJSONArray("done") ?: JSONArray()
        val done = HashSet<Int>(doneArr.length())
        for (i in 0 until doneArr.length()) done.add(doneArr.getInt(i))
        return Pattern(
            id = root.getString("id"),
            name = root.getString("name"),
            width = w,
            height = h,
            paletteId = root.optString("paletteId", PaletteId.MARD_221),
            cells = cells,
            createdAt = root.optLong("createdAt", file.lastModified()),
            updatedAt = root.optLong("updatedAt", file.lastModified()),
            done = done,
        )
    }
}
