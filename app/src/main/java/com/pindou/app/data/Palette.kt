package com.pindou.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个 MARD 色号。hex 只作屏幕参考，实物颜色以色卡/校准为准。 */
data class BeadColor(
    val code: String,
    val hex: String,
    val group: String,
) {
    val argb: Int = HexColor.parseArgb(hex)
}

/** 一套色板（MARD 221 基础卡 / MARD 291 完整卡）。colors 的下标就是图纸格子里的存储值。 */
class Palette(
    val id: String,
    val title: String,
    val groups: List<String>,
    val colors: List<BeadColor>,
) {
    val count: Int get() = colors.size

    private val codeIndex: Map<String, Int> = colors.withIndex().associate { (i, c) -> c.code to i }

    fun indexOf(code: String): Int = codeIndex[code] ?: -1

    fun get(index: Int): BeadColor? = colors.getOrNull(index)

    val groupSizes: Map<String, Int> = colors.groupingBy { it.group }.eachCount()

    /** 实际颜色指纹：校准后变化，用来隔离缩略图缓存；颜色不变就复用旧图。 */
    val colorsFingerprint: String by lazy {
        var hash = 0xcbf29ce484222325UL
        for (color in colors) {
            hash = fnv1a(hash, color.code)
            hash = fnv1a(hash, color.hex)
        }
        hash.toString(16)
    }

    fun toJson(): String {
        val colorsJson = JSONArray()
        for (color in colors) {
            colorsJson.put(
                JSONObject()
                    .put("code", color.code)
                    .put("hex", color.hex)
                    .put("g", color.group),
            )
        }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("groups", JSONArray(groups))
            .put("colors", colorsJson)
            .toString()
    }

    companion object {
        const val ASSET_221 = "palette/mard221.json"
        const val ASSET_291 = "palette/mard291.json"
        const val MAX_COLORS = 500

        fun load(context: Context, assetPath: String): Palette {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            return fromJson(text)
        }

        fun fromJson(text: String): Palette {
            val root = JSONObject(text)
            val id = root.getString("id").trim()
            require(PaletteId.isValid(id)) { "色板编号不合法" }
            val title = root.optString("title", "").trim().ifBlank { id }
            val colorsJson = root.optJSONArray("colors")
                ?: throw IllegalArgumentException("色板缺少颜色")
            require(colorsJson.length() > 0) { "色板没有颜色" }
            require(colorsJson.length() <= MAX_COLORS) { "色板最多 $MAX_COLORS 色" }
            val colors = ArrayList<BeadColor>(colorsJson.length())
            val seen = HashSet<String>()
            for (i in 0 until colorsJson.length()) {
                val o = colorsJson.getJSONObject(i)
                val code = o.getString("code").trim().uppercase()
                require(code.isNotEmpty() && seen.add(code)) { "色号重复或为空：$code" }
                val hex = HexColor.normalize(o.getString("hex"))
                    ?: throw IllegalArgumentException("色号 $code 的颜色不合法")
                val group = o.optString("g", "").trim().ifBlank { groupOf(code) }
                colors.add(BeadColor(code, hex, group))
            }
            val groupsJson = root.optJSONArray("groups")
            val groups = ArrayList<String>()
            if (groupsJson != null) {
                for (i in 0 until groupsJson.length()) {
                    val group = groupsJson.optString(i, "").trim()
                    if (group.isNotEmpty() && group !in groups) groups.add(group)
                }
            }
            for (color in colors) if (color.group !in groups) groups.add(color.group)
            return Palette(id, title, groups, colors)
        }

        fun groupOf(code: String): String {
            val prefix = code.takeWhile { it.isLetter() }.uppercase()
            return prefix.ifBlank { "自定义" }
        }
    }
}

private fun fnv1a(hash: ULong, text: String): ULong {
    var h = hash
    for (ch in text) h = (h xor ch.code.toULong()) * 0x100000001b3UL
    return h
}

/**
 * 色系（MARD 色号的首字母）对应的中文名。
 * A~H、M 是行业里通用叫法；291 色里多出来的 P/Q/R/T/Y/ZG 没有官方中文名，
 * 这里是按各组实际颜色的色相/明度特征起的描述性名字，随时可改。
 */
