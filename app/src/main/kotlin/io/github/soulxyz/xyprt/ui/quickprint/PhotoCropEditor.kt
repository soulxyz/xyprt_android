package io.github.soulxyz.xyprt.ui.quickprint

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.soulxyz.xyprt.scanner.DocumentQuad
import io.github.soulxyz.xyprt.scanner.QuadPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private enum class QuadHandle { TL, TR, BR, BL }

/** Four-corner document surface. The user always has final control over the automatic detector. */
@Composable
fun PhotoCropEditor(
    uri: Uri,
    quad: DocumentQuad,
    onQuadChange: (DocumentQuad) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { QuickPrintRenderer.previewBitmap(context, uri, 1400) }.getOrNull() }
    }
    val density=LocalDensity.current;val hitPx=with(density){42.dp.toPx()};val handleRadius=with(density){7.dp.toPx()}
    var boxSize by remember{mutableStateOf(IntSize.Zero)};var active by remember{mutableStateOf<QuadHandle?>(null)};val latest by rememberUpdatedState(quad)
    val primary=MaterialTheme.colorScheme.primary;val bg=MaterialTheme.colorScheme.surfaceContainerHighest

    Box(modifier.fillMaxWidth().height(380.dp).background(bg,RoundedCornerShape(20.dp)).onSizeChanged{boxSize=it}){
        val bmp=bitmap
        if(bmp!=null&&boxSize.width>0&&boxSize.height>0){
            Image(bmp.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit,filterQuality=FilterQuality.Medium)
            val scale=min(boxSize.width/bmp.width.toFloat(),boxSize.height/bmp.height.toFloat());val dw=bmp.width*scale;val dh=bmp.height*scale;val ox=(boxSize.width-dw)/2f;val oy=(boxSize.height-dh)/2f
            fun screen(p:QuadPoint)=Offset(ox+p.x*dw,oy+p.y*dh)
            fun norm(p:Offset)=QuadPoint(((p.x-ox)/dw).coerceIn(0f,1f),((p.y-oy)/dh).coerceIn(0f,1f))
            fun handles(q:DocumentQuad)=listOf(QuadHandle.TL to screen(q.topLeft),QuadHandle.TR to screen(q.topRight),QuadHandle.BR to screen(q.bottomRight),QuadHandle.BL to screen(q.bottomLeft))
            Canvas(Modifier.fillMaxSize().pointerInput(uri,boxSize){
                detectDragGestures(onDragStart={pos->active=handles(latest).minByOrNull{(_,p)->(p-pos).getDistance()}?.takeIf{(_,p)->(p-pos).getDistance()<=hitPx}?.first},onDragEnd={active=null},onDragCancel={active=null},onDrag={change,_->
                    val h=active?:return@detectDragGestures;change.consume();val p=norm(change.position);val next=when(h){QuadHandle.TL->latest.copy(topLeft=p);QuadHandle.TR->latest.copy(topRight=p);QuadHandle.BR->latest.copy(bottomRight=p);QuadHandle.BL->latest.copy(bottomLeft=p)}.clamped();if(next.area>0.015f)onQuadChange(next)
                })
            }){
                val p=quad.points().map(::screen);val path=Path().apply{moveTo(p[0].x,p[0].y);lineTo(p[1].x,p[1].y);lineTo(p[2].x,p[2].y);lineTo(p[3].x,p[3].y);close()}
                drawPath(path,Color.Black.copy(alpha=.10f));drawPath(path,primary,style=Stroke(width=2.dp.toPx()))
                // Edge midpoints make perspective obvious without adding more drag targets.
                for(i in 0..3){val a=p[i];val b=p[(i+1)%4];drawCircle(primary.copy(alpha=.55f),radius=2.5.dp.toPx(),center=Offset((a.x+b.x)/2,(a.y+b.y)/2))}
                handles(quad).forEach{(_,pt)->drawCircle(Color.White,handleRadius+2.dp.toPx(),pt);drawCircle(primary,handleRadius,pt)}
            }
        }
    }
}
