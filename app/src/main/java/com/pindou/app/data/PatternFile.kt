package com.pindou.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单张图纸分享文件：外层记来源、色板和当时的颜色，内层沿用 PatternStore 的图纸 JSON。
 * 导入一律另存为新图纸，不覆盖本地；颜色差异只提示，不改图纸。
 */
object PatternFile {

    private const val APP_TAG = "douge"
    private const val FORMAT = 1
    private const val KIND = "pattern"
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024

    const val MIME = "application/json"

    data class Parsed(val pattern: Pattern, val colors: Map<String, String>)
    data class Prepared(val pattern: Pattern, val renamed: Boolean)

    sealed interface Outcome {
        data class Success(val pattern: Pattern, val mismatchedColors: Int) : Outcome
        data class Failure(val message: String) : Outcome
    }

    class ImportException(message: String) : Exception(message)

    fun encode(pattern: Pattern, palette: Palette, versionName: String): String {
        val colors = JSONObject()
        for (value in pattern.cells) {
            if (value < 0) continue
            val color = palette.get(value) ?: continue
            if (!colors.has(color.code)) colors.put(color.code, color.hex)
        }
        val root = JSONObject()
            .put("app", APP_TAG)
            .put("format", FORMAT)
            .put("kind", KIND)
            .put("versionName", versionName)
            .put("exportedAt", System.currentTimeMillis())
            .put("paletteId", pattern.paletteId)
            .put("colors", colors)
            .put("pattern", JSONObject(PatternStore.serialize(pattern)))
        return root.toString()
    }

    fun parse(raw: String): Parsed {
        val root = runCatching { JSONObject(raw.removePrefix("\uFEFF")) }.getOrNull()
            ?: throw ImportException("不是有效的图纸文件")
        if (root.optString("app") != APP_TAG || root.optString("kind") != KIND) {
            throw ImportException("不是豆格的图纸文件")
        }
        val format = root.optInt("format", 0)
        if (format != FORMAT) throw ImportException("图纸文件版本不支持（$format）")
        val payload = root.optJSONObject("pattern") ?: throw ImportException("图纸文件缺少内容")
        val pattern = PatternStore.deserialize(payload.toString())
            ?: throw ImportException("图纸内容已损坏，无法导入")
        val paletteId = root.optString("paletteId", pattern.paletteId)
        if (paletteId != pattern.paletteId) throw ImportException("图纸的色板信息不一致")
        if (paletteId != PaletteId.MARD_221 && paletteId != PaletteId.MARD_291) {
            throw ImportException("图纸使用了本地不支持的色板")
        }
        val colors = HashMap<String, String>()
        val colorsJson = root.optJSONObject("colors")
        if (colorsJson != null) {
            val keys = colorsJson.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val hex = colorsJson.optString(code, "")
                if (hex.isNotEmpty()) colors[code] = hex
            }
        }
        return Parsed(pattern, colors)
    }

    /** 本地色板里没有的色号数。外部文件可能带越界下标，导入前必须先挡住。 */
    fun unknownColorCount(pattern: Pattern, palette: Palette): Int {
        val seen = HashSet<Int>()
        var count = 0
        for (value in pattern.cells) {
            if (value < 0 || !seen.add(value)) continue
            if (palette.get(value) == null) count++
        }
        return count
    }

    /** 导出时与本地当前颜色不同的已用色号数，用来提示校准差异。 */
    fun mismatchedColorCount(parsed: Parsed, palette: Palette): Int {
        if (parsed.colors.isEmpty()) return 0
        val used = HashSet<Int>()
        for (value in parsed.pattern.cells) if (value >= 0) used.add(value)
        var count = 0
        for (index in used) {
            val color = palette.get(index) ?: continue
            val fromFile = parsed.colors[color.code] ?: continue
            if (!fromFile.equals(color.hex, ignoreCase = true)) count++
        }
        return count
    }

    /** 导入一律换新编号；重名时加「（导入）」后缀，避免库里两张图分不清。 */
    fun prepareForImport(
        pattern: Pattern,
        usedIds: Set<String>,
        usedNames: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): Prepared {
        var id = Pattern.newId()
        var attempts = 0
        while (id in usedIds) {
            attempts++
            id = if (attempts < 20) {
                Pattern.newId()
            } else {
                "import" + System.currentTimeMillis().toString(36) + attempts
            }
        }
        var name = pattern.name
        if (name in usedNames) {
            val base = pattern.name + "（导入）"
            name = base
            var suffix = 2
            while (name in usedNames) {
                name = "$base $suffix"
                suffix++
            }
        }
        val copy = Pattern(
            id = id,
            name = name,
            width = pattern.width,
            height = pattern.height,
            paletteId = pattern.paletteId,
            cells = pattern.cells,
            createdAt = now,
            updatedAt = now,
            done = pattern.done.toMutableSet(),
        )
        return Prepared(copy, name != pattern.name)
    }

    /** 生成临时分享文件；调用方用 ACTION_SEND 把返回的 URI 发出去。 */
    fun share(context: Context, pattern: Pattern, palette: Palette): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "豆格图纸-${safeBaseName(pattern.name)}-$stamp.json")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        file.writeText(encode(pattern, palette, version))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun readText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("无法读取所选文件")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) throw ImportException("图纸文件过大")
                output.write(buffer, 0, count)
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    }

    internal fun safeBaseName(name: String): String {
        val cleaned = buildString {
            for (ch in name.trim()) {
                append(if (ch in "/\\:*?\"<>|" || ch.code < 0x20) '_' else ch)
            }
        }
        return cleaned.take(40).ifBlank { "图纸" }
    }
}
