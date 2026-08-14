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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
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
class DocumentScanner(private val models: EnhancedModelRepository) {
    @Volatile private var sessionKey: String? = null
    @Volatile private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val sessionLock = Any()

    suspend fun detect(bitmap: Bitmap, preferEnhanced: Boolean = true): ScanDetection = withContext(Dispatchers.Default) {
        if (preferEnhanced) {
            val item=models.bestInstalled()
            if(item!=null){
                enhancedDetect(bitmap,item)?.let { return@withContext it }
            }
        }
        standardDetect(bitmap)
    }

    fun standardDetect(bitmap: Bitmap): ScanDetection {
        ensureOpenCv()
        // Edge search is intentionally capped: Canny/contour work on multi-megapixel photos makes
        // the editor feel frozen and does not improve the initial quad much. The chosen quad is
        // normalized and then refined against the original-resolution grayscale image.
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
        val src=Mat(); val gray=Mat(); val blur=Mat(); val edges=Mat(); val closed=Mat(); val hierarchy=Mat()
        try {
            Utils.bitmapToMat(detectBitmap,src)
            Imgproc.cvtColor(src,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,blur,Size(5.0,5.0),0.0)
            Imgproc.Canny(blur,edges,55.0,165.0)
            val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(5.0,5.0))
            Imgproc.morphologyEx(edges,closed,Imgproc.MORPH_CLOSE,kernel,Point(-1.0,-1.0),2)
            kernel.release()
            val contours=mutableListOf<MatOfPoint>()
            Imgproc.findContours(closed,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
            val total=detectBitmap.width.toDouble()*detectBitmap.height
            var best: DocumentQuad?=null; var bestScore=0.0
            val candidates=contours.sortedByDescending { Imgproc.contourArea(it) }.take(80)
            candidates.forEach { contour ->
                val area=Imgproc.contourArea(contour)
                if(area<total*0.06) return@forEach
                val c2=MatOfPoint2f(*contour.toArray()); val approx=MatOfPoint2f()
                try {
                    val peri=Imgproc.arcLength(c2,true)
                    Imgproc.approxPolyDP(c2,approx,peri*0.018,true)
                    if(approx.total()==4L){
                        val p=order(approx.toArray())
                        val q=normalized(p,detectBitmap.width,detectBitmap.height)
                        val poly=MatOfPoint(*p)
                        val convex=try { Imgproc.isContourConvex(poly) } finally { poly.release() }
                        if(convex && q.isReasonable()){
                            val areaRatio=area/total
                            val anglePenalty=maxCornerCosine(p)
                            val borderBonus=p.count { it.x<detectBitmap.width*0.08||it.x>detectBitmap.width*0.92||it.y<detectBitmap.height*0.08||it.y>detectBitmap.height*0.92 }*0.015
                            val score=areaRatio*(1.0-anglePenalty.coerceIn(0.0,0.75))+borderBonus
                            if(score>bestScore){bestScore=score;best=q}
                        }
                    }
                } finally { c2.release(); approx.release() }
            }
            contours.forEach { it.release() }
            val initial=best?:DocumentQuad()
            val refined = if (detectBitmap === bitmap) {
                refineCorners(gray,initial,bitmap.width,bitmap.height)
            } else {
                val full=Mat(); val fullGray=Mat()
                try {
                    Utils.bitmapToMat(bitmap,full); Imgproc.cvtColor(full,fullGray,Imgproc.COLOR_RGBA2GRAY)
                    refineCorners(fullGray,initial,bitmap.width,bitmap.height)
                } finally { full.release(); fullGray.release() }
            }
            return ScanDetection(refined,ScanEngine.STANDARD,bestScore.coerceIn(0.25,0.92).toFloat())
        } finally {
            if(detectBitmap !== bitmap) detectBitmap.recycle()
            src.release();gray.release();blur.release();edges.release();closed.release();hierarchy.release()
        }
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
            if(cleanupEdges) cleanupEdgeConnectedDark(out) else out
        } finally {src.release();dst.release();srcPts.release();dstPts.release();transform.release()}
    }

    fun releaseEnhanced()=synchronized(sessionLock){session?.close();session=null;sessionKey=null}

    private suspend fun enhancedDetect(bitmap: Bitmap,item: EnhancedCapability): ScanDetection? {
        var bytes=models.decryptInstalled(item)
        if(bytes==null){
            val refreshed=withTimeoutOrNull(1800){models.ensureLease(item.id)}==true
            if(refreshed) bytes=models.decryptInstalled(item)
        }
        bytes?:return null
        val s=runCatching { sessionFor(item,bytes!!) }.getOrNull().also { bytes!!.fill(0) } ?: return null
        return runCatching { inferQuad(bitmap,s) }.getOrNull()?.takeIf { it.quad.isReasonable() }
    }

    private fun sessionFor(item: EnhancedCapability,bytes: ByteArray): OrtSession = synchronized(sessionLock){
        val key="${item.id}:${item.version}"
        session?.takeIf{sessionKey==key}?.let{return it}
        session?.close();session=null
        val opts=OrtSession.SessionOptions().apply{
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val threads=(Runtime.getRuntime().availableProcessors()/2).coerceIn(2,4)
            setIntraOpNumThreads(threads);setInterOpNumThreads(1)
            runCatching{addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))}
        }
        try { env.createSession(bytes,opts).also{session=it;sessionKey=key} } finally { opts.close() }
    }

    private fun inferQuad(bitmap: Bitmap,s: OrtSession): ScanDetection {
        val size=256
        val scale=minOf(size/bitmap.width.toFloat(),size/bitmap.height.toFloat())
        val dw=(bitmap.width*scale).roundToInt();val dh=(bitmap.height*scale).roundToInt();val ox=(size-dw)/2;val oy=(size-dh)/2
        val inputBmp=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        Canvas(inputBmp).apply{drawColor(Color.rgb(128,128,128));drawBitmap(bitmap,null,Rect(ox,oy,ox+dw,oy+dh),Paint(Paint.FILTER_BITMAP_FLAG))}
        val px=IntArray(size*size);inputBmp.getPixels(px,0,size,0,0,size,size);inputBmp.recycle()
        val data=FloatArray(3*size*size)
        for(i in px.indices){val p=px[i];data[i]=((p shr 16) and 255)/255f;data[size*size+i]=((p shr 8) and 255)/255f;data[2*size*size+i]=(p and 255)/255f}
        OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,256,256)).use { tensor ->
            s.run(mapOf("input" to tensor)).use { result ->
                val value=result.get("corner_heatmaps").orElseThrow().value as Array<*>
                @Suppress("UNCHECKED_CAST") val batch=value as Array<Array<Array<FloatArray>>>
                val hm=batch[0];val pts=ArrayList<QuadPoint>(4);var confidence=0f
                for(ch in 0..3){
                    val a=hm[ch];var bx=0;var by=0;var best=-Float.MAX_VALUE
                    for(y in a.indices)for(x in a[y].indices)if(a[y][x]>best){best=a[y][x];bx=x;by=y}
                    confidence+=sigmoid(best)
                    var sx=0.0;var sy=0.0;var sw=0.0
                    for(y in (by-2).coerceAtLeast(0)..(by+2).coerceAtMost(63))for(x in (bx-2).coerceAtLeast(0)..(bx+2).coerceAtMost(63)){
                        val ww=exp((a[y][x]-best).coerceIn(-12f,0f).toDouble());sx+=(x+0.5)*ww;sy+=(y+0.5)*ww;sw+=ww
                    }
                    val hx=(sx/sw*4.0).toFloat();val hy=(sy/sw*4.0).toFloat()
                    pts+=modelPointToNormalized(hx,hy,ox,oy,scale,bitmap.width,bitmap.height)
                }
                val heatQuad=DocumentQuad(pts[0],pts[1],pts[2],pts[3]).clamped()
                val avgConfidence=(confidence/4f).coerceIn(0f,1f)
                val maskQuad = runCatching {
                    val mv=result.get("mask_logits").orElseThrow().value as Array<*>
                    @Suppress("UNCHECKED_CAST") val mb=mv as Array<Array<Array<FloatArray>>>
                    maskQuadFromLogits(mb[0][0],ox,oy,scale,bitmap.width,bitmap.height)
                }.getOrNull()
                // Heatmaps are usually the most precise; the mask is a strong fallback for weak or
                // geometrically suspicious peaks. Large disagreement also favors the mask when its
                // contour is clearly plausible.
                val selected=when{
                    heatQuad.isReasonable() && avgConfidence>=0.55f -> heatQuad
                    maskQuad?.isReasonable()==true -> maskQuad
                    heatQuad.isReasonable() -> heatQuad
                    else -> return ScanDetection(DocumentQuad(),ScanEngine.ENHANCED,0f)
                }
                ensureOpenCv();val mat=Mat();val gray=Mat()
                try{
                    Utils.bitmapToMat(bitmap,mat);Imgproc.cvtColor(mat,gray,Imgproc.COLOR_RGBA2GRAY)
                    val refined=refineCorners(gray,selected,bitmap.width,bitmap.height)
                    return ScanDetection(refined,ScanEngine.ENHANCED,avgConfidence.coerceIn(0.35f,0.99f))
                }finally{mat.release();gray.release()}
            }
        }
    }

    private fun modelPointToNormalized(hx:Float,hy:Float,ox:Int,oy:Int,scale:Float,w:Int,h:Int):QuadPoint{
        val ix=((hx-ox)/scale).coerceIn(0f,w.toFloat());val iy=((hy-oy)/scale).coerceIn(0f,h.toFloat())
        return QuadPoint(ix/w,iy/h)
    }

    private fun maskQuadFromLogits(mask:Array<FloatArray>,ox:Int,oy:Int,scale:Float,w:Int,h:Int):DocumentQuad?{
        if(mask.isEmpty()||mask[0].isEmpty())return null
        val mh=mask.size;val mw=mask[0].size;val binary=Mat(mh,mw,CvType.CV_8UC1);val bytes=ByteArray(mw*mh)
        var i=0;for(y in 0 until mh)for(x in 0 until mw)bytes[i++]=if(mask[y][x]>0f)255.toByte() else 0
        binary.put(0,0,bytes)
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(3.0,3.0));val hierarchy=Mat();val contours=mutableListOf<MatOfPoint>()
        try{
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            val contour=contours.maxByOrNull{Imgproc.contourArea(it)}?:return null
            if(Imgproc.contourArea(contour)<mw*mh*0.08)return null
            val c2=MatOfPoint2f(*contour.toArray());val approx=MatOfPoint2f()
            try{
                val peri=Imgproc.arcLength(c2,true);var points:Array<Point>?=null
                for(eps in doubleArrayOf(.018,.025,.035,.05,.07)){
                    Imgproc.approxPolyDP(c2,approx,peri*eps,true)
                    if(approx.total()==4L){points=order(approx.toArray());break}
                }
                val p=points?:return null
                val mapped=p.map{pt->modelPointToNormalized(((pt.x+.5)*4.0).toFloat(),((pt.y+.5)*4.0).toFloat(),ox,oy,scale,w,h)}
                return DocumentQuad(mapped[0],mapped[1],mapped[2],mapped[3]).clamped().takeIf{it.isReasonable()}
            }finally{c2.release();approx.release()}
        }finally{contours.forEach{it.release()};kernel.release();hierarchy.release();binary.release()}
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
        fun dark(i:Int):Boolean{val p=px[i];val lum=(((p shr 16) and 255)*30+((p shr 8) and 255)*59+(p and 255)*11)/100;return lum<78}
        fun scan(seed:Int){
            if(seen[seed]||!dark(seed))return
            var head=0;var tail=0;queue[tail++]=seed;seen[seed]=true;var maxDepth=0;var minX=w;var maxX=0;var minY=h;var maxY=0;var touches=0
            var touchL=false;var touchR=false;var touchT=false;var touchB=false
            while(head<tail){val i=queue[head++];val x=i%w;val y=i/w;minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y);if(x==0)touchL=true;if(x==w-1)touchR=true;if(y==0)touchT=true;if(y==h-1)touchB=true;maxDepth=max(maxDepth,minOf(x,y,w-1-x,h-1-y));if(x>0){val n=i-1;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}};if(x<w-1){val n=i+1;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}};if(y>0){val n=i-w;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}};if(y<h-1){val n=i+w;if(!seen[n]&&dark(n)){seen[n]=true;queue[tail++]=n}}}
            touches=listOf(touchL,touchR,touchT,touchB).count{it};val spanX=(maxX-minX+1)/w.toFloat();val spanY=(maxY-minY+1)/h.toFloat();val likelyIntentionalFrame=touches>=3&&spanX>.75f&&spanY>.75f&&maxDepth<minOf(w,h)*.06f
            if(!likelyIntentionalFrame&&tail>=minArea&&maxDepth>=minDeep)for(i in 0 until tail)whiten[queue[i]]=true
        }
        for(x in 0 until w){scan(x);scan((h-1)*w+x)};for(y in 1 until h-1){scan(y*w);scan(y*w+w-1)}
        var changed=false;for(i in px.indices)if(whiten[i]){px[i]=Color.WHITE;changed=true};if(!changed)return src
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{it.setPixels(px,0,w,0,0,w,h);src.recycle()}
    }

    private fun normalized(p:Array<Point>,w:Int,h:Int)=DocumentQuad(QuadPoint((p[0].x/w).toFloat(),(p[0].y/h).toFloat()),QuadPoint((p[1].x/w).toFloat(),(p[1].y/h).toFloat()),QuadPoint((p[2].x/w).toFloat(),(p[2].y/h).toFloat()),QuadPoint((p[3].x/w).toFloat(),(p[3].y/h).toFloat())).clamped()
    private fun order(points:Array<Point>):Array<Point>{val pts=points.toList();val tl=pts.minBy{it.x+it.y};val br=pts.maxBy{it.x+it.y};val tr=pts.maxBy{it.x-it.y};val bl=pts.minBy{it.x-it.y};return arrayOf(tl,tr,br,bl)}
    private fun maxCornerCosine(p:Array<Point>):Double{var m=0.0;for(i in 0..3){val a=p[(i+3)%4];val b=p[i];val c=p[(i+1)%4];val ux=a.x-b.x;val uy=a.y-b.y;val vx=c.x-b.x;val vy=c.y-b.y;val d=(ux*vx+uy*vy)/(hypot(ux,uy)*hypot(vx,vy)+1e-6);m=max(m,abs(d))};return m}
    private fun distance(a:Point,b:Point)=hypot(a.x-b.x,a.y-b.y)
    private fun sigmoid(v:Float)=(1.0/(1.0+exp(-v.toDouble()))).toFloat()
    private fun ensureOpenCv(){if(!OpenCVHolder.ready)error("OpenCV 初始化失败")}
    private object OpenCVHolder{val ready:Boolean by lazy{OpenCVLoader.initLocal()}}
}
