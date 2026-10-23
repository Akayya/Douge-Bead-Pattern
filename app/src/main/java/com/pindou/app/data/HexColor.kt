package com.pindou.app.data

/** 色板与校准时用到的 HEX 处理；不依赖 Android，方便 JVM 单测。 */
object HexColor {

    private val HEX6 = Regex("[0-9a-fA-F]{6}")

    /** 接受 RRGGBB / #RRGGBB，统一成 #RRGGBB；其它格式返回 null。 */
    fun normalize(raw: String): String? {
        val body = raw.trim().removePrefix("#")
        return if (HEX6.matches(body)) "#${body.uppercase()}" else null
    }

    /** 支持 #RRGGBB / #AARRGGBB；色板数据由资源文件保证合法，异常时按不透明黑兜底。 */
    fun parseArgb(hex: String): Int {
        val body = hex.trim().removePrefix("#")
        return try {
            when (body.length) {
                6 -> (0xFF000000L or body.toLong(16)).toInt()
                8 -> body.toLong(16).toInt()
                else -> 0xFF000000.toInt()
            }
        } catch (e: NumberFormatException) {
            0xFF000000.toInt()
        }
    }
}
