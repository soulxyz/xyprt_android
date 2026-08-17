package io.github.soulxyz.xyprt.printer

/**
 * Estimates the physical paper travel represented by a successfully completed print job.
 *
 * Continuous paper has explicit feed-before/feed-after commands, so both are included. Die-cut
 * media uses a device form-feed whose pitch is not known by the current protocol layer; for that
 * case we conservatively count the rendered label height only.
 */
fun estimatePrintedLengthMm(
    image: MonoImage,
    media: MediaType,
    copies: Int = 1,
    feedBeforeDots: Int = 0,
    feedAfterDots: Int = 0,
): Double {
    val safeCopies = copies.coerceAtLeast(1)
    val effective = if (media == MediaType.CONTINUOUS) image.trimTrailingWhite() else image
    val perCopyDots = when (media) {
        MediaType.CONTINUOUS -> feedBeforeDots.coerceAtLeast(0) + effective.height + feedAfterDots.coerceAtLeast(0)
        MediaType.DIE_CUT -> effective.height
    }
    return perCopyDots.toDouble() / Protocol.DOTS_PER_MM * safeCopies
}
