package com.pindou.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pindou.app.data.PaletteStore
import com.pindou.app.data.Pattern
import com.pindou.app.data.InventoryStore
import com.pindou.app.data.PatternStore
import com.pindou.app.data.SamplePatterns
import com.pindou.app.ui.about.AboutScreen
import com.pindou.app.ui.build.BuildModeScreen
import com.pindou.app.ui.editor.PatternEditorScreen
import com.pindou.app.ui.importsheet.ImportScreen
import com.pindou.app.ui.library.LibraryScreen
import com.pindou.app.ui.palette.PaletteScreen
import com.pindou.app.ui.pattern.PatternDetailScreen
import kotlinx.coroutines.Dispatchers
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
    val patterns = remember { mutableStateListOf<Pattern>() }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }

    // 系统返回键在子页面里要能退回图纸库，而不是直接退出 App
    BackHandler(enabled = screen != Screen.Library) { screen = Screen.Library }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            if (PatternStore.list(context).isEmpty()) {
                SamplePatterns.heart(store.current)?.let { PatternStore.save(context, it) }
            }
            PatternStore.list(context)
        }
        patterns.clear()
        patterns.addAll(loaded)
    }

    when (val current = screen) {
        Screen.Library -> LibraryScreen(
            patterns = patterns,
            palette = store.current,
            paletteTitle = store.current.title,
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
                PatternStore.delete(context, p.id)
                patterns.removeAll { it.id == p.id }
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

        Screen.Palette -> PaletteScreen(store = store, inventory = inventory, onBack = { screen = Screen.Library })

        Screen.About -> AboutScreen(onBack = { screen = Screen.Library })

        is Screen.Editor -> {
            val pattern = patterns.firstOrNull { it.id == current.id }
            if (pattern == null) {
                LaunchedEffect(current.id) { screen = Screen.Library }
            } else {
                PatternEditorScreen(
                    pattern = pattern,
                    palette = store.palette(pattern.paletteId),
                    enabledColors = store.enabledColors(),
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
