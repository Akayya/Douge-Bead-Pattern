package com.pindou.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整库备份：图纸 + 施工进度 + 色板开关/校准 + 豆仓库存 + 成本设置，打成一个 zip。
 *
 * 导入分两种：
 * - 合并：保留现有数据；图纸编号撞了就另存为副本，库存/校准只补本地没记过的项；
 * - 覆盖：先完整校验备份，再把图纸目录整体换掉，最后替换设置文件。
 *
 * 备份里不包含缩略图缓存，恢复后按需重建。
 */
object BackupManager {

    private const val APP_TAG = "douge"
    private const val FORMAT = 2
    private const val MIN_FORMAT = 1
    private const val MANIFEST = "manifest.json"
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private const val MAX_ENTRIES = 5000
    private val PATTERN_NAME = Regex("^patterns/([A-Za-z0-9_-]{4,64})\\.json$")
    private val PALETTE_NAME = Regex("^palettes/([A-Za-z0-9_-]{4,64})\\.json$")
    private val SETTINGS = listOf(
        "palette_state.json",
        "palette_calibrated.json",
        "inventory.json",
        "inventory_deductions.json",
        "cost.json",
    )

    private data class PaletteEntry(val id: String, val palette: Palette, val data: ByteArray)

    data class Info(
        val createdAt: Long,
        val appVersion: String,
        val patternCount: Int,
        val paletteCount: Int = 0,
    )
    data class ExportResult(val patternCount: Int)
    data class ImportResult(val added: Int, val copies: Int, val overwrite: Boolean)

    class BackupException(message: String) : Exception(message)

    fun defaultFileName(): String =
        "豆格备份-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".zip"

    fun export(context: Context, uri: Uri): ExportResult {
        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: throw BackupException("无法写入所选位置")
        return output.use { exportTo(context, it) }
    }

