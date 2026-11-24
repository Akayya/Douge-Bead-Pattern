package com.pindou.app.data

import java.util.Locale
import kotlin.math.floor
import kotlin.math.min

/**
 * 照片吸管的纯逻辑：颜色转 HEX、点击坐标换算到原图像素。
 * 不依赖 Android，方便 JVM 单测；取色界面在 `ui/palette/PaletteScreen.kt`。
 */
object ColorPick {

    /**
     * ARGB → 6 位大写 HEX（不带 `#`，和校准输入框的约定一致：输入框自己带 `#` 前缀）。
     * 忽略 alpha；半透明/全透明照片也只看 RGB。
     */
    fun argbToHex(argb: Int): String = String.format(
        Locale.US,
        "%02X%02X%02X",
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )

    /**
     * 把点击位置换算成原图（已下采样的 bitmap）上的像素坐标。
     *
     * 显示约定是 **ContentScale.Fit（contain）**：整张图等比缩放后居中，四周可能留黑边。
     * 调用方的 Image 必须用 `ContentScale.Fit`，否则换算会偏。
     * 点在留边上时 clamp 到最近的边缘像素，不抛异常。
     *
     * @return (列, 行)，均在 `0 until bmpW` / `0 until bmpH` 内。
     */
    fun mapTapToPixel(
        tapX: Float,
        tapY: Float,
        viewW: Float,
        viewH: Float,
        bmpW: Int,
        bmpH: Int,
    ): Pair<Int, Int> {
        if (viewW <= 0f || viewH <= 0f || bmpW <= 0 || bmpH <= 0) return 0 to 0
        val scale = min(viewW / bmpW, viewH / bmpH)
        if (scale <= 0f) return 0 to 0
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        val px = floor((tapX - left) / scale).toInt().coerceIn(0, bmpW - 1)
        val py = floor((tapY - top) / scale).toInt().coerceIn(0, bmpH - 1)
        return px to py
    }
}