object PaletteGroups {
    private val names = mapOf(
        "A" to "黄",
        "B" to "绿",
        "C" to "蓝",
        "D" to "紫",
        "E" to "粉",
        "F" to "红",
        "G" to "棕",
        "H" to "灰白",
        "M" to "莫兰迪",
        "P" to "杂色",
        "Q" to "荧光",
        "R" to "彩虹",
        "T" to "单色",
        "Y" to "柔彩",
        "ZG" to "珠光",
    )

    fun name(group: String): String = names[group] ?: group

    /** 「黄 A」这种，色系名 + 色号前缀，方便对着色号看。 */
    fun label(group: String): String = name(group) + " " + group
}

object PaletteId {
    const val MARD_221 = "mard221"
    const val MARD_291 = "mard291"

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{4,64}$")

    fun isValid(id: String): Boolean = ID_PATTERN.matches(id)

    fun isBuiltIn(id: String): Boolean = id == MARD_221 || id == MARD_291
}

/**
 * 色板状态：当前使用的色板 + 每个色号的「我有 / 没有」开关 + 颜色校准。
 * 转图纸做最近色映射时只在启用的色号里选，避免出图给出一堆买不到的色号。
 */
class PaletteStore(private val context: Context) {

    private val file = File(context.filesDir, "palette_state.json")
    private val calibrationFile = File(context.filesDir, "palette_calibrated.json")
    private val customDir = File(context.filesDir, "palettes")
    private var state: JSONObject = readState()
    private var calibration: JSONObject = readCalibration()
    private val baseCache = HashMap<String, Palette>()
    private val cache = HashMap<String, Palette>()

    var current: Palette by mutableStateOf(loadPalette(activeId()))
        private set

    var disabled: Set<String> by mutableStateOf(readDisabled(activeId()))
        private set

    /** 自定义色板增删后让色板页重新读取列表。 */
    var revision: Int by mutableStateOf(0)
        private set

    val enabledCount: Int get() = current.count - disabled.size

    fun isEnabled(code: String): Boolean = code !in disabled

    fun palette(id: String): Palette = paletteOrNull(id) ?: loadPalette(PaletteId.MARD_221)

    fun paletteOrNull(id: String): Palette? {
        if (!PaletteId.isValid(id)) return null
        if (!PaletteId.isBuiltIn(id) && !customFile(id).isFile) return null
        return runCatching { loadPalette(id) }.getOrNull()
    }

    fun availablePalettes(): List<Palette> =
        listOf(loadPalette(PaletteId.MARD_221), loadPalette(PaletteId.MARD_291)) + customPalettes()

