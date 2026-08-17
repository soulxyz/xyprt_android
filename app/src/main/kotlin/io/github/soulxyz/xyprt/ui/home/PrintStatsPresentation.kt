package io.github.soulxyz.xyprt.ui.home

import java.util.Locale
import kotlin.math.roundToInt

internal fun formatPrintedDistance(mm: Long, complete: Boolean): String {
    val safe = mm.coerceAtLeast(0L)
    val value = when {
        safe < 1_000L -> "${safe / 10.0}".trimTrailingZero() + " cm"
        safe < 1_000_000L -> (safe / 1000.0).smartNumber(2) + " m"
        else -> (safe / 1_000_000.0).smartNumber(2) + " km"
    }
    return if (complete) value else "至少 $value"
}

internal fun printedDistanceAnalogy(mm: Long): String? {
    val safe = mm.coerceAtLeast(0L)
    return when {
        safe in 140L..240L -> "大约一支铅笔的长度"
        safe in 241L..420L -> "差不多一张 A4 纸的长边"
        safe in 800L..1_500L -> "接近一张书桌的宽度"
        safe in 1_500L..3_000L -> "差不多一张床的长度"
        safe in 7_000L..14_000L -> "接近一辆公交车的长度"
        safe in 20_000L..40_000L -> "差不多一个篮球场的长度"
        safe in 70_000L..130_000L -> "接近一条百米跑道"
        safe in 250_000L..550_000L -> "差不多绕标准跑道一圈"
        safe in 600_000L..4_000_000L -> {
            val laps = (safe / 400_000.0).roundToInt().coerceAtLeast(2)
            "大约绕标准跑道 $laps 圈"
        }
        safe >= 40_075_000_000L -> {
            val laps = (safe / 40_075_000_000.0).smartNumber(1)
            "相当于绕地球约 $laps 圈"
        }
        else -> null
    }
}

private fun Double.smartNumber(decimals: Int): String = String.format(Locale.CHINA, "%.${decimals}f", this)
    .trimEnd('0').trimEnd('.')

private fun String.trimTrailingZero(): String = if (endsWith(".0")) dropLast(2) else this
