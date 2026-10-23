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
    private const val MAX_CELLS = 4_000_000
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{4,64}$")

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

    /** 解析外部来源（分享文件/备份）里的单张图纸；格式不合法返回 null。 */
    fun deserialize(text: String): Pattern? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = root.optString("id", "")
        if (!ID_PATTERN.matches(id)) return null
        val width = root.optInt("w", 0)
        val height = root.optInt("h", 0)
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height > MAX_CELLS) return null
        val cellsJson = root.optJSONArray("cells") ?: return null
        if (cellsJson.length() != width * height) return null
        val cells = IntArray(width * height)
        for (i in cells.indices) {
            val value = cellsJson.optInt(i, -1)
            if (value < -1 || value > 4096) return null
            cells[i] = value
        }
        val done = HashSet<Int>()
        val doneJson = root.optJSONArray("done")
        if (doneJson != null) {
            for (i in 0 until doneJson.length()) {
                val value = doneJson.optInt(i, -1)
                if (value in cells.indices && cells[value] >= 0) done.add(value)
            }
        }
        val createdAt = root.optLong("createdAt", System.currentTimeMillis())
        val paletteId = when (root.optString("paletteId", PaletteId.MARD_221)) {
            PaletteId.MARD_291 -> PaletteId.MARD_291
            else -> PaletteId.MARD_221
        }
        return Pattern(
            id = id,
            name = root.optString("name", "").ifBlank { "未命名图纸" },
            width = width,
            height = height,
            paletteId = paletteId,
            cells = cells,
            createdAt = createdAt,
            updatedAt = root.optLong("updatedAt", createdAt),
            done = done,
        )
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
