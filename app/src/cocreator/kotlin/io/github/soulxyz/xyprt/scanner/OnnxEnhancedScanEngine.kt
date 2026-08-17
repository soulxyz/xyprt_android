package io.github.soulxyz.xyprt.scanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import io.github.soulxyz.xyprt.data.remote.EnhancedCapability
import io.github.soulxyz.xyprt.data.remote.EnhancedModelRepository
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Co-creator-only ONNX proposal engine. This file is excluded from normal/open-source builds. */
class OnnxEnhancedScanEngine(
    private val models: EnhancedModelRepository,
) : EnhancedScanEngine {
    @Volatile private var sessionKey: String? = null
    @Volatile private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val sessionLock = Any()

    override suspend fun detect(bitmap: Bitmap): EnhancedScanProposal? {
        val item = models.bestInstalled() ?: return null
        var bytes = models.decryptInstalled(item)
        if (bytes == null) {
            val refreshed = withTimeoutOrNull(1800) { models.ensureLease(item.id) } == true
            if (refreshed) bytes = models.decryptInstalled(item)
        }
        bytes ?: return null
        val localBytes = bytes
        val s = runCatching { sessionFor(item, localBytes) }.getOrNull().also { localBytes.fill(0) } ?: return null
        return runCatching { inferQuad(bitmap, s) }.getOrNull()?.takeIf { it.quad.isReasonable() }
    }

    override fun release() = synchronized(sessionLock) {
        session?.close()
        session = null
        sessionKey = null
    }

    private fun sessionFor(item: EnhancedCapability, bytes: ByteArray): OrtSession = synchronized(sessionLock) {
        val key = "${item.id}:${item.version}"
        session?.takeIf { sessionKey == key }?.let { return it }
        session?.close()
        session = null
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            runCatching { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
        }
        try {
            return env.createSession(bytes, opts).also {
                session = it
                sessionKey = key
            }
        } finally {
            opts.close()
        }
    }

    private fun inferQuad(bitmap: Bitmap, s: OrtSession): EnhancedScanProposal {
        val size = 256
        val scale = minOf(size / bitmap.width.toFloat(), size / bitmap.height.toFloat())
        val dw = (bitmap.width * scale).roundToInt()
        val dh = (bitmap.height * scale).roundToInt()
        val ox = (size - dw) / 2
        val oy = (size - dh) / 2
        val inputBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(inputBmp).apply {
            drawColor(Color.rgb(128, 128, 128))
            drawBitmap(bitmap, null, Rect(ox, oy, ox + dw, oy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(size * size)
        inputBmp.getPixels(px, 0, size, 0, 0, size, size)
        inputBmp.recycle()
        val data = FloatArray(3 * size * size)
        for (i in px.indices) {
            val p = px[i]
            data[i] = ((p shr 16) and 255) / 255f
            data[size * size + i] = ((p shr 8) and 255) / 255f
            data[2 * size * size + i] = (p and 255) / 255f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 256, 256)).use { tensor ->
            s.run(mapOf("input" to tensor)).use { result ->
                val value = result.get("corner_heatmaps").orElseThrow().value as Array<*>
                @Suppress("UNCHECKED_CAST") val batch = value as Array<Array<Array<FloatArray>>>
                val hm = batch[0]
                val pts = ArrayList<QuadPoint>(4)
                var confidence = 0f
                var minPeakSigma = Float.MAX_VALUE
                for (ch in 0..3) {
                    val a = hm[ch]
                    var bx = 0
                    var by = 0
                    var best = -Float.MAX_VALUE
                    var sum = 0.0
                    var sumSq = 0.0
                    var count = 0
                    for (y in a.indices) for (x in a[y].indices) {
                        val v = a[y][x]
                        if (v > best) { best = v; bx = x; by = y }
                        sum += v
                        sumSq += v * v
                        count++
                    }
                    confidence += sigmoid(best)
                    val mean = sum / count.coerceAtLeast(1)
                    val std = kotlin.math.sqrt((sumSq / count.coerceAtLeast(1) - mean * mean).coerceAtLeast(1e-9))
                    minPeakSigma = minOf(minPeakSigma, ((best - mean) / std).toFloat())
                    var sx = 0.0
                    var sy = 0.0
                    var sw = 0.0
                    for (y in (by - 2).coerceAtLeast(0)..(by + 2).coerceAtMost(63)) {
                        for (x in (bx - 2).coerceAtLeast(0)..(bx + 2).coerceAtMost(63)) {
                            val ww = exp((a[y][x] - best).coerceIn(-12f, 0f).toDouble())
                            sx += (x + 0.5) * ww
                            sy += (y + 0.5) * ww
                            sw += ww
                        }
                    }
                    val hx = (sx / sw * 4.0).toFloat()
                    val hy = (sy / sw * 4.0).toFloat()
                    pts += modelPointToNormalized(hx, hy, ox, oy, scale, bitmap.width, bitmap.height)
                }
                val heatQuad = DocumentQuad(pts[0], pts[1], pts[2], pts[3]).clamped()
                val avgConfidence = (confidence / 4f).coerceIn(0f, 1f)
                val maskQuad = runCatching {
                    val mv = result.get("mask_logits").orElseThrow().value as Array<*>
                    @Suppress("UNCHECKED_CAST") val mb = mv as Array<Array<Array<FloatArray>>>
                    maskQuadFromLogits(mb[0][0], ox, oy, scale, bitmap.width, bitmap.height)
                }.getOrNull()

                val heatGood = heatQuad.isReasonable() && avgConfidence >= 0.50f && minPeakSigma >= 4.2f
                val maskGood = maskQuad?.isReasonable() == true
                val selected = when {
                    heatGood && maskGood -> {
                        val mq = maskQuad!!
                        val disagreement = quadDistance(heatQuad, mq)
                        if (disagreement < .12f || quadGeometryPenalty(heatQuad) <= quadGeometryPenalty(mq) * 1.12) heatQuad else mq
                    }
                    heatGood -> heatQuad
                    maskGood -> maskQuad!!
                    heatQuad.isReasonable() && minPeakSigma >= 3.0f -> heatQuad
                    else -> return EnhancedScanProposal(DocumentQuad(), 0f)
                }
                val evidence = (minPeakSigma / 7f).coerceIn(.45f, 1f)
                return EnhancedScanProposal(selected, (avgConfidence * evidence).coerceIn(0.35f, 0.99f))
            }
        }
    }

    private fun modelPointToNormalized(hx: Float, hy: Float, ox: Int, oy: Int, scale: Float, w: Int, h: Int): QuadPoint {
        val ix = ((hx - ox) / scale).coerceIn(0f, w.toFloat())
        val iy = ((hy - oy) / scale).coerceIn(0f, h.toFloat())
        return QuadPoint(ix / w, iy / h)
    }

    private fun maskQuadFromLogits(mask: Array<FloatArray>, ox: Int, oy: Int, scale: Float, w: Int, h: Int): DocumentQuad? {
        if (mask.isEmpty() || mask[0].isEmpty()) return null
        val mh = mask.size
        val mw = mask[0].size
        val binary = Mat(mh, mw, CvType.CV_8UC1)
        val bytes = ByteArray(mw * mh)
        var i = 0
        for (y in 0 until mh) for (x in 0 until mw) bytes[i++] = if (mask[y][x] > 0f) 255.toByte() else 0
        binary.put(0, 0, bytes)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val contour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            if (Imgproc.contourArea(contour) < mw * mh * 0.08) return null
            val c2 = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            try {
                val peri = Imgproc.arcLength(c2, true)
                var points: Array<Point>? = null
                for (eps in doubleArrayOf(.018, .025, .035, .05, .07)) {
                    Imgproc.approxPolyDP(c2, approx, peri * eps, true)
                    if (approx.total() == 4L) { points = order(approx.toArray()); break }
                }
                val p = points ?: run {
                    val rect = Imgproc.minAreaRect(c2)
                    val box = arrayOf(Point(), Point(), Point(), Point())
                    rect.points(box)
                    val bm = MatOfPoint(*box)
                    val rectArea = try { abs(Imgproc.contourArea(bm)) } finally { bm.release() }
                    if (rectArea <= 0.0 || Imgproc.contourArea(contour) / rectArea < .58) return null
                    order(box)
                }
                val mapped = p.map { pt ->
                    modelPointToNormalized(((pt.x + .5) * 4.0).toFloat(), ((pt.y + .5) * 4.0).toFloat(), ox, oy, scale, w, h)
                }
                return DocumentQuad(mapped[0], mapped[1], mapped[2], mapped[3]).clamped().takeIf { it.isReasonable() }
            } finally {
                c2.release(); approx.release()
            }
        } finally {
            contours.forEach { it.release() }
            kernel.release(); hierarchy.release(); binary.release()
        }
    }

    private fun order(points: Array<Point>): Array<Point> {
        val pts = points.toList()
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.maxBy { it.x - it.y }
        val bl = pts.minBy { it.x - it.y }
        return arrayOf(tl, tr, br, bl)
    }

    private fun maxCornerCosine(p: Array<Point>): Double {
        var m = 0.0
        for (i in 0..3) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val ux = a.x - b.x; val uy = a.y - b.y; val vx = c.x - b.x; val vy = c.y - b.y
            val d = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy) + 1e-6)
            m = max(m, abs(d))
        }
        return m
    }

    private fun quadGeometryPenalty(q: DocumentQuad): Double {
        if (!q.isReasonable()) return 999.0
        val p = q.points().map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
        val angle = maxCornerCosine(p).coerceIn(0.0, .95)
        val edges = (0..3).map { distance(p[it], p[(it + 1) % 4]) }
        val skinny = (edges.minOrNull() ?: 0.0) / (edges.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0)
        val area = q.area.toDouble()
        return angle * 3.0 + (if (skinny < .08) 2.0 else 0.0) + (if (area < .015) 2.0 else 0.0)
    }

    private fun quadDistance(a: DocumentQuad, b: DocumentQuad): Float = a.points().zip(b.points())
        .map { (p, q) -> hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) }
        .average().toFloat()

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun sigmoid(v: Float) = (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()
}
