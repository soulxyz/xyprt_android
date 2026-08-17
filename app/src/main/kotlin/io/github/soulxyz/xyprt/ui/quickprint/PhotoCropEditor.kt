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

private enum class QuadHandle { TL, TOP, TR, RIGHT, BR, BOTTOM, BL, LEFT }

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

    Box(modifier.fillMaxWidth().height(480.dp).background(bg,RoundedCornerShape(20.dp)).onSizeChanged{boxSize=it}){
        val bmp=bitmap
        if(bmp!=null&&boxSize.width>0&&boxSize.height>0){
            Image(bmp.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit,filterQuality=FilterQuality.Medium)
            val scale=min(boxSize.width/bmp.width.toFloat(),boxSize.height/bmp.height.toFloat());val dw=bmp.width*scale;val dh=bmp.height*scale;val ox=(boxSize.width-dw)/2f;val oy=(boxSize.height-dh)/2f
            fun screen(p:QuadPoint)=Offset(ox+p.x*dw,oy+p.y*dh)
            fun norm(p:Offset)=QuadPoint(((p.x-ox)/dw).coerceIn(0f,1f),((p.y-oy)/dh).coerceIn(0f,1f))
            fun handles(q:DocumentQuad):List<Pair<QuadHandle,Offset>> {
                val tl=screen(q.topLeft);val tr=screen(q.topRight);val br=screen(q.bottomRight);val bl=screen(q.bottomLeft)
                fun mid(a:Offset,b:Offset)=Offset((a.x+b.x)/2f,(a.y+b.y)/2f)
                return listOf(
                    QuadHandle.TL to tl, QuadHandle.TOP to mid(tl,tr), QuadHandle.TR to tr,
                    QuadHandle.RIGHT to mid(tr,br), QuadHandle.BR to br, QuadHandle.BOTTOM to mid(br,bl),
                    QuadHandle.BL to bl, QuadHandle.LEFT to mid(bl,tl),
                )
            }
            Canvas(Modifier.fillMaxSize().pointerInput(uri,boxSize){
                detectDragGestures(
                    onDragStart={pos->active=handles(latest).minByOrNull{(_,p)->(p-pos).getDistance()}?.takeIf{(_,p)->(p-pos).getDistance()<=hitPx}?.first},
                    onDragEnd={active=null},onDragCancel={active=null},
                    onDrag={change,dragAmount->
                        val h=active?:return@detectDragGestures;change.consume();val p=norm(change.position)
                        val dx=dragAmount.x/dw;val dy=dragAmount.y/dh
                        fun moved(q:QuadPoint)=QuadPoint((q.x+dx).coerceIn(0f,1f),(q.y+dy).coerceIn(0f,1f))
                        val q=latest
                        val next=when(h){
                            QuadHandle.TL->q.copy(topLeft=p)
                            QuadHandle.TR->q.copy(topRight=p)
                            QuadHandle.BR->q.copy(bottomRight=p)
                            QuadHandle.BL->q.copy(bottomLeft=p)
                            QuadHandle.TOP->q.copy(topLeft=moved(q.topLeft),topRight=moved(q.topRight))
                            QuadHandle.RIGHT->q.copy(topRight=moved(q.topRight),bottomRight=moved(q.bottomRight))
                            QuadHandle.BOTTOM->q.copy(bottomRight=moved(q.bottomRight),bottomLeft=moved(q.bottomLeft))
                            QuadHandle.LEFT->q.copy(bottomLeft=moved(q.bottomLeft),topLeft=moved(q.topLeft))
                        }.clamped();if(next.area>0.015f)onQuadChange(next)
                    }
                )
            }){
                val p=quad.points().map(::screen);val path=Path().apply{moveTo(p[0].x,p[0].y);lineTo(p[1].x,p[1].y);lineTo(p[2].x,p[2].y);lineTo(p[3].x,p[3].y);close()}
                drawPath(path,Color.Black.copy(alpha=.10f));drawPath(path,primary,style=Stroke(width=2.dp.toPx()))
                handles(quad).forEach{(kind,pt)->
                    val corner=kind==QuadHandle.TL||kind==QuadHandle.TR||kind==QuadHandle.BR||kind==QuadHandle.BL
                    val r=if(corner)handleRadius else handleRadius*.82f
                    drawCircle(Color.White,r+2.dp.toPx(),pt);drawCircle(primary.copy(alpha=if(corner)1f else .78f),r,pt)
                }
            }
        }
    }
}
