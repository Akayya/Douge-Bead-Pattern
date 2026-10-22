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
    val argb: Int = android.graphics.Color.parseColor(hex)
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

    companion object {
        const val ASSET_221 = "palette/mard221.json"
        const val ASSET_291 = "palette/mard291.json"

        fun load(context: Context, assetPath: String): Palette {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val groupsJson = root.getJSONArray("groups")
            val groups = ArrayList<String>(groupsJson.length())
            for (i in 0 until groupsJson.length()) groups.add(groupsJson.getString(i))
            val colorsJson = root.getJSONArray("colors")
            val colors = ArrayList<BeadColor>(colorsJson.length())
            for (i in 0 until colorsJson.length()) {
                val o = colorsJson.getJSONObject(i)
                colors.add(BeadColor(o.getString("code"), o.getString("hex"), o.getString("g")))
            }
            return Palette(root.getString("id"), root.getString("title"), groups, colors)
        }
    }
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
}

/**
 * 色板状态：当前使用的色板 + 每个色号的「我有 / 没有」开关 + 颜色校准。
 * 转图纸做最近色映射时只在启用的色号里选，避免出图给出一堆买不到的色号。
 */
class PaletteStore(private val context: Context) {

    private val file = File(context.filesDir, "palette_state.json")
    private val calibrationFile = File(context.filesDir, "palette_calibrated.json")
    private var state: JSONObject = readState()
    private var calibration: JSONObject = readCalibration()
    private val baseCache = HashMap<String, Palette>()
    private val cache = HashMap<String, Palette>()

    var current: Palette by mutableStateOf(loadPalette(activeId()))
        private set

    var disabled: Set<String> by mutableStateOf(readDisabled(activeId()))
        private set

    val enabledCount: Int get() = current.count - disabled.size

    fun isEnabled(code: String): Boolean = code !in disabled

    fun palette(id: String): Palette = loadPalette(id)

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
        state.put("active", id)
        current = loadPalette(id)
        disabled = readDisabled(id)
        persist()
    }

    fun enabledColors(): List<BeadColor> = current.colors.filter { isEnabled(it.code) }

    /** 填 #RRGGBB 保存校准；传 null 恢复原始色值。 */
    fun calibrate(code: String, hex: String?) {
        val id = current.id
        val overrides = calibration.optJSONObject(id)
            ?: JSONObject().also { calibration.put(id, it) }
        if (hex == null) overrides.remove(code) else overrides.put(code, hex)
        cache.remove(id)
        current = loadPalette(id)
        PatternThumbCache.clear(context)
        persistCalibration()
    }

    fun originalHex(code: String): String? {
        val base = basePalette(current.id)
        val index = base.indexOf(code)
        return if (index >= 0) base.colors[index].hex else null
    }

    fun calibratedHex(code: String): String? =
        calibration.optJSONObject(current.id)?.optString(code)?.takeIf { it.isNotEmpty() }

    private fun activeId(): String = state.optString("active", PaletteId.MARD_221)

    private fun basePalette(id: String): Palette = baseCache.getOrPut(id) {
        when (id) {
            PaletteId.MARD_291 -> Palette.load(context, Palette.ASSET_291)
            else -> Palette.load(context, Palette.ASSET_221)
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

    companion object {
        private val HEX = Regex("[0-9a-fA-F]{6}")

        fun normalizeHex(raw: String): String? {
            val body = raw.trim().removePrefix("#")
            return if (HEX.matches(body)) "#${body.uppercase()}" else null
        }
    }
}