    fun customPalettes(): List<Palette> {
        val files = customDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            if (!isCustomId(id)) return@mapNotNull null
            runCatching { Palette.fromJson(file.readText()) }
                .getOrNull()
                ?.takeIf { it.id == id }
        }.sortedBy { it.title }
    }

    fun isCustom(id: String): Boolean = isCustomId(id) && customFile(id).isFile

    fun toggle(code: String) {
        disabled = if (code in disabled) disabled - code else disabled + code
        persist()
    }

    fun setGroupEnabled(group: String, enabled: Boolean) {
        val codes = current.colors.filter { it.group == group }.map { it.code }.toSet()
        disabled = if (enabled) disabled - codes else disabled + codes
        persist()
    }

    fun switchPalette(id: String) {
        if (id == current.id) return
        val target = paletteOrNull(id) ?: return
        state.put("active", id)
        current = target
        disabled = readDisabled(id)
        persist()
    }

    fun enabledColors(): List<BeadColor> = current.colors.filter { isEnabled(it.code) }

    fun enabledColors(paletteId: String): List<BeadColor> {
        val target = paletteOrNull(paletteId) ?: current
        val hidden = readDisabled(target.id)
        return target.colors.filter { it.code !in hidden }
    }

    fun importCustomPalette(title: String, colors: List<BeadColor>): Palette {
        require(colors.isNotEmpty()) { "色板没有颜色" }
        require(colors.size <= Palette.MAX_COLORS) { "色板最多 ${Palette.MAX_COLORS} 色" }
        require(colors.map { it.code }.toSet().size == colors.size) { "色号重复" }
        val id = newCustomId()
        val palette = Palette(
            id = id,
            title = title.trim().ifBlank { "自定义色板" }.take(30),
            groups = colors.map { it.group }.distinct(),
            colors = colors,
        )
        val dir = customDir.apply { if (!exists()) mkdirs() }
        File(dir, "$id.json").writeText(palette.toJson())
        baseCache.remove(id)
        cache.remove(id)
        revision++
        return palette
    }

    fun deleteCustomPalette(id: String): Boolean {
        if (!isCustom(id)) return false
        if (!customFile(id).delete()) return false
        baseCache.remove(id)
        cache.remove(id)
        calibration.remove(id)
        persistCalibration()
        state.optJSONObject("disabled")?.remove(id)
        if (current.id == id) {
            state.put("active", PaletteId.MARD_221)
            current = loadPalette(PaletteId.MARD_221)
            disabled = readDisabled(PaletteId.MARD_221)
        }
        persist()
        revision++
        return true
    }

    /** 填 #RRGGBB 保存校准；传 null 恢复原始色值。 */
    fun calibrate(code: String, hex: String?) {
        val id = current.id
        val overrides = calibration.optJSONObject(id)
            ?: JSONObject().also { calibration.put(id, it) }
        if (hex == null) overrides.remove(code) else overrides.put(code, hex)
        cache.remove(id)
        current = loadPalette(id)
        persistCalibration()
    }

    /** 当前色板里改过颜色的色号数；缩略图缓存靠颜色指纹自己失效，不再全量重画。 */
    val calibratedCount: Int
        get() {
            val overrides = calibration.optJSONObject(current.id) ?: return 0
            return current.colors.count { overrides.optString(it.code, "").isNotEmpty() }
        }

    fun originalHex(code: String): String? {
        val base = basePalette(current.id)
        val index = base.indexOf(code)
        return if (index >= 0) base.colors[index].hex else null
    }

    fun calibratedHex(code: String): String? =
        calibration.optJSONObject(current.id)?.optString(code)?.takeIf { it.isNotEmpty() }

    /** 备份恢复后重新读取状态文件；缓存的调色板要一起丢掉，否则校准/自定义色板不生效。 */
    fun reload() {
        state = readState()
        calibration = readCalibration()
        baseCache.clear()
        cache.clear()
        current = loadPalette(activeId())
        disabled = readDisabled(current.id)
        revision++
    }

    private fun activeId(): String {
        val saved = state.optString("active", PaletteId.MARD_221)
        return if (paletteOrNull(saved) != null) saved else PaletteId.MARD_221
    }

    private fun basePalette(id: String): Palette = baseCache.getOrPut(id) {
        when {
            id == PaletteId.MARD_291 -> Palette.load(context, Palette.ASSET_291)
            id == PaletteId.MARD_221 -> Palette.load(context, Palette.ASSET_221)
            else -> {
                val file = customFile(id)
                if (!file.isFile) error("色板不存在")
                Palette.fromJson(file.readText())
            }
        }
    }

    private fun loadPalette(id: String): Palette = cache.getOrPut(id) {
        val base = basePalette(id)
        val overrides = calibration.optJSONObject(id)
        if (overrides == null || overrides.length() == 0) {
            base
        } else {
            Palette(
                base.id,
                base.title,
                base.groups,
                base.colors.map { color ->
                    val hex = overrides.optString(color.code, "")
                    if (hex.isEmpty()) color else color.copy(hex = hex)
                },
            )
        }
    }

    private fun readDisabled(paletteId: String): Set<String> {
        val all = state.optJSONObject("disabled") ?: return emptySet()
        val arr = all.optJSONArray(paletteId) ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    private fun persist() {
        val all = state.optJSONObject("disabled") ?: JSONObject().also { state.put("disabled", it) }
        all.put(current.id, JSONArray(disabled.sorted()))
        runCatching { file.writeText(state.toString()) }
    }

    private fun readState(): JSONObject = runCatching {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }.getOrElse { JSONObject() }

    private fun persistCalibration() {
        runCatching { calibrationFile.writeText(calibration.toString()) }
    }

    private fun readCalibration(): JSONObject = runCatching {
        if (calibrationFile.exists()) JSONObject(calibrationFile.readText()) else JSONObject()
    }.getOrElse { JSONObject() }

    private fun customFile(id: String): File = File(customDir, "$id.json")

    private fun isCustomId(id: String): Boolean = PaletteId.isValid(id) && !PaletteId.isBuiltIn(id)

    private fun newCustomId(): String {
        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val id = "custom-" + System.currentTimeMillis().toString(36) + suffix
            if (!customFile(id).exists()) return id
            attempt++
        }
    }
}
