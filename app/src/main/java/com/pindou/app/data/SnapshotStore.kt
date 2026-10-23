package com.pindou.app.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 本地自动快照：复用整库备份的 zip 格式，存在 filesDir/snapshots 下。
 *
 * 快照不进入用户导出的备份包，也不随卸载保留；它是“忘记手动备份”时的
 * 本机兜底，保留最近 [SnapshotPolicy.MAX_SNAPSHOTS] 份。
 */
object SnapshotStore {

    private const val DIR = "snapshots"
    private const val PREFIX = "snap-"
    private const val SUFFIX = ".zip"
    private val NAME = Regex("^snap-(\\d{10,16})\\.zip$")

    data class Entry(
        val file: File,
        val createdAt: Long,
        val appVersion: String,
        val patternCount: Int,
        val sizeBytes: Long,
    )

    @Synchronized
    fun list(context: Context): List<Entry> {
        val files = dir(context).listFiles { f -> f.isFile && NAME.matches(f.name) }
            ?: return emptyList()
        return files.mapNotNull { file ->
            val stamp = stampOf(file) ?: return@mapNotNull null
            val info = runCatching { BackupManager.inspectHeader(file) }.getOrNull()
                ?: return@mapNotNull null
            Entry(
                file = file,
                createdAt = stamp,
                appVersion = info.appVersion,
                patternCount = info.patternCount,
                sizeBytes = file.length(),
            )
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun create(context: Context, protect: File? = null): Entry {
        val dir = dir(context)
        cleanupTemp(dir)
        var stamp = System.currentTimeMillis()
        var target = File(dir, "$PREFIX$stamp$SUFFIX")
        while (target.exists()) {
            stamp += 1
            target = File(dir, "$PREFIX$stamp$SUFFIX")
        }
        val temp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output -> BackupManager.exportTo(context, output) }
            if (!temp.renameTo(target)) {
                throw BackupManager.BackupException("无法保存快照文件")
            }
        } catch (e: Exception) {
            temp.delete()
            if (e is BackupManager.BackupException) throw e
            throw BackupManager.BackupException("创建快照失败：${e.message ?: "存储空间不足"}")
        }
        val info = runCatching { BackupManager.inspectHeader(target) }.getOrNull()
        if (info == null) {
            target.delete()
            throw BackupManager.BackupException("快照写入后校验失败")
        }
        prune(dir, protect)
        return Entry(
            file = target,
            createdAt = stamp,
            appVersion = info.appVersion,
            patternCount = info.patternCount,
            sizeBytes = target.length(),
        )
    }

    /** App 启动时调用；没有数据变化或间隔不足时不打包。 */
    @Synchronized
    fun maybeAutoSnapshot(context: Context): Entry? {
        if (!hasPatterns(context)) return null
        val newest = list(context).firstOrNull()?.createdAt
        val latestChange = latestChangeAt(context)
        if (!SnapshotPolicy.shouldAutoSnapshot(System.currentTimeMillis(), newest, latestChange)) {
            return null
        }
        return create(context)
    }

    /**
     * 恢复前先给当前数据留一份安全快照；安全快照创建失败则放弃恢复，
     * 避免“恢复不成功还丢了当前版本”。
     */
    @Synchronized
    fun restore(context: Context, source: File): BackupManager.ImportResult {
        if (!source.isFile) throw BackupManager.BackupException("快照文件已不存在")
        BackupManager.inspectHeader(source)
        create(context, protect = source)
        return BackupManager.importFile(context, source, overwrite = true)
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun stampOf(file: File): Long? =
        NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()

    private fun hasPatterns(context: Context): Boolean =
        File(context.filesDir, "patterns")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.isNotEmpty() == true

    private fun latestChangeAt(context: Context): Long {
        val patterns = File(context.filesDir, "patterns")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.toList()
            ?: emptyList()
        val palettes = File(context.filesDir, "palettes")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.toList()
            ?: emptyList()
        val files = patterns + palettes + BackupManager.settingsFiles(context)
        return files.filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: 0L
    }

    private fun prune(dir: File, protect: File?) {
        val files = dir.listFiles { f -> f.isFile && NAME.matches(f.name) } ?: return
        val names = files.mapNotNull { file -> stampOf(file)?.let { it to file.name } }
            .sortedByDescending { it.first }
            .map { it.second }
        val protectedName = protect?.takeIf { it.parentFile == dir }?.name
        SnapshotPolicy.namesToDelete(names, protect = protectedName)
            .forEach { File(dir, it).delete() }
    }

    private fun cleanupTemp(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }
}
