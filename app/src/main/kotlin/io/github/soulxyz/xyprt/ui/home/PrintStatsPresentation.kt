package io.github.soulxyz.xyprt.ui.home

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

internal fun formatPrintedDistance(mm: Long, complete: Boolean): String {
    val safe = mm.coerceAtLeast(0L)
    val value = when {
        safe < 1_000L -> "${safe / 10.0}".trimTrailingZero() + " cm"
        safe < 1_000_000L -> (safe / 1000.0).smartNumber(2) + " m"
        else -> (safe / 1_000_000.0).smartNumber(2) + " km"
    }
    return if (complete) value else "至少 $value"
}

private data class AnalogyItem(val canonicalMm: Long, val label: String)

/**
 * Familiar objects with roughly known sizes, ordered by canonical length. An item matches when the
 * printed length is within 75%..130% of its canonical size, so several plausible comparisons can
 * apply to the same distance and the UI may pick any of them.
 */
private val ANALOGY_ITEMS = listOf(
    AnalogyItem(25L, "大约一枚一元硬币的直径"),
    AnalogyItem(30L, "差不多一个瓶盖的直径"),
    AnalogyItem(44L, "差不多一节七号电池的长度"),
    AnalogyItem(50L, "差不多一节五号电池的长度"),
    AnalogyItem(54L, "大约一张银行卡的短边"),
    AnalogyItem(75L, "差不多一支口红的高度"),
    AnalogyItem(86L, "大约一张银行卡的长边"),
    AnalogyItem(90L, "差不多一张名片的长边"),
    AnalogyItem(100L, "大约一张明信片的短边"),
    AnalogyItem(140L, "差不多一副眼镜的镜腿长度"),
    AnalogyItem(148L, "大约一张明信片的长边"),
    AnalogyItem(150L, "差不多一部手机的长度"),
    AnalogyItem(190L, "大约一支铅笔的长度"),
    AnalogyItem(200L, "差不多一把学生直尺的长度"),
    AnalogyItem(220L, "大约一个足球的直径"),
    AnalogyItem(230L, "差不多一瓶矿泉水的高度"),
    AnalogyItem(250L, "大约一个篮球的直径"),
    AnalogyItem(280L, "差不多一双运动鞋的长度"),
    AnalogyItem(297L, "大约一张 A4 纸的长边"),
    AnalogyItem(360L, "差不多一台笔记本电脑的宽度"),
    AnalogyItem(450L, "大约一个电脑键盘的长度"),
    AnalogyItem(500L, "差不多一个新生婴儿的身长"),
    AnalogyItem(720L, "大约一条手臂的长度"),
    AnalogyItem(750L, "差不多一个篮球的周长"),
    AnalogyItem(850L, "大约一根棒球棒的长度"),
    AnalogyItem(900L, "差不多一扇房门的宽度"),
    AnalogyItem(1_000L, "大约一张单人床的宽度"),
    AnalogyItem(1_000L, "差不多一把木吉他的长度"),
    AnalogyItem(1_140L, "大约一根高尔夫球杆的长度"),
    AnalogyItem(1_200L, "差不多一张餐桌的直径"),
    AnalogyItem(1_350L, "大约一台落地风扇的高度"),
    AnalogyItem(1_450L, "差不多一根台球杆的长度"),
    AnalogyItem(1_500L, "大约一张双人床的宽度"),
    AnalogyItem(1_750L, "差不多一台冰箱的高度"),
    AnalogyItem(1_800L, "大约一个成年人的身高"),
    AnalogyItem(2_000L, "差不多一扇房门的高度"),
    AnalogyItem(2_300L, "大约一床双人被子的长度"),
    AnalogyItem(2_700L, "差不多一层楼的高度"),
    AnalogyItem(2_740L, "大约一张乒乓球桌的长度"),
    AnalogyItem(3_500L, "差不多一辆两厢轿车的长度"),
    AnalogyItem(4_300L, "大约一辆小型货车的长度"),
    AnalogyItem(4_500L, "差不多一辆家用轿车的长度"),
    AnalogyItem(5_300L, "大约一辆皮卡车的长度"),
    AnalogyItem(8_000L, "差不多一辆中巴车的长度"),
    AnalogyItem(12_000L, "大约一辆公交车的长度"),
    AnalogyItem(19_000L, "差不多一节地铁车厢的长度"),
    AnalogyItem(23_770L, "大约一个标准网球场的长度"),
    AnalogyItem(28_000L, "差不多一个篮球场的长度"),
    AnalogyItem(50_000L, "大约一个标准游泳池的长度"),
    AnalogyItem(68_000L, "差不多一个标准足球场的宽度"),
    AnalogyItem(100_000L, "大约一条百米跑道的长度"),
    AnalogyItem(105_000L, "差不多一个标准足球场的长度"),
    AnalogyItem(135_000L, "大约伦敦眼的高度"),
    AnalogyItem(200_000L, "差不多一条 200 米跑道的长度"),
    AnalogyItem(315_000L, "大约三个足球场的长度"),
    AnalogyItem(468_000L, "差不多东方明珠的高度"),
    AnalogyItem(525_000L, "大约五个足球场的长度"),
    AnalogyItem(600_000L, "差不多广州塔的高度"),
    AnalogyItem(828_000L, "大约哈利法塔的高度"),
    AnalogyItem(1_000_000L, "差不多一座跨江大桥的长度"),
    AnalogyItem(1_670_000L, "大约一座武汉长江大桥的长度"),
    AnalogyItem(3_000_000L, "差不多一条机场跑道的长度"),
    AnalogyItem(5_000_000L, "大约一场 5 公里跑的距离"),
    AnalogyItem(10_000_000L, "差不多一次 10 公里长跑的距离"),
    AnalogyItem(21_097_000L, "大约半个马拉松的距离"),
    AnalogyItem(42_195_000L, "差不多一个全程马拉松的距离"),
    AnalogyItem(55_000_000L, "大约一座港珠澳大桥主桥的长度"),
    AnalogyItem(120_000_000L, "差不多北京到天津的城际距离"),
    AnalogyItem(170_000_000L, "大约上海到杭州的距离"),
    AnalogyItem(300_000_000L, "差不多高铁一小时的车程"),
    AnalogyItem(406_000_000L, "大约北京到济南的高铁里程"),
    AnalogyItem(810_000_000L, "差不多武汉到上海的高铁里程"),
    AnalogyItem(1_318_000_000L, "大约京沪高铁全程的长度"),
    AnalogyItem(1_540_000_000L, "差不多北京到成都的航线距离"),
    AnalogyItem(1_950_000_000L, "大约北京到深圳的航线距离"),
    AnalogyItem(2_800_000_000L, "差不多哈尔滨到广州的直线距离"),
    AnalogyItem(3_000_000_000L, "大约乌鲁木齐到上海的铁路里程"),
    AnalogyItem(6_300_000_000L, "差不多长江的长度"),
    AnalogyItem(8_200_000_000L, "大约北京到巴黎的航线距离"),
    AnalogyItem(12_000_000_000L, "差不多北京到洛杉矶的航线距离"),
    AnalogyItem(20_037_000_000L, "大约半个赤道的长度"),
    AnalogyItem(40_075_000_000L, "差不多绕地球赤道一圈"),
    AnalogyItem(384_400_000_000L, "大约地球到月球的距离"),
)

internal fun printedDistanceCandidates(mm: Long): List<String> {
    val safe = mm.coerceAtLeast(0L)
    val items = ANALOGY_ITEMS
        .filter { safe >= it.canonicalMm * 3L / 4L && safe <= it.canonicalMm * 13L / 10L }
        .map { it.label }
        .toMutableList()
    if (safe >= 600_000L && safe < 40_075_000_000L) {
        val laps = (safe / 400_000.0).roundToInt().coerceAtLeast(2)
        items += "大约绕标准跑道 $laps 圈"
    }
    if (safe >= 40_075_000_000L) {
        val laps = (safe / 40_075_000_000.0).smartNumber(1)
        items += "相当于绕地球约 $laps 圈"
    }
    return items
}

internal fun printedDistanceAnalogy(mm: Long, random: Random = Random.Default): String? =
    printedDistanceCandidates(mm).takeIf { it.isNotEmpty() }?.random(random)

private fun Double.smartNumber(decimals: Int): String = String.format(Locale.CHINA, "%.${decimals}f", this)
    .trimEnd('0').trimEnd('.')

private fun String.trimTrailingZero(): String = if (endsWith(".0")) dropLast(2) else this
