package io.github.soulxyz.xyprt.scanner

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc

/**
 * Two-level document scanner: a stable OpenCV detector is always available; an optional
 * server-authorized model can provide a better initial quad. Both feed the same full-resolution
 * edge refinement and perspective pipeline, so model failure never blocks scanning.
 */
class DocumentScanner(private val enhancedEngine: EnhancedScanEngine) {

    suspend fun detect(bitmap: Bitmap, preferEnhanced: Boolean = true): ScanDetection = withContext(Dispatchers.Default) {
        // Always keep the stable detector in the decision. Enhanced models are allowed to improve
        // the initial quad, not to remove the fallback. This costs one extra small-image OpenCV
        // pass after a photo, but prevents a confident model miss from trapping the user.
        val standard = standardDetect(bitmap)
        if (!preferEnhanced || standard.confidence >= .82f) return@withContext standard
        val proposal = enhancedEngine.detect(bitmap)?.takeIf { it.quad.isReasonable() } ?: return@withContext standard
        val enhanced = refineEnhanced(bitmap, proposal)
        val disagreement = quadDistance(standard.quad, enhanced.quad)
        if (standard.confidence >= .70f && disagreement >= .11f) standard else enhanced
    }

    fun standardDetect(bitmap: Bitmap): ScanDetection {
        ensureOpenCv()
        val maxSide = 1280
        val detectScale = minOf(1f, maxSide / maxOf(bitmap.width, bitmap.height).toFloat())
        val detectBitmap = if (detectScale < 0.999f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * detectScale).roundToInt().coerceAtLeast(64),
                (bitmap.height * detectScale).roundToInt().coerceAtLeast(64),
                true,
            )
        } else bitmap

        val src = Mat(); val gray = Mat(); val hierarchy = Mat()
        val candidates = mutableListOf<QuadCandidate>()
        try {
            Utils.bitmapToMat(detectBitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // The old detector had one gray Canny pass. Real photos fail in different ways: a
            // receipt may have a weak outer edge but strong red/green channel separation; a white
            // label can disappear in grayscale; a colorful box can be easy in one channel. Scan a
            // small set of classic Canny passes and let geometry/content scoring choose.
            val split = mutableListOf<Mat>()
            Core.split(src, split)
            val channels = mutableListOf(gray)
            split.take(3).forEach { channels += it }
            try {
                channels.forEachIndexed { channelIndex, channel ->
                    val blurred = Mat(); val edges = Mat(); val closed = Mat()
                    try {
                        Imgproc.GaussianBlur(channel, blurred, Size(5.0, 5.0), 0.0)
                        for ((lo, hi) in listOf(20.0 to 70.0, 36.0 to 108.0, 58.0 to 174.0, 82.0 to 232.0)) {
                            Imgproc.Canny(blurred, edges, lo, hi)
                            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                            try { Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2) }
                            finally { kernel.release() }
                            collectQuadCandidates(closed, gray, src, detectBitmap.width, detectBitmap.height, "edge-$channelIndex", candidates)
                        }
                    } finally { blurred.release(); edges.release(); closed.release() }
                }
            } finally { split.forEach { it.release() } }

            // Bright/low-saturation sheets and labels are common enough to deserve their own
            // segmentation pass. Multiple thresholds make this robust to warm restaurant light,
            // gray paper and washed-out store labels.
            collectLightRegionCandidates(src, gray, detectBitmap.width, detectBitmap.height, candidates)

            val best = candidates.maxByOrNull { it.score }
            // A tiny low-confidence rectangle is more dangerous than admitting uncertainty: it can
            // silently crop away most of a receipt. In that case start from a safe near-full frame
            // and let the user adjust corners instead of pretending the detector knows.
            val safeFallback = DocumentScanPolicy.shouldUseManualFallback(best?.score, best?.quad?.area)
            val initial = if (safeFallback) DocumentQuad() else best!!.quad
            // Do not run sub-pixel corner snapping on the uncertainty fallback. On a busy desk a
            // near-full frame can otherwise get pulled onto four unrelated high-contrast details,
            // which is exactly the destructive behaviour the fallback is meant to prevent.
            val refined = if (safeFallback) {
                initial
            } else if (detectBitmap === bitmap) {
                refineCorners(gray, initial, bitmap.width, bitmap.height)
            } else {
                val full = Mat(); val fullGray = Mat()
                try {
                    Utils.bitmapToMat(bitmap, full)
                    Imgproc.cvtColor(full, fullGray, Imgproc.COLOR_RGBA2GRAY)
                    refineCorners(fullGray, initial, bitmap.width, bitmap.height)
                } finally { full.release(); fullGray.release() }
            }
            val confidence = DocumentScanPolicy.confidenceFor(best?.score, safeFallback)
            return ScanDetection(refined, ScanEngine.STANDARD, confidence)
        } finally {
            if (detectBitmap !== bitmap) detectBitmap.recycle()
            src.release(); gray.release(); hierarchy.release()
        }
    }

    private data class QuadCandidate(val quad: DocumentQuad, val score: Double, val source: String)

    private fun collectQuadCandidates(
        binary: Mat,
        gray: Mat,
        rgba: Mat,
        w: Int,
        h: Int,
        source: String,
        out: MutableList<QuadCandidate>,
    ) {
        val hierarchy = Mat(); val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val total = w.toDouble() * h
            contours.sortedByDescending { Imgproc.contourArea(it) }.take(100).forEach { contour ->
                val area = Imgproc.contourArea(contour)
                val areaRatio = area / total
                if (areaRatio < .012 || areaRatio > .965) return@forEach
                val c2 = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                try {
                    val peri = Imgproc.arcLength(c2, true)
                    var accepted = false
                    for (eps in doubleArrayOf(.012, .018, .025, .035, .05, .07)) {
                        Imgproc.approxPolyDP(c2, approx, peri * eps, true)
                        if (approx.total() == 4L) {
                            val p = order(approx.toArray())
                            val poly = MatOfPoint(*p)
                            val convex = try { Imgproc.isContourConvex(poly) } finally { poly.release() }
                            if (convex) {
                                val q = normalized(p, w, h)
                                if (q.isReasonable()) {
                                    val score = documentCandidateScore(p, area, area, gray, rgba, w, h, binary)
                                    if (score > .018) out += QuadCandidate(q, score, source)
                                    accepted = true
                                }
                            }
                            break
                        }
                    }
                    // Curled receipts / partially obscured labels often do not simplify to four
                    // points. A high-fill min-area rectangle is a safer fallback than returning the
                    // whole frame or a random internal text contour.
                    if (!accepted) {
                        val rect = Imgproc.minAreaRect(c2)
                        val box = arrayOf(Point(), Point(), Point(), Point())
                        rect.points(box)
                        val boxMat = MatOfPoint(*box)
                        val rectArea = try { kotlin.math.abs(Imgproc.contourArea(boxMat)).coerceAtLeast(1.0) } finally { boxMat.release() }
                        val fill = area / rectArea
                        if (fill >= .66) {
                            val p = order(box)
                            val q = normalized(p, w, h)
                            if (q.isReasonable()) {
                                val score = documentCandidateScore(p, area, rectArea, gray, rgba, w, h, binary) * .86
                                if (score > .02) out += QuadCandidate(q, score, "$source-rect")
                            }
                        }
                    }
                } finally { c2.release(); approx.release() }
            }
        } finally { contours.forEach { it.release() }; hierarchy.release() }
    }

    private fun documentCandidateScore(
        p: Array<Point>,
        contourArea: Double,
        rectArea: Double,
        gray: Mat,
        rgba: Mat,
        w: Int,
        h: Int,
        edgeMap: Mat,
    ): Double {
        val total = (w.toDouble() * h).coerceAtLeast(1.0)
        val areaRatio = (contourArea / total).coerceIn(0.0, 1.0)
        val anglePenalty = maxCornerCosine(p).coerceIn(0.0, .92)
        val lengths = (0..3).map { distance(p[it], p[(it + 1) % 4]) }
        val skinny = (lengths.minOrNull() ?: 0.0) / (lengths.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0)
        if (skinny < .075) return 0.0
        val fill = (contourArea / rectArea.coerceAtLeast(1.0)).coerceIn(.0, 1.0)
        val touches = p.count { it.x < w * .012 || it.x > w * .988 || it.y < h * .012 || it.y > h * .988 }

        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        return try {
            val poly = MatOfPoint(*p)
            try { Imgproc.fillConvexPoly(mask, poly, Scalar(255.0)) } finally { poly.release() }
            val mean = MatOfDouble()
            val std = MatOfDouble()
            val meanGray: Double
            val grayStd: Double
            try {
                Core.meanStdDev(gray, mean, std, mask)
                meanGray = mean.toArray().firstOrNull() ?: 0.0
                grayStd = std.toArray().firstOrNull() ?: 255.0
            } finally {
                mean.release(); std.release()
            }
            val edgeDensity = (Core.mean(edgeMap, mask).`val`[0] / 255.0).coerceIn(0.0, .35)
            val paperBonus = ((meanGray - 115.0) / 140.0).coerceIn(0.0, 1.0)
            val color = Core.mean(rgba, mask).`val`
            val maxRgb = maxOf(color[0], color[1], color[2]).coerceAtLeast(1.0)
            val minRgb = minOf(color[0], color[1], color[2])
            val colorSpread = ((maxRgb - minRgb) / maxRgb).coerceIn(0.0, 1.0)
            // White/gray paper can contain ink but is usually much more internally uniform and
            // color-neutral than a keyboard, tabletop or colored clipboard. This deliberately
            // counterbalances the old "bigger rectangle wins" bias.
            val uniformity = ((58.0 - grayStd) / 50.0).coerceIn(0.0, 1.0)
            val neutral = (1.0 - colorSpread / .35).coerceIn(0.0, 1.0)
            val materialPrior = (.48 + 1.08 * uniformity) * (.72 + .45 * neutral)
            val framePenalty = if (touches >= 3) .38 else if (touches == 2 && areaRatio > .80) .72 else 1.0
            kotlin.math.sqrt(areaRatio.coerceAtLeast(.0001)) *
                (1.0 - anglePenalty) * (1.0 - anglePenalty) *
                (.58 + .42 * skinny) * (.68 + .32 * fill) *
                (.82 + edgeDensity * 1.4 + paperBonus * .12) * materialPrior * framePenalty
        } finally { mask.release() }
    }

    private fun collectLightRegionCandidates(
        rgba: Mat,
        gray: Mat,
        w: Int,
        h: Int,
        out: MutableList<QuadCandidate>,
    ) {
        val rgb = Mat(); val hsv = Mat(); val mask = Mat(); val tmp = Mat()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val ranges = listOf(
                Triple(132.0, 112.0, "light-a"),
                Triple(158.0, 120.0, "light-b"),
                Triple(182.0, 138.0, "light-c"),
                Triple(202.0, 160.0, "light-d"),
            )
            for ((valueMin, satMax, name) in ranges) {
                Core.inRange(hsv, Scalar(0.0, 0.0, valueMin), Scalar(180.0, satMax, 255.0), mask)
                val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
                try {
                    Imgproc.morphologyEx(mask, tmp, Imgproc.MORPH_CLOSE, k, Point(-1.0, -1.0), 2)
                    Imgproc.morphologyEx(tmp, tmp, Imgproc.MORPH_OPEN, k, Point(-1.0, -1.0), 1)
                } finally { k.release() }
                collectQuadCandidates(tmp, gray, rgba, w, h, name, out)
            }
            // Grayscale Otsu remains useful for white paper on black keyboards and dark desks.
            Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            try { Imgproc.morphologyEx(mask, tmp, Imgproc.MORPH_CLOSE, k, Point(-1.0, -1.0), 2) }
            finally { k.release() }
            collectQuadCandidates(tmp, gray, rgba, w, h, "otsu-light", out)
        } finally { rgb.release(); hsv.release(); mask.release(); tmp.release() }
    }

    suspend fun perspective(bitmap: Bitmap, quad: DocumentQuad, cleanupEdges: Boolean = true): Bitmap = withContext(Dispatchers.Default) {
        ensureOpenCv(); val q=quad.clamped(); val w=bitmap.width.toDouble(); val h=bitmap.height.toDouble()
        val p=q.points().map { Point((it.x*w).toDouble(), (it.y*h).toDouble()) }
        val width=max(distance(p[0],p[1]),distance(p[3],p[2])).roundToInt().coerceAtLeast(64)
        val height=max(distance(p[0],p[3]),distance(p[1],p[2])).roundToInt().coerceAtLeast(64)
        val src=Mat();val dst=Mat(); val srcPts=MatOfPoint2f(*p.toTypedArray()); val dstPts=MatOfPoint2f(Point(0.0,0.0),Point(width-1.0,0.0),Point(width-1.0,height-1.0),Point(0.0,height-1.0)); val transform=Imgproc.getPerspectiveTransform(srcPts,dstPts)
        try {
            Utils.bitmapToMat(bitmap,src)
            Imgproc.warpPerspective(src,dst,transform,Size(width.toDouble(),height.toDouble()),Imgproc.INTER_CUBIC,Core.BORDER_CONSTANT,Scalar(255.0,255.0,255.0,255.0))
            val out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Utils.matToBitmap(dst,out)
            if(cleanupEdges) cleanupOutsidePaper(cleanupEdgeConnectedDark(out)) else out
        } finally {src.release();dst.release();srcPts.release();dstPts.release();transform.release()}
    }

    fun releaseEnhanced() = enhancedEngine.release()

    private fun refineEnhanced(bitmap: Bitmap, proposal: EnhancedScanProposal): ScanDetection {
        ensureOpenCv()
        val mat = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            val refined = refineCorners(gray, proposal.quad, bitmap.width, bitmap.height)
            ScanDetection(refined, ScanEngine.ENHANCED, proposal.confidence.coerceIn(0.35f, 0.99f))
        } finally {
            mat.release(); gray.release()
        }
    }

    private fun detectLightSheet(gray: Mat, w: Int, h: Int): Pair<DocumentQuad, Double>? {
        val binary=Mat();val opened=Mat();val hierarchy=Mat();val contours=mutableListOf<MatOfPoint>()
        val kernelSize=(minOf(w,h)/220).coerceIn(3,9).let{if(it%2==0)it+1 else it}
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(kernelSize.toDouble(),kernelSize.toDouble()))
        try{
            Imgproc.threshold(gray,binary,0.0,255.0,Imgproc.THRESH_BINARY+Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary,opened,Imgproc.MORPH_OPEN,kernel,Point(-1.0,-1.0),1)
            Imgproc.findContours(opened,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            val total=w.toDouble()*h
            var best:DocumentQuad?=null;var bestScore=0.0
            contours.sortedByDescending{Imgproc.contourArea(it)}.take(24).forEach{contour->
                val area=Imgproc.contourArea(contour);val ratio=area/total
                if(ratio<0.045||ratio>0.90)return@forEach
                val c2=MatOfPoint2f(*contour.toArray());val approx=MatOfPoint2f()
                try{
                    val peri=Imgproc.arcLength(c2,true);var p:Array<Point>?=null
                    for(eps in doubleArrayOf(.012,.018,.025,.035,.05,.07)){
                        Imgproc.approxPolyDP(c2,approx,peri*eps,true)
                        if(approx.total()==4L){p=order(approx.toArray());break}
                    }
                    val pts=p?:return@forEach
                    val poly=MatOfPoint(*pts);val convex=try{Imgproc.isContourConvex(poly)}finally{poly.release()}
                    if(!convex)return@forEach
                    val q=normalized(pts,w,h);if(!q.isReasonable())return@forEach
                    val anglePenalty=maxCornerCosine(pts).coerceIn(0.0,.85)
                    val edgeLengths=(0..3).map{distance(pts[it],pts[(it+1)%4])}
                    val skinny=(edgeLengths.minOrNull()?:0.0)/(edgeLengths.maxOrNull()?.coerceAtLeast(1.0)?:1.0)
                    if(skinny<0.12)return@forEach
                    val touches=pts.count{it.x<w*.025||it.x>w*.975||it.y<h*.025||it.y>h*.975}
                    // Prefer a reasonably rectangular isolated bright region; penalize a contour
                    // that is basically the whole camera frame.
                    val score=ratio*(1.0-anglePenalty)*(.75+.25*skinny)*(if(touches>=3).45 else 1.0)
                    if(score>bestScore){best=q;bestScore=score}
                }finally{c2.release();approx.release()}
            }
            return best?.let{it to bestScore}
        }finally{contours.forEach{it.release()};kernel.release();binary.release();opened.release();hierarchy.release()}
    }

    private fun refineCorners(gray: Mat,q: DocumentQuad,w:Int,h:Int):DocumentQuad{
        val pts=MatOfPoint2f(*q.points().map{Point((it.x*w).toDouble(), (it.y*h).toDouble())}.toTypedArray())
        runCatching{Imgproc.cornerSubPix(gray,pts,Size(13.0,13.0),Size(-1.0,-1.0),TermCriteria(TermCriteria.EPS+TermCriteria.MAX_ITER,24,0.03))}
        val p=pts.toArray();pts.release();if(p.size!=4)return q
        return normalized(p,w,h).takeIf{it.isReasonable()}?:q
    }

    private fun cleanupEdgeConnectedDark(src: Bitmap):Bitmap{
        val w=src.width;val h=src.height;if(w<80||h<80)return src
        val px=IntArray(w*h);src.getPixels(px,0,w,0,0,w,h);val seen=BooleanArray(px.size);val queue=IntArray(px.size);val whiten=BooleanArray(px.size);val minDeep=max(6,(minOf(w,h)*0.012f).roundToInt());val minArea=max(100,(w*h*0.002f).roundToInt())
        fun dark(i:Int):Boolean{val p=px[i];val lum=(((p shr 16) and 255)*30+((p shr 8) and 255)*59+(p and 255)*11)/100;return lum<96}
        fun scan(seed:Int){
            if(seen[seed]||!dark(seed))return
            var head=0;var tail=0;queue[tail++]=seed;seen[seed]=true;var maxDepth=0;var minX=w;var maxX=0;var minY=h;var maxY=0;var touches=0
            var touchL=false;var touchR=false;var touchT=false;var touchB=false
            while(head<tail){val i=queue[head++];val x=i%w;val y=i/w;minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y);if(x==0)touchL=true;if(x==w-1)touchR=true;if(y==0)touchT=true;if(y==h-1)touchB=true;maxDepth=max(maxDepth,minOf(x,y,w-1-x,h-1-y));if(x>0){val n=i-1;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}};if(x<w-1){val n=i+1;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}};if(y>0){val n=i-w;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}};if(y<h-1){val n=i+w;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}}}
            touches=listOf(touchL,touchR,touchT,touchB).count{it};val spanX=(maxX-minX+1)/w.toFloat();val spanY=(maxY-minY+1)/h.toFloat();val likelyIntentionalFrame=touches>=3&&spanX>.75f&&spanY>.75f&&maxDepth<=max(2,(minOf(w,h)*.015f).roundToInt())
            if(!likelyIntentionalFrame&&tail>=minArea&&maxDepth>=minDeep)for(i in 0 until tail)whiten[queue[i]]=true
        }
        for(x in 0 until w){scan(x);scan((h-1)*w+x)};for(y in 1 until h-1){scan(y*w);scan(y*w+w-1)}
        var changed=false;for(i in px.indices)if(whiten[i]){px[i]=Color.WHITE;changed=true};if(!changed)return src
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{it.setPixels(px,0,w,0,0,w,h);src.recycle()}
    }

    /**
     * If a slightly loose quad includes a keyboard/table around the paper, keep the output size but
     * whiten everything outside the dominant light sheet. This is deliberately conservative: it
     * only runs when one isolated bright region already occupies most of the rectified image.
     */
    private fun cleanupOutsidePaper(src: Bitmap): Bitmap {
        val mat=Mat();val gray=Mat();val binary=Mat();val hierarchy=Mat();val contours=mutableListOf<MatOfPoint>()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(7.0,7.0))
        try{
            Utils.bitmapToMat(src,mat);Imgproc.cvtColor(mat,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray,binary,0.0,255.0,Imgproc.THRESH_BINARY+Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_CLOSE,kernel,Point(-1.0,-1.0),2)
            Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            val contour=contours.maxByOrNull{Imgproc.contourArea(it)}?:return src
            val ratio=Imgproc.contourArea(contour)/(src.width.toDouble()*src.height)
            if(ratio<0.52||ratio>0.985)return src
            val mask=Mat.zeros(src.height,src.width,CvType.CV_8UC1);val inv=Mat()
            try{
                Imgproc.drawContours(mask,listOf(contour),0,Scalar(255.0),Imgproc.FILLED)
                Core.bitwise_not(mask,inv)
                mat.setTo(Scalar(255.0,255.0,255.0,255.0),inv)
                val out=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888);Utils.matToBitmap(mat,out);src.recycle();return out
            }finally{mask.release();inv.release()}
        }finally{contours.forEach{it.release()};kernel.release();mat.release();gray.release();binary.release();hierarchy.release()}
    }

    private fun normalized(p:Array<Point>,w:Int,h:Int)=DocumentQuad(QuadPoint((p[0].x/w).toFloat(),(p[0].y/h).toFloat()),QuadPoint((p[1].x/w).toFloat(),(p[1].y/h).toFloat()),QuadPoint((p[2].x/w).toFloat(),(p[2].y/h).toFloat()),QuadPoint((p[3].x/w).toFloat(),(p[3].y/h).toFloat())).clamped()
    private fun order(points:Array<Point>):Array<Point>{val pts=points.toList();val tl=pts.minBy{it.x+it.y};val br=pts.maxBy{it.x+it.y};val tr=pts.maxBy{it.x-it.y};val bl=pts.minBy{it.x-it.y};return arrayOf(tl,tr,br,bl)}
    private fun maxCornerCosine(p:Array<Point>):Double{var m=0.0;for(i in 0..3){val a=p[(i+3)%4];val b=p[i];val c=p[(i+1)%4];val ux=a.x-b.x;val uy=a.y-b.y;val vx=c.x-b.x;val vy=c.y-b.y;val d=(ux*vx+uy*vy)/(hypot(ux,uy)*hypot(vx,vy)+1e-6);m=max(m,abs(d))};return m}
    private fun quadGeometryPenalty(q: DocumentQuad): Double {
        if(!q.isReasonable())return 999.0
        val p=q.points().map{Point(it.x.toDouble(),it.y.toDouble())}.toTypedArray()
        val angle=maxCornerCosine(p).coerceIn(0.0,.95)
        val edges=(0..3).map{distance(p[it],p[(it+1)%4])}
        val skinny=(edges.minOrNull()?:0.0)/(edges.maxOrNull()?.coerceAtLeast(1e-6)?:1.0)
        val area=q.area.toDouble()
        return angle*3.0+(if(skinny<.08)2.0 else 0.0)+(if(area<.015)2.0 else 0.0)
    }

    private fun quadDistance(a: DocumentQuad, b: DocumentQuad): Float = a.points().zip(b.points()).map { (p,q) -> hypot((p.x-q.x).toDouble(), (p.y-q.y).toDouble()) }.average().toFloat()
    private fun distance(a:Point,b:Point)=hypot(a.x-b.x,a.y-b.y)
    private fun ensureOpenCv(){if(!OpenCVHolder.ready)error("OpenCV 初始化失败")}
    private object OpenCVHolder{val ready:Boolean by lazy{OpenCVLoader.initLocal()}}
}
