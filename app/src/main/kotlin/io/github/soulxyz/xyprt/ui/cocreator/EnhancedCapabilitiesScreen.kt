package io.github.soulxyz.xyprt.ui.cocreator

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.data.remote.EnhancedCapability
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedCapabilitiesScreen(onBack:()->Unit){
    val context=LocalContext.current;val repo=remember{(context.applicationContext as App).container.enhancedModels};val state by repo.catalog.collectAsState();val scope=rememberCoroutineScope();var busyId by remember{mutableStateOf<String?>(null)}
    val runtimeAvailable = BuildConfig.ENHANCED_SCANNER_AVAILABLE
    LaunchedEffect(runtimeAvailable){ if (runtimeAvailable) repo.refreshCatalog(silent=false) }
    Scaffold(topBar={TopAppBar(title={Text("增强能力")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,null)}},actions={if(runtimeAvailable) TextButton(onClick={scope.launch{repo.refreshCatalog(false)}}){Text("刷新")}})}){pad->
        LazyColumn(Modifier.padding(pad).fillMaxSize(),contentPadding=androidx.compose.foundation.layout.PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("标准文档识别",fontWeight=FontWeight.SemiBold);Text("内置 · 永久可用 · 无需联网",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);Text("使用本地 OpenCV 进行纸张边缘识别、透视矫正和黑边清理。增强能力不可用时会自动回落到这里。",style=MaterialTheme.typography.bodySmall)}}}
            if(!runtimeAvailable)item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("当前是开源轻量构建",fontWeight=FontWeight.SemiBold);Text("增强模型运行时没有塞进这个安装包，因此日常扫描不会背几十 MB 的额外体积。共创构建启用运行时后，这里才会允许下载和使用模型。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("开源版的自动找边、透视校正、手动四角和打印增强仍然完整可用。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}}}
            if(runtimeAvailable&&state.refreshing)item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){CircularProgressIndicator()}}
            if(runtimeAvailable) state.lastError?.let{err->item{Text("增强能力目录暂时不可用：$err",color=MaterialTheme.colorScheme.error)}}
            if(runtimeAvailable) items(state.items,key={it.id}){item->CapabilityCard(item,busyId==item.id,onDownload={scope.launch{busyId=item.id;val r=repo.download(item);busyId=null;Toast.makeText(context,if(r.isSuccess)"增强能力已就绪" else r.exceptionOrNull()?.message?:"下载失败",Toast.LENGTH_LONG).show()}},onRemove={scope.launch{repo.remove(item);Toast.makeText(context,"已移除本地增强包",Toast.LENGTH_SHORT).show()}})}
            if(runtimeAvailable&&!state.refreshing&&state.items.isEmpty()&&state.lastError==null)item{Text("服务器目前没有发布额外的增强能力。标准识别仍然可以正常使用。",style=MaterialTheme.typography.bodyMedium)}
            item{Spacer(Modifier.size(20.dp))}
        }
    }
}

@Composable private fun CapabilityCard(item:EnhancedCapability,busy:Boolean,onDownload:()->Unit,onRemove:()->Unit){
    Card{Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.SemiBold);Text("v${item.version}${item.releaseLabel?.let{" · $it"}.orEmpty()}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)};if(busy)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)}
        if(item.description.isNotBlank())Text(item.description,style=MaterialTheme.typography.bodyMedium)
        item.publishedAt?.let{Text("发布：${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it*1000))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item.fileSize?.let{Text("下载约 ${formatBytes(it)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        when{item.locked->Text("当前未开放。已下载的数据也需要有效的服务器授权才能启用。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);item.installed->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("已安装 · 扫描时按需加载",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge);TextButton(onClick=onRemove){Text("移除")}};item.downloadable->Button(onClick=onDownload,enabled=!busy){Text(if(busy)"准备中…" else "下载并启用")};else->OutlinedButton(onClick={},enabled=false){Text("暂不可下载")}}
    }}
}
private fun formatBytes(v:Long):String=when{v>=1024*1024->"%.1f MB".format(v/1024.0/1024);v>=1024->"%.0f KB".format(v/1024.0);else->"$v B"}
