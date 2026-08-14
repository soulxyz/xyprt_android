package io.github.soulxyz.xyprt.ui.cocreator

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.soulxyz.xyprt.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val SPONSOR_QR_URL = "https://api.xyprt.5am.top/sponsor.jpg"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoCreatorScreen(onBack: () -> Unit, onOpenCapabilities: () -> Unit) {
    val context=LocalContext.current;val container=remember{(context.applicationContext as App).container};val repo=container.coCreator;val state by repo.state.collectAsState();val scope=rememberCoroutineScope()
    var code by remember{mutableStateOf("")};var activating by remember{mutableStateOf(false)};var celebrate by remember{mutableStateOf(false)};var showSupport by remember{mutableStateOf(false)};var qrBytes by remember{mutableStateOf<ByteArray?>(null)};var qrLoading by remember{mutableStateOf(false)};var confirmQr by remember{mutableStateOf(false)};var pendingSave by remember{mutableStateOf<ByteArray?>(null)}

    fun doSave(bytes:ByteArray){scope.launch{val ok=withContext(Dispatchers.IO){saveQr(context,bytes)};if(ok){openWechat(context);Toast.makeText(context,"二维码已保存，可在微信扫一扫中从相册选择",Toast.LENGTH_LONG).show()}else Toast.makeText(context,"二维码保存失败",Toast.LENGTH_SHORT).show()}}
    val storagePermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->pendingSave?.let{bytes->pendingSave=null;if(granted)doSave(bytes)else Toast.makeText(context,"未获得相册写入权限",Toast.LENGTH_SHORT).show()}}

    LaunchedEffect(showSupport){if(showSupport&&qrBytes==null&&!qrLoading){qrLoading=true;qrBytes=runCatching{container.serverApi.downloadAbsolute(SPONSOR_QR_URL,5L*1024*1024)}.getOrNull();qrLoading=false}}

    Scaffold(topBar={TopAppBar(title={Text("共创者计划")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)}})}){pad->
        Box(Modifier.padding(pad).fillMaxSize()){
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                Card(colors=CardDefaults.cardColors(containerColor=if(state.active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)){
                    Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)){
                        Text(if(state.active)"共创者权益已启用" else "一起把口袋小印做得更好",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
                        Text(if(state.active)"感谢你的支持。你可以优先参与实验能力和新方案验证。" else "社区开源版会持续维护。共创者计划主要用于支持服务器、存储、下载分发、开发工具，以及模型训练与持续优化。",textAlign=TextAlign.Center,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.editionLabel,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        if(state.active) Button(onClick=onOpenCapabilities){Text("管理增强能力")}
                    }
                }
                if(!state.active){
                    Text("卡密激活",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    OutlinedTextField(value=code,onValueChange={code=it.uppercase()},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("输入卡密")},placeholder={Text("XXXX-XXXX-XXXX-XXXX")})
                    Button(enabled=code.isNotBlank()&&!activating,onClick={scope.launch{activating=true;val result=repo.activate(code);activating=false;if(result.isSuccess){celebrate=true;code=""}else Toast.makeText(context,result.exceptionOrNull()?.message?:"激活失败",Toast.LENGTH_LONG).show()}}){if(activating)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("激活当前设备")}
                }
                Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    Text("共创者可以获得什么",fontWeight=FontWeight.SemiBold)
                    Text("• 优先体验仍在灰度/内测阶段的创新功能\n• 意见与设备适配需求会被更优先地听取\n• 合理范围内的使用、开发、AI Coding、Vibe Coding 等交流支持\n• 部分实验资源、素材与后续增强能力",style=MaterialTheme.typography.bodyMedium)
                    Text("实验能力会持续迭代，成熟后其中一部分也可能进入社区版本。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
                TextButton(onClick={showSupport=!showSupport}){Text(if(showSupport)"收起支持入口" else "没有卡密？想支持一下项目")}
                if(showSupport){
                    Card{Column(Modifier.fillMaxWidth().padding(14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("自愿支持",fontWeight=FontWeight.SemiBold);Text("不影响社区版正常更新与核心功能。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        when{qrLoading->CircularProgressIndicator();qrBytes!=null->{val bmp=remember(qrBytes){BitmapFactory.decodeByteArray(qrBytes,0,qrBytes!!.size)};if(bmp!=null)Image(bmp.asImageBitmap(),"赞助二维码",Modifier.size(220.dp).clickable{confirmQr=true})};else->Text("二维码暂时加载失败")}
                        Text("点击二维码后，会先征得你的确认，再保存到相册并尝试打开微信。",style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
                    }}
                }
                Spacer(Modifier.height(24.dp))
            }
            if(celebrate) ConfettiCelebration(onFinished={celebrate=false})
        }
    }
    if(confirmQr){AlertDialog(onDismissRequest={confirmQr=false},title={Text("保存二维码并打开微信？")},text={Text("口袋小印会把当前二维码保存到相册，并尝试打开微信。若无法直接进入扫一扫，请在微信扫一扫中选择刚保存的图片。")},confirmButton={TextButton(onClick={confirmQr=false;val bytes=qrBytes?:return@TextButton;if(Build.VERSION.SDK_INT<=28&&ContextCompat.checkSelfPermission(context,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){pendingSave=bytes;storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)}else doSave(bytes)}){Text("继续")}},dismissButton={TextButton(onClick={confirmQr=false}){Text("取消")}})}
}

@Composable private fun ConfettiCelebration(onFinished:()->Unit){
    val progress=remember{Animatable(0f)};val primary=MaterialTheme.colorScheme.primary;val secondary=MaterialTheme.colorScheme.tertiary;val particles=remember{Random(731).let{r->List(72){ConfettiParticle(r.nextFloat(),r.nextFloat(),r.nextFloat(),r.nextFloat(),r.nextBoolean())}}}
    LaunchedEffect(Unit){progress.animateTo(1f,tween(1900));onFinished()}
    Canvas(Modifier.fillMaxSize()){
        particles.forEachIndexed{i,p->val t=progress.value;val startX=size.width*(.15f+.7f*p.x);val startY=size.height*.38f;val angle=(-PI*.92+PI*.84*p.angle).toFloat();val speed=size.minDimension*(.30f+.65f*p.speed);val x=startX+cos(angle)*speed*t;val y=startY+sin(angle)*speed*t+size.height*.42f*t*t;val alpha=(1f-t*.72f).coerceIn(0f,1f);val c=if(i%3==0)secondary else primary;if(p.circle)drawCircle(c.copy(alpha=alpha),radius=3.dp.toPx()+(i%4),center=Offset(x,y))else drawLine(c.copy(alpha=alpha),Offset(x,y),Offset(x+7.dp.toPx(),y+11.dp.toPx()),strokeWidth=3.dp.toPx())}
    }
}
private data class ConfettiParticle(val x:Float,val angle:Float,val speed:Float,val spin:Float,val circle:Boolean)

private fun saveQr(context:Context,bytes:ByteArray):Boolean=runCatching{
    val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"xyprt-support-${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29){put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/口袋小印");put(MediaStore.Images.Media.IS_PENDING,1)}}
    val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("insert failed");context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("open failed");if(Build.VERSION.SDK_INT>=29){values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);context.contentResolver.update(uri,values,null,null)};true
}.getOrDefault(false)
private fun openWechat(context:Context){val launch=context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(launch!=null)runCatching{context.startActivity(launch)} }
