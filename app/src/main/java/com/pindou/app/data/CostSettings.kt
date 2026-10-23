package com.pindou.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * 成本与工时的估算参数。全局一份，存在 filesDir/cost.json。
 * 豆子单价按「元/颗」，工时按「每 100 颗需要多少分钟」。
 */
class CostSettings(context: Context) {

    private val file = File(context.filesDir, "cost.json")
    private var json: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    var pricePerBead by mutableFloatStateOf(json.optDouble("pricePerBead", 0.02).toFloat())
    var minutesPer100 by mutableFloatStateOf(json.optDouble("minutesPer100", 5.0).toFloat())

    /** 备份恢复后重新读取 cost.json。 */
    fun reload() {
        json = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        pricePerBead = json.optDouble("pricePerBead", 0.02).toFloat()
        minutesPer100 = json.optDouble("minutesPer100", 5.0).toFloat()
    }

    fun priceOf(count: Int): Float = count * pricePerBead

    fun minutesOf(count: Int): Int = (count / 100f * minutesPer100).toInt()

    fun save() {
        json.put("pricePerBead", pricePerBead.toDouble())
        json.put("minutesPer100", minutesPer100.toDouble())
        runCatching { file.writeText(json.toString()) }
    }

    companion object {
        fun yuan(v: Float): String = "¥" + String.format("%.2f", v)

        fun duration(minutes: Int): String =
            if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}
