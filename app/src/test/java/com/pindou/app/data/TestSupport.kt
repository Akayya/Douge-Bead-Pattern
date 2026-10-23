package com.pindou.app.data

internal fun testPalette(vararg hexes: String): Palette =
    Palette(
        id = "test",
        title = "测试色板",
        groups = listOf("A"),
        colors = hexes.mapIndexed { index, hex -> BeadColor("T$index", hex, "A") },
    )
