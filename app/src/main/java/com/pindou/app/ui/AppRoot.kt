package com.pindou.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pindou.app.data.PaletteStore
import com.pindou.app.data.Pattern
import com.pindou.app.data.CostSettings
import com.pindou.app.data.DeductionStore
import com.pindou.app.data.InventoryStore
import com.pindou.app.data.PaletteId
import com.pindou.app.data.PatternFile
import com.pindou.app.data.PatternStore
import com.pindou.app.data.PatternThumbCache
import com.pindou.app.data.SamplePatterns
import com.pindou.app.data.SnapshotStore
import com.pindou.app.ui.about.AboutScreen
import com.pindou.app.ui.build.BuildModeScreen
import com.pindou.app.ui.editor.PatternEditorScreen
import com.pindou.app.ui.importsheet.ImportScreen
import com.pindou.app.ui.library.LibraryScreen
import com.pindou.app.ui.palette.PaletteScreen
import com.pindou.app.ui.pattern.PatternDetailScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Library : Screen
    data object Palette : Screen
    data object About : Screen
    data object Import : Screen
    data class Detail(val id: String) : Screen
    data class Editor(val id: String) : Screen
    data class Build(val id: String) : Screen
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { PaletteStore(context) }
    val inventory = remember { InventoryStore(context) }
    val deductions = remember { DeductionStore(context) }
    val cost = remember { CostSettings(context) }
    val patterns = remember { mutableStateListOf<Pattern>() }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    val appScope = rememberCoroutineScope()

    // 系统返回键在子页面里要能退回图纸库，而不是直接退出 App
    BackHandler(enabled = screen != Screen.Library) { screen = Screen.Library }

    LaunchedEffect(Unit) {
        var seeded = false
        val loaded = withContext(Dispatchers.IO) {
            val existing = PatternStore.list(context)
            if (existing.isEmpty()) {
                seeded = true
                SamplePatterns.heart(store.current)?.let { PatternStore.save(context, it) }
                PatternStore.list(context)
            } else {
                existing
            }
        }
        patterns.clear()
        patterns.addAll(loaded)
        if (!seeded && loaded.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { SnapshotStore.maybeAutoSnapshot(context) }
            }
        }
    }

    when (val current = screen) {
        Screen.Library -> LibraryScreen(
            patterns = patterns,
            palette = store.current,
            paletteTitle = store.current.title,
            paletteFor = { id -> store.palette(id) },
            onOpenPattern = { screen = Screen.Detail(it.id) },
            onOpenPalette = { screen = Screen.Palette },
            onOpenAbout = { screen = Screen.About },
            onNewPattern = { screen = Screen.Import },
            onNewBlank = { w, h ->
                val now = System.currentTimeMillis()
                val fresh = Pattern(
                    id = Pattern.newId(),
                    name = "空白 " + java.text.SimpleDateFormat(
                        "MM-dd HH:mm",
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date(now)),
                    width = w,
                    height = h,
                    paletteId = store.current.id,
                    cells = IntArray(w * h) { -1 },
                    createdAt = now,
                    updatedAt = now,
                    done = mutableSetOf(),
                )
                PatternStore.save(context, fresh)
                patterns.add(0, fresh)
                screen = Screen.Editor(fresh.id)
            },
            onRename = { p, name ->
                val renamed = PatternStore.rename(context, p.id, name)
                if (renamed != null) {
                    val index = patterns.indexOfFirst { it.id == renamed.id }
                    if (index >= 0) patterns[index] = renamed
                }
            },
            onDuplicate = { p ->
                val now = System.currentTimeMillis()
                val copy = Pattern(
                    id = Pattern.newId(),
                    name = p.name + " · 副本",
                    width = p.width,
                    height = p.height,
                    paletteId = p.paletteId,
                    cells = p.cells.copyOf(),
                    createdAt = now,
                    updatedAt = now,
                    done = p.done.toMutableSet(),
                )
                PatternStore.save(context, copy)
                patterns.add(0, copy)
            },
            onDelete = { p ->
                val snapshotted = withContext(Dispatchers.IO) {
                    runCatching { SnapshotStore.create(context) }.isSuccess
                }
                PatternStore.delete(context, p.id)
                PatternThumbCache.delete(context, p.id)
                patterns.removeAll { it.id == p.id }
                snapshotted
            },
            onRestore = { p ->
                PatternStore.save(context, p)
                if (patterns.none { it.id == p.id }) patterns.add(0, p)
            },
            onImportPattern = { uri ->
                val usedIds = patterns.mapTo(HashSet()) { it.id }
                val usedNames = patterns.mapTo(HashSet()) { it.name }
                val outcome = try {
                    val parsed = withContext(Dispatchers.IO) {
                        PatternFile.parse(PatternFile.readText(context, uri))
                    }
                    val embedded = parsed.embeddedPalette
                    val byId = store.paletteOrNull(parsed.pattern.paletteId)
                    var paletteNote: String? = null
                    val localPalette = when {
                        embedded == null -> byId
                        byId != null && byId.colors == embedded.colors -> byId
                        else -> store.customPalettes().firstOrNull { it.colors == embedded.colors }
                            ?: store.importCustomPalette(embedded.title, embedded.colors).also {
                                paletteNote = "同时导入色板「${it.title}」"
                            }
                    } ?: throw PatternFile.ImportException(
                        if (PaletteId.isBuiltIn(parsed.pattern.paletteId)) {
                            "色板加载失败，无法导入"
                        } else {
                            "图纸使用了本地没有的自定义色板，请先在色板页导入同名色板"
                        },
                    )
                    val source = if (localPalette.id == parsed.pattern.paletteId) {
                        parsed.pattern
                    } else {
                        Pattern(
                            id = parsed.pattern.id,
                            name = parsed.pattern.name,
                            width = parsed.pattern.width,
                            height = parsed.pattern.height,
                            paletteId = localPalette.id,
                            cells = parsed.pattern.cells,
                            createdAt = parsed.pattern.createdAt,
                            updatedAt = parsed.pattern.updatedAt,
                            done = parsed.pattern.done,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        val unknown = PatternFile.unknownColorCount(source, localPalette)
                        if (unknown > 0) {
                            PatternFile.Outcome.Failure(
                                "图纸里有 $unknown 个本地色板不存在的色号，无法导入",
                            )
                        } else {
                            val prepared = PatternFile.prepareForImport(
                                source,
                                usedIds,
                                usedNames,
                            )
                            PatternStore.save(context, prepared.pattern)
                            PatternFile.Outcome.Success(
                                pattern = prepared.pattern,
                                mismatchedColors = PatternFile.mismatchedColorCount(
                                    parsed,
                                    localPalette,
                                ),
                                paletteNote = paletteNote,
                            )
                        }
                    }
                } catch (e: PatternFile.ImportException) {
                    PatternFile.Outcome.Failure(e.message ?: "图纸文件无法读取")
                } catch (e: Exception) {
                    PatternFile.Outcome.Failure("导入失败：${e.message ?: "文件无法读取"}")
                }
                if (outcome is PatternFile.Outcome.Success) patterns.add(0, outcome.pattern)
                outcome
            },
        )

        Screen.Import -> ImportScreen(
            store = store,
            onBack = { screen = Screen.Library },
            onSaved = { saved ->
                patterns.add(0, saved)
                screen = Screen.Detail(saved.id)
            },
        )

        Screen.Palette -> PaletteScreen(
            store = store,
            inventory = inventory,
            onBack = { screen = Screen.Library },
            paletteUsage = { id -> patterns.count { it.paletteId == id } },
        )

        Screen.About -> AboutScreen(
            onBack = { screen = Screen.Library },
            patternCount = patterns.size,
            onBackupImported = {
                store.reload()
                inventory.reload()
                deductions.reload()
                cost.reload()
                appScope.launch {
                    val loaded = withContext(Dispatchers.IO) { PatternStore.list(context) }
                    patterns.clear()
                    patterns.addAll(loaded)
                }
            },
        )

        is Screen.Editor -> {
            val pattern = patterns.firstOrNull { it.id == current.id }
            if (pattern == null) {
                LaunchedEffect(current.id) { screen = Screen.Library }
            } else {
                PatternEditorScreen(
                    pattern = pattern,
                    palette = store.palette(pattern.paletteId),
                    enabledColors = store.enabledColors(pattern.paletteId),
                    onBack = { screen = Screen.Detail(pattern.id) },
                    onSaved = { updated ->
                        val index = patterns.indexOfFirst { it.id == updated.id }
                        if (index >= 0) patterns[index] = updated
                    },
                )
            }
        }

        is Screen.Build -> {
            val pattern = patterns.firstOrNull { it.id == current.id }
            if (pattern == null) {
                LaunchedEffect(current.id) { screen = Screen.Library }
            } else {
                BuildModeScreen(
                    pattern = pattern,
                    palette = store.palette(pattern.paletteId),
                    inventory = inventory,
                    deductions = deductions,
                    minutesPer100 = cost.minutesPer100,
                    onBack = { screen = Screen.Detail(pattern.id) },
                    onPatternChanged = { updated ->
                        val index = patterns.indexOfFirst { it.id == updated.id }
                        if (index >= 0) patterns[index] = updated
                    },
                )
            }
        }

        is Screen.Detail -> {
            val pattern = patterns.firstOrNull { it.id == current.id }
            if (pattern == null) {
                LaunchedEffect(current.id) { screen = Screen.Library }
            } else {
                PatternDetailScreen(
                    pattern = pattern,
                    palette = store.palette(pattern.paletteId),
                    inventory = inventory,
                    onBack = { screen = Screen.Library },
                    onEdit = { screen = Screen.Editor(pattern.id) },
                    onBuild = { screen = Screen.Build(pattern.id) },
                    onPatternChanged = { updated ->
                        val index = patterns.indexOfFirst { it.id == updated.id }
                        if (index >= 0) patterns[index] = updated
                    },
                )
            }
        }
    }
}