    /** 写入 zip；返回时会关闭 output。 */
    fun exportTo(context: Context, output: OutputStream): ExportResult {
        val patterns = PatternStore.list(context)
        val palettes = readCustomPalettes(context)
        ensurePatternPalettes(context, patterns, palettes.associate { it.id to it.palette })
        val patternFiles = patterns.mapNotNull { pattern ->
            val file = File(File(context.filesDir, "patterns"), "${pattern.id}.json")
            runCatching { pattern.id to file.readBytes() }.getOrNull()
        }
        val manifest = JSONObject()
            .put("app", APP_TAG)
            .put("format", FORMAT)
            .put("versionName", appVersion(context))
            .put("createdAt", System.currentTimeMillis())
            .put("patternCount", patternFiles.size)
            .put("paletteCount", palettes.size)

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            writeEntry(zip, MANIFEST, manifest.toString(2).toByteArray(Charsets.UTF_8))
            for ((id, data) in patternFiles) {
                writeEntry(zip, "patterns/$id.json", data)
            }
            for (entry in palettes) {
                writeEntry(zip, "palettes/${entry.id}.json", entry.data)
            }
            for (file in settingsFiles(context)) {
                if (file.exists()) writeEntry(zip, file.name, file.readBytes())
            }
        }
        return ExportResult(patternFiles.size)
    }

    fun settingsFiles(context: Context): List<File> = SETTINGS.map { File(context.filesDir, it) }

    fun inspect(context: Context, uri: Uri): Info {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupException("无法读取所选文件")
        return input.use { inspectFrom(it) }
    }

    fun inspectFrom(input: InputStream): Info {
        val entries = readEntries(input)
        val manifest = verifyManifest(entries)
        return Info(
            createdAt = manifest.optLong("createdAt", 0L),
            appVersion = manifest.optString("versionName", "?"),
            patternCount = entries.keys.count { PATTERN_NAME.matches(it) },
            paletteCount = entries.keys.count { PALETTE_NAME.matches(it) },
        )
    }

    /** 只读清单，用于快速列出本地快照；不校验整包。 */
    fun inspectHeader(file: File): Info {
        val manifest = file.inputStream().use { readManifest(it) }
        verifyManifestValues(manifest)
        return Info(
            createdAt = manifest.optLong("createdAt", 0L),
            appVersion = manifest.optString("versionName", "?"),
            patternCount = manifest.optInt("patternCount", 0),
            paletteCount = manifest.optInt("paletteCount", 0),
        )
    }

    fun import(context: Context, uri: Uri, overwrite: Boolean): ImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupException("无法读取所选文件")
        return input.use { importFrom(context, it, overwrite) }
    }

    fun importFile(context: Context, file: File, overwrite: Boolean): ImportResult =
        file.inputStream().use { importFrom(context, it, overwrite) }

    fun importFrom(context: Context, input: InputStream, overwrite: Boolean): ImportResult {
        val entries = readEntries(input)
        verifyManifest(entries)

        val patterns = ArrayList<Pattern>()
        val ids = HashSet<String>()
        for ((name, data) in entries) {
            val match = PATTERN_NAME.matchEntire(name) ?: continue
            val expectedId = match.groupValues[1]
            val pattern = PatternStore.deserialize(String(data, Charsets.UTF_8))
                ?: throw BackupException("备份里的图纸「$expectedId」已损坏，未做任何修改")
            if (pattern.id != expectedId) {
                throw BackupException("备份里的图纸编号不一致，未做任何修改")
            }
            if (!ids.add(pattern.id)) {
                throw BackupException("备份里有重复编号的图纸，未做任何修改")
            }
            patterns.add(pattern)
        }

        val settings = entries.filterKeys { it in SETTINGS }
        for ((name, data) in settings) {
            runCatching { JSONObject(String(data, Charsets.UTF_8)) }
                .getOrElse { throw BackupException("备份里的 $name 已损坏，未做任何修改") }
        }

        val palettes = parsePalettes(entries)
        ensurePatternPalettes(context, patterns, palettes.mapValues { it.value.palette })

        return if (overwrite) restoreAll(context, patterns, settings, palettes)
        else merge(context, patterns, settings, palettes)
    }

    private fun merge(
        context: Context,
        incoming: List<Pattern>,
        settings: Map<String, ByteArray>,
        palettes: Map<String, PaletteEntry>,
    ): ImportResult {
        mergePalettes(context, palettes)
        val dir = File(context.filesDir, "patterns").apply { if (!exists()) mkdirs() }
        val used = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { it.name.removeSuffix(".json").takeIf { id -> id.isNotEmpty() } }
            ?.toMutableSet()
            ?: mutableSetOf()
        var copies = 0
        for (pattern in incoming) {
            var id = pattern.id
            var name = pattern.name
            if (id in used) {
                id = generateId(used)
                name = pattern.name + "（导入）"
                copies++
            }
            used.add(id)
            val item = if (id == pattern.id) pattern else Pattern(
                id = id,
                name = name,
                width = pattern.width,
                height = pattern.height,
                paletteId = pattern.paletteId,
                cells = pattern.cells,
                createdAt = pattern.createdAt,
                updatedAt = pattern.updatedAt,
                done = pattern.done.toMutableSet(),
            )
            PatternStore.save(context, item)
            PatternThumbCache.delete(context, id)
        }
        mergeSettings(context, settings)
        return ImportResult(incoming.size, copies, overwrite = false)
    }

    private fun restoreAll(
        context: Context,
        incoming: List<Pattern>,
        settings: Map<String, ByteArray>,
        palettes: Map<String, PaletteEntry>,
    ): ImportResult {
        val root = context.filesDir
        val staging = File(root, "patterns-importing")
        val paletteStaging = File(root, "palettes-importing")
        staging.deleteRecursively()
        paletteStaging.deleteRecursively()
        if (!staging.mkdirs() || !paletteStaging.mkdirs()) {
            staging.deleteRecursively()
            paletteStaging.deleteRecursively()
            throw BackupException("无法写入应用存储")
        }
        try {
            for (pattern in incoming) {
                File(staging, "${pattern.id}.json").writeText(PatternStore.serialize(pattern))
            }
            for (entry in palettes.values) {
                File(paletteStaging, "${entry.id}.json").writeBytes(entry.data)
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            paletteStaging.deleteRecursively()
            throw BackupException("写入备份失败：${e.message ?: "存储空间不足"}")
        }

        val target = File(root, "patterns")
        val previous = File(root, "patterns-before-import")
        val paletteTarget = File(root, "palettes")
        val palettePrevious = File(root, "palettes-before-import")
        previous.deleteRecursively()
        palettePrevious.deleteRecursively()
        val hadPatterns = target.exists()
        val hadPalettes = paletteTarget.exists()
        if (hadPatterns && !target.renameTo(previous)) {
            staging.deleteRecursively()
            paletteStaging.deleteRecursively()
            throw BackupException("无法替换当前图纸目录")
        }
        if (hadPalettes && !paletteTarget.renameTo(palettePrevious)) {
            if (hadPatterns) previous.renameTo(target)
            staging.deleteRecursively()
            paletteStaging.deleteRecursively()
            throw BackupException("无法替换当前色板目录")
        }
        val patternsSwapped = staging.renameTo(target)
        val palettesSwapped = patternsSwapped && paletteStaging.renameTo(paletteTarget)
        if (!patternsSwapped || !palettesSwapped) {
            if (patternsSwapped) target.renameTo(staging)
            if (palettesSwapped) paletteTarget.renameTo(paletteStaging)
            if (hadPatterns) previous.renameTo(target)
            if (hadPalettes) palettePrevious.renameTo(paletteTarget)
            staging.deleteRecursively()
            paletteStaging.deleteRecursively()
            throw BackupException("无法替换当前数据目录")
        }
        previous.deleteRecursively()
        palettePrevious.deleteRecursively()

        for (name in SETTINGS) {
            val file = File(root, name)
            val data = settings[name]
            if (data == null) file.delete() else file.writeBytes(data)
        }
        PatternThumbCache.clearAll(context)
        return ImportResult(incoming.size, copies = 0, overwrite = true)
    }

    /** 合并色板：编号同一律按本地内容为准；内容不同则整包拒绝，避免图纸颜色错位。 */
    private fun mergePalettes(context: Context, incoming: Map<String, PaletteEntry>) {
        if (incoming.isEmpty()) return
        val dir = File(context.filesDir, "palettes").apply { if (!exists()) mkdirs() }
        for ((id, entry) in incoming) {
            val file = File(dir, "$id.json")
            if (!file.exists()) continue
            val local = runCatching { Palette.fromJson(file.readText()) }.getOrElse {
                throw BackupException("本地自定义色板「$id」已损坏，未做任何修改")
            }
            if (local.colors != entry.palette.colors) {
                throw BackupException("本地已有内容不同的色板「$id」，未做任何修改")
            }
        }
        try {
            for ((id, entry) in incoming) {
                val file = File(dir, "$id.json")
                if (!file.exists()) file.writeBytes(entry.data)
            }
        } catch (e: Exception) {
            throw BackupException("写入自定义色板失败：${e.message ?: "存储空间不足"}")
        }
    }

    private fun mergeSettings(context: Context, settings: Map<String, ByteArray>) {
        val root = context.filesDir
        settings["palette_state.json"]?.let { data ->
            val file = File(root, "palette_state.json")
            if (!file.exists()) file.writeBytes(data)
        }
        settings["cost.json"]?.let { data ->
            val file = File(root, "cost.json")
            if (!file.exists()) file.writeBytes(data)
        }
        settings["palette_calibrated.json"]?.let { data ->
            val file = File(root, "palette_calibrated.json")
            val local = readJson(file)
            mergeMissing(local, JSONObject(String(data, Charsets.UTF_8)))
            file.writeText(local.toString())
        }
        settings["inventory.json"]?.let { data ->
            val file = File(root, "inventory.json")
            val local = readJson(file)
            mergeMissing(local, JSONObject(String(data, Charsets.UTF_8)))
            file.writeText(local.toString())
        }
    }

    /** 只补本地没有的色号，已有记录一律不覆盖。 */
    private fun mergeMissing(target: JSONObject, incoming: JSONObject) {
        val palettes = incoming.keys()
        while (palettes.hasNext()) {
            val paletteId = palettes.next()
            val source = incoming.optJSONObject(paletteId) ?: continue
            val bucket = target.optJSONObject(paletteId)
                ?: JSONObject().also { target.put(paletteId, it) }
            val codes = source.keys()
            while (codes.hasNext()) {
                val code = codes.next()
                if (!bucket.has(code)) source.opt(code)?.let { bucket.put(code, it) }
            }
        }
    }

    private fun readCustomPalettes(context: Context): List<PaletteEntry> {
        val files = File(context.filesDir, "palettes")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        val out = ArrayList<PaletteEntry>(files.size)
        for (file in files.sortedBy { it.name }) {
            val id = file.name.removeSuffix(".json")
            val palette = runCatching { Palette.fromJson(file.readText()) }.getOrElse {
                throw BackupException("本机自定义色板「$id」已损坏，无法备份")
            }
            if (!PaletteId.isValid(id) || PaletteId.isBuiltIn(id) || palette.id != id) {
                throw BackupException("本机自定义色板「$id」编号异常，无法备份")
            }
            val data = runCatching { file.readBytes() }.getOrElse {
                throw BackupException("读取自定义色板「$id」失败")
            }
            out.add(PaletteEntry(id, palette, data))
        }
        return out
    }

    private fun parsePalettes(entries: Map<String, ByteArray>): LinkedHashMap<String, PaletteEntry> {
        val out = LinkedHashMap<String, PaletteEntry>()
        for ((name, data) in entries) {
            val match = PALETTE_NAME.matchEntire(name) ?: continue
            val expectedId = match.groupValues[1]
            if (PaletteId.isBuiltIn(expectedId)) {
                throw BackupException("备份里的色板编号与内置色板冲突，未做任何修改")
            }
            val palette = runCatching { Palette.fromJson(String(data, Charsets.UTF_8)) }.getOrElse {
                throw BackupException("备份里的色板「$expectedId」已损坏，未做任何修改")
            }
            if (palette.id != expectedId) {
                throw BackupException("备份里的色板编号不一致，未做任何修改")
            }
            out[expectedId] = PaletteEntry(expectedId, palette, data)
        }
        return out
    }

    /** 先解析所有图纸引用的色板并检查下标范围，避免恢复后颜色错位。 */
    private fun ensurePatternPalettes(
        context: Context,
        patterns: List<Pattern>,
        incoming: Map<String, Palette>,
    ) {
        val resolved = HashMap<String, Palette>()
        for (pattern in patterns) {
            val palette = resolved.getOrPut(pattern.paletteId) {
                incoming[pattern.paletteId] ?: when (pattern.paletteId) {
                    PaletteId.MARD_221 -> Palette.load(context, Palette.ASSET_221)
                    PaletteId.MARD_291 -> Palette.load(context, Palette.ASSET_291)
                    else -> throw BackupException(
                        "图纸「${pattern.name}」使用了备份里没有的色板，未做任何修改",
                    )
                }
            }
            if (pattern.cells.any { it >= palette.count }) {
                throw BackupException("图纸「${pattern.name}」的色号超出色板范围，未做任何修改")
            }
        }
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!isKnownEntry(name)) {
                        if (name.startsWith("patterns/")) {
                            throw BackupException("备份里的图纸文件名异常")
                        }
                        if (name.startsWith("palettes/")) {
                            throw BackupException("备份里的色板文件名异常")
                        }
                        continue
                    }
                    val data = readEntry(zip)
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) throw BackupException("备份文件过大")
                    if (entries.size >= MAX_ENTRIES) throw BackupException("备份里的文件太多")
                    entries[name] = data
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipException) {
            throw BackupException("不是有效的 zip 备份文件")
        } catch (e: Exception) {
            throw BackupException("读取备份失败：${e.message ?: "文件无法打开"}")
        }
        if (entries.isEmpty()) throw BackupException("不是豆格的备份文件")
        return entries
    }

    private fun readEntry(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ENTRY_BYTES) throw BackupException("备份里有异常大的文件")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isKnownEntry(name: String): Boolean =
        name == MANIFEST || name in SETTINGS || PATTERN_NAME.matches(name) || PALETTE_NAME.matches(name)

    private fun readManifest(input: InputStream): JSONObject {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name == MANIFEST) {
                    val raw = readEntry(zip)
                    return runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
                        ?: throw BackupException("备份清单已损坏")
                }
            }
        }
        throw BackupException("不是豆格的备份文件")
    }

    private fun verifyManifest(entries: Map<String, ByteArray>): JSONObject {
        val raw = entries[MANIFEST] ?: throw BackupException("不是豆格的备份文件")
        val manifest = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
            ?: throw BackupException("备份清单已损坏")
        verifyManifestValues(manifest)
        return manifest
    }

    private fun verifyManifestValues(manifest: JSONObject) {
        if (manifest.optString("app") != APP_TAG) {
            throw BackupException("不是豆格的备份文件")
        }
        val format = manifest.optInt("format", 0)
        if (format !in MIN_FORMAT..FORMAT) {
            throw BackupException("备份格式版本不支持（$format）")
        }
    }

    private fun generateId(used: Set<String>): String {
        repeat(20) {
            val id = Pattern.newId()
            if (id !in used) return id
        }
        return "import" + System.currentTimeMillis().toString(36)
    }

    private fun readJson(file: File): JSONObject =
        if (!file.exists()) JSONObject()
        else runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
