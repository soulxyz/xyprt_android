package io.github.soulxyz.xyprt.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.data.remote.RemoteAsset
import io.github.soulxyz.xyprt.model.BarcodeElement
import io.github.soulxyz.xyprt.model.DrawingElement
import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.FrameStyle
import io.github.soulxyz.xyprt.model.IconElement
import io.github.soulxyz.xyprt.model.ImageElement
import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelFont
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.QrPayload
import io.github.soulxyz.xyprt.model.QrPayloadType
import io.github.soulxyz.xyprt.model.Symbology
import io.github.soulxyz.xyprt.model.TableElement
import io.github.soulxyz.xyprt.model.TextElement
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.printer.dither.DitherMode
import io.github.soulxyz.xyprt.printer.dither.OutlineMethod
import io.github.soulxyz.xyprt.render.FontRegistry
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.ui.components.ClearButton
import io.github.soulxyz.xyprt.ui.components.EffectThumb
import io.github.soulxyz.xyprt.ui.components.EffectThumbRow
import io.github.soulxyz.xyprt.ui.components.RasterAdjustmentTabs
import io.github.soulxyz.xyprt.ui.components.rememberBlePermissionRunner
import io.github.soulxyz.xyprt.ui.home.LabelDialog
import io.github.soulxyz.xyprt.ui.quickprint.QuickImageAdjustments
import io.github.soulxyz.xyprt.ui.quickprint.QuickPrintRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import io.github.soulxyz.xyprt.ui.print.TemplatePrintSheet
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    templateId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenCoCreator: () -> Unit = {},
    vm: EditorViewModel = viewModel(factory = EditorViewModel.factory(templateId)),
) {
    val template by vm.template.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val selected by vm.selectedElement.collectAsState()
    val guides by vm.guides.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val remoteAssetCatalog by vm.remoteAssetCatalog.collectAsState()
    val remoteFontDownloads by vm.remoteFontDownloads.collectAsState()
    val remoteFonts = remoteAssetCatalog.items.filter { it.type == "font" }

    var showPrintSheet by remember { mutableStateOf(false) }
    var showMetaDialog by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showPropertiesSheet by remember { mutableStateOf(false) }
    var showLayersSheet by remember { mutableStateOf(false) }
    var showSketchSheet by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf("") }
    var editingTextId by remember { mutableStateOf<String?>(null) }
    var editingTextValue by remember { mutableStateOf("") }

    val withBlePermissions = rememberBlePermissionRunner()
    val t = template
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()

    fun addFrame(style: FrameStyle) {
        val id = UUID.randomUUID().toString()
        val sel = selected
        val frame = if (style == FrameStyle.LINE_H) {
            FrameElement(
                id = id,
                x = 48f,
                y = 64f,
                style = FrameStyle.LINE_H,
                widthPx = 288f,
                heightPx = 8f,
                strokePx = 2f,
            )
        } else if (sel != null) {
            val size = LabelRenderer.measure(sel)
            val rotated = sel.rotation % 180 != 0
            val w = if (rotated) size.height else size.width
            val h = if (rotated) size.width else size.height
            val cx = sel.x + size.width / 2f
            val cy = sel.y + size.height / 2f
            val pad = 8f
            FrameElement(
                id = id,
                x = cx - w / 2f - pad,
                y = cy - h / 2f - pad,
                style = style,
                widthPx = w + 2 * pad,
                heightPx = h + 2 * pad,
            )
        } else {
            FrameElement(
                id = id,
                x = 20f,
                y = 24f,
                style = style,
                widthPx = (LabelSpec.PRINT_WIDTH_PX - 40).toFloat(),
                heightPx = 120f,
            )
        }
        vm.addElement(frame)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importScope.launch {
            val loaded = withContext(Dispatchers.IO) { ImageImport.load(context, uri) }
            if (loaded != null) {
                vm.addElement(
                    ImageElement(
                        id = UUID.randomUUID().toString(),
                        pngBase64 = loaded.pngBase64,
                        srcWidth = loaded.width,
                        srcHeight = loaded.height,
                        widthPx = (LabelSpec.PRINT_WIDTH_PX - 32).toFloat(),
                        x = 16f,
                        y = 24f,
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = if (t != null) Modifier.clickable { showMetaDialog = true } else Modifier
                    ) {
                        Text(
                            t?.name ?: stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (t != null) {
                            Text(
                                if (t.spec.autoLength) "自动长度" else "${t.spec.lengthMm} mm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = canUndo) {
                        Text("↶", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = vm::redo, enabled = canRedo) {
                        Text("↷", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(
                        onClick = { withBlePermissions { showPrintSheet = true } },
                        enabled = t != null,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_print),
                            contentDescription = stringResource(R.string.action_print),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (t != null) {
                EditorDock(
                    selected = selected,
                    onAdd = { showAddSheet = true },
                    onPage = { showMetaDialog = true },
                    onLayers = { showLayersSheet = true },
                    onPreview = { withBlePermissions { showPrintSheet = true } },
                    onEdit = { showPropertiesSheet = true },
                    onDuplicate = vm::duplicateSelected,
                    onFront = vm::bringSelectedToFront,
                    onBack = vm::sendSelectedToBack,
                    onDelete = vm::deleteSelected,
                )
            }
        },
    ) { padding ->
        if (t == null) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        // The paper itself is the editor. Long continuous documents keep their real aspect ratio
        // and scroll vertically instead of being squeezed into a short fixed preview.
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (t.spec.autoLength) "自动长度 · 打印时去除页尾白边" else "固定长度 · ${t.spec.lengthMm} mm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(R.string.dialog_edit_title)) { showMetaDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            EditorCanvas(
                spec = t.spec,
                elements = t.elements,
                selectedId = selectedId,
                guides = guides,
                onSelect = vm::select,
                onDoubleTap = { id ->
                    vm.select(id)
                    showPropertiesSheet = true
                },
                onDeleteSelected = vm::deleteSelected,
                onDragStart = vm::beginDrag,
                onDragBy = vm::dragBy,
                onDragEnd = vm::endDrag,
                onResizeStart = vm::beginResize,
                onResizeBy = vm::resizeSelectedBy,
                onResizeEnd = vm::endResize,
                onRotateStart = vm::beginRotate,
                onRotateTo = vm::rotateSelectedTo,
                onRotateEnd = vm::endRotate,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .aspectRatio(LabelSpec.PRINT_WIDTH_PX.toFloat() / t.spec.lengthPx.toFloat()),
            )
            Text(
                if (selected == null) "点一下选择 · 拖动移动" else "拖动移动 · 双击按住可精细移动 · 右上旋转 · 右下缩放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }

    if (showAddSheet && t != null) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 18.dp),
            ) {
                Text("添加到纸上", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "选择内容，添加后可直接在纸上移动和调整。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AddTile("文字", "双击可修改") {
                        showAddSheet = false
                        pendingText = ""
                        showAddTextDialog = true
                    }
                    AddTile("图片", "从相册选择") {
                        showAddSheet = false
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    AddTile("涂画", "手写、圈画") {
                        showAddSheet = false
                        showSketchSheet = true
                    }
                    AddTile("二维码", "网址、文本等") {
                        showAddSheet = false
                        vm.addElement(BarcodeElement(id = UUID.randomUUID().toString(), x = 40f, y = 32f, symbology = Symbology.QR_CODE))
                        showPropertiesSheet = true
                    }
                    AddTile("条码", "Code 128 等") {
                        showAddSheet = false
                        vm.addElement(
                            BarcodeElement(
                                id = UUID.randomUUID().toString(),
                                x = 24f,
                                y = 32f,
                                symbology = Symbology.CODE_128,
                                widthPx = 260f,
                                heightPx = 72f,
                            )
                        )
                        showPropertiesSheet = true
                    }
                    AddTile("表格", "清单、打卡表") {
                        showAddSheet = false
                        vm.addElement(
                            TableElement(
                                id = UUID.randomUUID().toString(),
                                x = 16f,
                                y = 24f,
                                rows = 3,
                                columns = 3,
                                widthPx = (LabelSpec.PRINT_WIDTH_PX - 32).toFloat(),
                                heightPx = 144f,
                            )
                        )
                    }
                    AddTile("符号", "图标与 Emoji") {
                        showAddSheet = false
                        vm.addElement(IconElement(id = UUID.randomUUID().toString(), x = 24f, y = 32f))
                        showPropertiesSheet = true
                    }
                    AddTile("边框", "框住重点内容") {
                        showAddSheet = false
                        addFrame(FrameStyle.RECT)
                    }
                    AddTile("线条", "分隔内容") {
                        showAddSheet = false
                        addFrame(FrameStyle.LINE_H)
                    }
                }
            }
        }
    }

    if (showSketchSheet && t != null) {
        ModalBottomSheet(onDismissRequest = { showSketchSheet = false }) {
            SketchPadSheet(
                onDone = { drawing ->
                    vm.addElement(drawing.copy(x = 16f, y = 24f))
                    showSketchSheet = false
                },
                onCancel = { showSketchSheet = false },
            )
        }
    }

    if (showPropertiesSheet && selected != null) {
        ModalBottomSheet(onDismissRequest = { showPropertiesSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    "编辑${elementKind(selected!!)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PropertiesPanel(
                    element = selected!!,
                    remoteFonts = remoteFonts,
                    remoteFontDownloads = remoteFontDownloads,
                    remoteFontsRefreshing = remoteAssetCatalog.refreshing,
                    onUseRemoteFont = vm::useRemoteFont,
                    onCancelRemoteFont = vm::cancelRemoteFontSelection,
                    onRefreshRemoteFonts = vm::refreshRemoteFonts,
                    onUpdate = vm::updateElement,
                    onOpenCoCreator = onOpenCoCreator,
                    onDelete = {
                        vm.deleteSelected()
                        showPropertiesSheet = false
                    },
                )
            }
        }
    }

    if (showLayersSheet && t != null) {
        ModalBottomSheet(onDismissRequest = { showLayersSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("图层", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "上面的元素会盖住下面的元素。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (t.elements.isEmpty()) {
                    Text("纸上还没有内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        t.elements.asReversed().forEachIndexed { index, element ->
                            LayerRow(
                                number = t.elements.size - index,
                                element = element,
                                selected = element.id == selectedId,
                                onClick = {
                                    vm.select(element.id)
                                    showLayersSheet = false
                                },
                            )
                            if (index != t.elements.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showPrintSheet && t != null) {
        TemplatePrintSheet(
            template = t,
            onDismiss = { showPrintSheet = false },
            onOpenSettings = onOpenSettings,
        )
    }

    if (showMetaDialog && t != null) {
        LabelDialog(
            title = "页面设置",
            initialName = t.name,
            initialSpec = t.spec,
            onDismiss = { showMetaDialog = false },
            onConfirm = { name, spec ->
                vm.updateMeta(name, spec)
                showMetaDialog = false
            },
            onImport = null,
        )
    }

    if (showAddTextDialog && t != null) {
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("添加文字") },
            text = {
                OutlinedTextField(
                    value = pendingText,
                    onValueChange = { pendingText = it },
                    label = { Text("输入文字") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val maxBottom = t.elements.maxOfOrNull { e -> e.y + LabelRenderer.measure(e).height } ?: 16f
                        val y = (maxBottom + 12f).coerceAtMost((t.spec.lengthPx - 48).toFloat())
                        vm.addElement(
                            TextElement(
                                id = UUID.randomUUID().toString(),
                                x = 12f,
                                y = y.coerceAtLeast(16f),
                                text = pendingText,
                            )
                        )
                        showAddTextDialog = false
                        pendingText = ""
                    },
                    enabled = pendingText.isNotBlank(),
                ) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddTextDialog = false }) { Text("取消") } },
        )
    }

    val editingText = t?.elements?.find { it.id == editingTextId } as? TextElement
    if (editingText != null) {
        AlertDialog(
            onDismissRequest = { editingTextId = null },
            title = { Text("编辑文字") },
            text = {
                OutlinedTextField(
                    value = editingTextValue,
                    onValueChange = { editingTextValue = it },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.updateElement(editingText.copy(text = editingTextValue))
                        editingTextId = null
                    },
                    enabled = editingTextValue.isNotBlank(),
                ) { Text("完成") }
            },
            dismissButton = { TextButton(onClick = { editingTextId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditorDock(
    selected: LabelElement?,
    onAdd: () -> Unit,
    onPage: () -> Unit,
    onLayers: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected == null) {
                DockAction("＋", "添加", onAdd, primary = true)
                DockAction("▱", "页面", onPage)
                DockAction("▤", "图层", onLayers)
                DockAction("◉", "预览", onPreview)
            } else {
                DockAction("✎", "编辑", onEdit, primary = true)
                DockAction("▣", "复制", onDuplicate)
                DockAction("↑", "置顶", onFront)
                DockAction("↓", "置底", onBack)
                DockAction("×", "删除", onDelete, destructive = true)
            }
        }
    }
}

@Composable
private fun DockAction(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false,
) {
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (primary) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f) else Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = contentColor)
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

@Composable
private fun AddTile(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.width(154.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LayerRow(
    number: Int,
    element: LabelElement,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(elementKind(element), style = MaterialTheme.typography.bodyLarge)
                Text(
                    elementSummary(element),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) Text("已选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun elementKind(element: LabelElement): String = when (element) {
    is TextElement -> "文字"
    is ImageElement -> "图片"
    is DrawingElement -> "涂画"
    is BarcodeElement -> if (element.symbology == Symbology.QR_CODE) "二维码" else "条码"
    is TableElement -> "表格"
    is IconElement -> "符号"
    is FrameElement -> when (element.style) {
        FrameStyle.LINE_H, FrameStyle.LINE_V -> "线条"
        else -> "边框"
    }
}

private fun elementSummary(element: LabelElement): String = when (element) {
    is TextElement -> element.text.replace('\n', ' ').ifBlank { "空文字" }
    is ImageElement -> "${element.srcWidth} × ${element.srcHeight}"
    is DrawingElement -> "${element.strokes.size} 笔"
    is BarcodeElement -> element.data.ifBlank { "尚未填写内容" }
    is TableElement -> "${element.rows} × ${element.columns}"
    is IconElement -> element.glyph
    is FrameElement -> "${element.widthPx.toInt()} × ${element.heightPx.toInt()}"
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

private fun humanFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun remoteFontLockReason(reason: String?): String = when (reason) {
    "sponsor_required" -> "部分字体暂时处于共创计划内测阶段"
    "login_required" -> "登录后可用"
    "permission_required" -> "暂不可用"
    else -> "暂不可用"
}

/** Filled "+ word" button for adding; clearly set apart from the selection chips. */
@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(start = 6.dp, end = 10.dp)) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(2.dp))
        Text(label)
    }
}

/** Content of an element chip: Text shows the text, Symbol the character, Frame a small box. */
@Composable
private fun ElementChipLabel(element: LabelElement) {
    when (element) {
        is TextElement -> Text(
            element.text.replace('\n', ' ').ifBlank { stringResource(R.string.add_text) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        is IconElement -> Text(element.glyph, maxLines = 1)
        is FrameElement -> Box(
            Modifier
                .size(width = 22.dp, height = 13.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))
        )
        is TableElement -> Text("表格 ${element.rows}×${element.columns}", maxLines = 1)
        is BarcodeElement -> Text(
            if (element.symbology == Symbology.QR_CODE) "QR" else "▊▎▊",
            maxLines = 1
        )
        is ImageElement -> Text(stringResource(R.string.add_image), maxLines = 1)
        is DrawingElement -> Text("涂画", maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PropertiesPanel(
    element: LabelElement,
    remoteFonts: List<RemoteAsset>,
    remoteFontDownloads: Map<Int, RemoteFontDownloadState>,
    remoteFontsRefreshing: Boolean,
    onUseRemoteFont: (String, RemoteAsset) -> Unit,
    onCancelRemoteFont: (String) -> Unit,
    onRefreshRemoteFonts: () -> Unit,
    onUpdate: (LabelElement) -> Unit,
    onDelete: () -> Unit,
    onOpenCoCreator: () -> Unit = {},
) {
    val tabs = when (element) {
        is TextElement -> listOf("文字", "字体", "排版", "调整")
        is IconElement -> listOf("符号", "调整")
        is FrameElement -> listOf("样式", "调整")
        is TableElement -> listOf("表格", "调整")
        is BarcodeElement -> listOf("条码", "调整")
        is ImageElement -> listOf("图片", "调整")
        is DrawingElement -> listOf("调整")
    }
    var tab by remember(element.id) { mutableStateOf(0) }
    Column {
        if (tabs.size > 1) {
            SecondaryTabRow(selectedTabIndex = tab.coerceIn(0, tabs.size - 1)) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label, maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        when (element) {
            is TextElement -> when (tab) {
                0 -> TextContentSection(element, onUpdate)
                1 -> FontSection(
                    element = element,
                    remoteFonts = remoteFonts,
                    remoteFontDownloads = remoteFontDownloads,
                    remoteFontsRefreshing = remoteFontsRefreshing,
                    onUseRemoteFont = onUseRemoteFont,
                    onCancelRemoteFont = onCancelRemoteFont,
                    onRefreshRemoteFonts = onRefreshRemoteFonts,
                    onUpdate = onUpdate,
                    onOpenCoCreator = onOpenCoCreator,
                )
                2 -> TextLayoutSection(element, onUpdate)
                else -> AdjustSection(element, onUpdate, onDelete)
            }
            is IconElement -> if (tab == 0) IconProperties(element, onUpdate) else AdjustSection(element, onUpdate, onDelete)
            is FrameElement -> if (tab == 0) FrameProperties(element, onUpdate) else AdjustSection(element, onUpdate, onDelete)
            is TableElement -> if (tab == 0) TableProperties(element, onUpdate) else AdjustSection(element, onUpdate, onDelete)
            is BarcodeElement -> if (tab == 0) BarcodeProperties(element, onUpdate) else AdjustSection(element, onUpdate, onDelete)
            is ImageElement -> if (tab == 0) ImageProperties(element, onUpdate) else AdjustSection(element, onUpdate, onDelete)
            is DrawingElement -> AdjustSection(element, onUpdate, onDelete)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdjustSection(element: LabelElement, onUpdate: (LabelElement) -> Unit, onDelete: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column {
            GroupLabel(stringResource(R.string.cd_rotate))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Fine 15° steps plus a quick 90° jump.
                Stepper(
                    label = "",
                    value = "${element.rotation}°",
                    onDecrease = { onUpdate(element.withRotation((element.rotation - 15 + 360) % 360)) },
                    onIncrease = { onUpdate(element.withRotation((element.rotation + 15) % 360)) },
                )
                OutlinedButton(
                    onClick = { onUpdate(element.withRotation((element.rotation + 90) % 360)) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_rotate_cw),
                        contentDescription = stringResource(R.string.cd_rotate),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("90°")
                }
            }
        }
        Column {
            GroupLabel(stringResource(R.string.group_scale))
            val pct = (LabelRenderer.measure(element).width / LabelSpec.PRINT_WIDTH_PX * 100f)
                .roundToInt().coerceIn(1, 999)
            // Codes cap at 100 % (their box must fit the printable height to stay scannable);
            // everything else scales up to 200 %.
            val scaleMax = if (element is BarcodeElement) 100 else 200
            Stepper(
                label = "",
                value = "$pct %",
                onDecrease = { onUpdate(element.scaledToHeightPercent((pct - 1).coerceAtLeast(2))) },
                onIncrease = { onUpdate(element.scaledToHeightPercent((pct + 1).coerceAtMost(scaleMax))) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onDelete,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.menu_delete))
    }
}

private fun LabelElement.withRotation(deg: Int): LabelElement = when (this) {
    is TextElement -> copy(rotation = deg)
    is IconElement -> copy(rotation = deg)
    is FrameElement -> copy(rotation = deg)
    is TableElement -> copy(rotation = deg)
    is BarcodeElement -> copy(rotation = deg)
    is ImageElement -> copy(rotation = deg)
    is DrawingElement -> copy(rotation = deg)
}

/** Scales the element relative to the fixed 384-dot printable paper width. */
private fun LabelElement.scaledToHeightPercent(pct: Int): LabelElement {
    val target = pct / 100f * LabelSpec.PRINT_WIDTH_PX
    val current = LabelRenderer.measure(this).width
    val factor = if (current > 0.1f) target / current else 1f
    val maxH = 2f * LabelSpec.PRINT_WIDTH_PX
    return when (this) {
        is TextElement -> copy(
            fontSizePx = (fontSizePx * factor).coerceIn(6f, 200f),
            boxWidthPx = boxWidthPx?.let { it * factor },
        )
        is IconElement -> copy(sizePx = target.coerceIn(8f, maxH))
        is FrameElement -> copy(
            heightPx = target.coerceIn(2f, maxH),
            widthPx = (widthPx * factor).coerceAtLeast(2f),
        )
        is TableElement -> copy(
            widthPx = target.coerceIn(48f, LabelSpec.PRINT_WIDTH_PX.toFloat()),
            heightPx = (heightPx * factor).coerceIn(32f, maxH),
        )
        is BarcodeElement -> {
            // Scale the reserved box like an image (keep aspect); the code re-fits and centers inside.
            // Capped at the label height so the printed code stays within the printable area.
            val w = target.coerceIn(16f, LabelSpec.PRINT_WIDTH_PX.toFloat())
            val f = if (current > 0.1f) w / current else 1f
            copy(widthPx = w, heightPx = (heightPx * f).coerceAtLeast(16f))
        }
        is ImageElement -> copy(widthPx = (widthPx * factor).coerceAtLeast(8f))
        is DrawingElement -> {
            val newW = target.coerceIn(16f, LabelSpec.PRINT_WIDTH_PX.toFloat())
            val f = if (widthPx > 0.1f) newW / widthPx else 1f
            copy(
                widthPx = newW,
                heightPx = (heightPx * f).coerceAtLeast(16f),
                strokes = strokes.map { stroke ->
                    stroke.copy(
                        widthPx = stroke.widthPx * f,
                        points = stroke.points.map { it.copy(x = it.x * f, y = it.y * f) },
                    )
                },
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodyMedium)
        StepButton("-", onDecrease)
        Text(value, style = MaterialTheme.typography.bodyMedium)
        StepButton("+", onIncrease)
    }
}

/**
 * Plus/minus area: a short tap = one step; holding repeats and accelerates
 * (the interval gets shorter) until released.
 */
@Composable
private fun StepButton(symbol: String, onStep: () -> Unit) {
    val latest by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val job = scope.launch {
                        latest()
                        delay(380)
                        var interval = 150L
                        while (true) {
                            latest()
                            delay(interval)
                            interval = (interval * 80 / 100).coerceAtLeast(35L)
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

/** Standalone yes/no option as a switch, visually distinct from the selection chips. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Compact selectable chip: less horizontal padding than the stock FilterChip, so more fit per row. */
@Composable
private fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.heightIn(min = 30.dp).padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, label)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TableProperties(element: TableElement, onUpdate: (LabelElement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupLabel("表格")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Stepper(
                label = "行 ",
                value = element.rows.toString(),
                onDecrease = { onUpdate(element.copy(rows = (element.rows - 1).coerceAtLeast(1))) },
                onIncrease = { onUpdate(element.copy(rows = (element.rows + 1).coerceAtMost(20))) },
            )
            Stepper(
                label = "列 ",
                value = element.columns.toString(),
                onDecrease = { onUpdate(element.copy(columns = (element.columns - 1).coerceAtLeast(1))) },
                onIncrease = { onUpdate(element.copy(columns = (element.columns + 1).coerceAtMost(12))) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("线宽", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
            Slider(
                value = element.strokePx,
                onValueChange = { onUpdate(element.copy(strokePx = it)) },
                valueRange = 1f..6f,
                steps = 4,
                modifier = Modifier.weight(1f),
            )
            Text("${element.strokePx.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarcodeProperties(element: BarcodeElement, onUpdate: (LabelElement) -> Unit) {
    GroupLabel(stringResource(R.string.prop_barcode_type))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbology.entries.forEach { s ->
            ChoiceChip(
                selected = element.symbology == s,
                onClick = {
                    // Leaving QR for a 1D barcode: reset the wizard to raw text, and if we were on a
                    // structured payload (WiFi, contact, ...) keep only its primary value so the barcode
                    // does not carry a full WIFI:/MECARD: string.
                    val leavingStructured = element.symbology == Symbology.QR_CODE &&
                        element.payloadType != QrPayloadType.TEXT && element.payloadType != QrPayloadType.LINK
                    val type = if (s == Symbology.QR_CODE) element.payloadType else QrPayloadType.TEXT
                    val data = if (s != Symbology.QR_CODE && leavingStructured)
                        element.payload[QrPayload.primaryKey(element.payloadType)].orEmpty()
                    else element.data
                    onUpdate(element.copy(symbology = s, payloadType = type, data = data))
                },
                label = { Text(symbologyLabel(s)) },
            )
        }
    }
    if (element.symbology == Symbology.QR_CODE) {
        Spacer(Modifier.height(6.dp))
        GroupLabel(stringResource(R.string.qr_content))
        val types = listOf(
            QrPayloadType.TEXT to R.string.qr_type_text,
            QrPayloadType.LINK to R.string.qr_type_link,
            QrPayloadType.WIFI to R.string.qr_type_wifi,
            QrPayloadType.EMAIL to R.string.qr_type_email,
            QrPayloadType.PHONE to R.string.qr_type_phone,
            QrPayloadType.CONTACT to R.string.qr_type_contact,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { (type, label) ->
                ChoiceChip(
                    selected = element.payloadType == type,
                    onClick = { onUpdate(QrPayload.switchType(element, type)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        QrPayloadFields(element, onUpdate)
    } else {
        // 1D barcode: raw content plus the optional human-readable caption.
        OutlinedTextField(
            value = element.data,
            onValueChange = { onUpdate(element.copy(data = it)) },
            label = { Text(stringResource(R.string.prop_barcode_data)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (element.data.isNotEmpty()) ClearButton { onUpdate(element.copy(data = "")) } },
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        ToggleRow(stringResource(R.string.prop_barcode_caption), element.showText) {
            onUpdate(element.copy(showText = it))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QrPayloadFields(element: BarcodeElement, onUpdate: (LabelElement) -> Unit) {
    // Set one or more payload fields and rebuild the encoded string the scanner reads.
    fun set(vararg pairs: Pair<String, String>) {
        val fields = element.payload + pairs
        onUpdate(element.copy(payload = fields, data = QrPayload.build(element.payloadType, fields)))
    }
    fun get(key: String) = element.payload[key].orEmpty()

    @Composable
    fun field(value: String, labelRes: Int, keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(labelRes)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (value.isNotEmpty()) ClearButton { onChange("") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }

    when (element.payloadType) {
        QrPayloadType.TEXT ->
            field(element.data, R.string.prop_barcode_data) { set(QrPayload.TEXT to it) }
        QrPayloadType.LINK -> {
            val url = get(QrPayload.URL)
            // TextFieldValue so the cursor can be placed at the end after the https:// prefill.
            var tfv by remember { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
            if (tfv.text != url) tfv = TextFieldValue(url, TextRange(url.length)) // sync external changes
            OutlinedTextField(
                value = tfv,
                onValueChange = { tfv = it; set(QrPayload.URL to it.text) },
                label = { Text(stringResource(R.string.qr_url)) },
                modifier = Modifier
                    .fillMaxWidth()
                    // Prefill https:// only once the empty field is tapped (cursor at the end), so it
                    // is never left as a stray default.
                    .onFocusChanged {
                        if (it.isFocused && get(QrPayload.URL).isEmpty()) {
                            set(QrPayload.URL to "https://")
                            tfv = TextFieldValue("https://", TextRange("https://".length))
                        }
                    },
                trailingIcon = { if (url.isNotEmpty()) ClearButton { set(QrPayload.URL to "") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        QrPayloadType.WIFI -> {
            field(get(QrPayload.SSID), R.string.qr_ssid) { set(QrPayload.SSID to it) }
            Spacer(Modifier.height(6.dp))
            GroupLabel(stringResource(R.string.qr_auth))
            val auths = listOf("WPA" to "WPA/WPA2/WPA3", "WEP" to "WEP", "nopass" to stringResource(R.string.qr_auth_none))
            val currentAuth = get(QrPayload.AUTH).ifBlank { "WPA" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                auths.forEach { (value, label) ->
                    ChoiceChip(
                        selected = currentAuth == value,
                        onClick = { set(QrPayload.AUTH to value) },
                        label = { Text(label) },
                    )
                }
            }
            // The password applies only to encrypted networks; an open one has none.
            if (currentAuth != "nopass") {
                Spacer(Modifier.height(4.dp))
                var reveal by remember { mutableStateOf(false) }
                val password = get(QrPayload.PASSWORD)
                // Validate live: any non-empty password that is too short/long shows the error, which
                // also covers reopening an element whose stored password is invalid.
                val invalid = password.isNotEmpty() && !QrPayload.isWifiPasswordValid(currentAuth, password)
                OutlinedTextField(
                    value = password,
                    onValueChange = { set(QrPayload.PASSWORD to it) },
                    label = { Text(stringResource(R.string.qr_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = invalid,
                    // Plain text keyboard (not Password) so a password manager does not offer to save it.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                painterResource(if (reveal) R.drawable.ic_eye_off else R.drawable.ic_eye),
                                contentDescription = stringResource(R.string.qr_show_password),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    supportingText = {
                        if (invalid) {
                            Text(
                                stringResource(if (currentAuth == "WEP") R.string.qr_wifi_pw_error_wep else R.string.qr_wifi_pw_error_wpa),
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(stringResource(R.string.qr_password_hint))
                        }
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            ToggleRow(stringResource(R.string.qr_hidden), get(QrPayload.HIDDEN) == "true") {
                set(QrPayload.HIDDEN to it.toString())
            }
        }
        QrPayloadType.EMAIL -> {
            field(get(QrPayload.EMAIL), R.string.qr_email_addr, KeyboardType.Email) { set(QrPayload.EMAIL to it) }
            Spacer(Modifier.height(4.dp))
            field(get(QrPayload.SUBJECT), R.string.qr_subject) { set(QrPayload.SUBJECT to it) }
        }
        QrPayloadType.PHONE ->
            field(get(QrPayload.PHONE), R.string.qr_phone, KeyboardType.Phone) { set(QrPayload.PHONE to it) }
        QrPayloadType.CONTACT -> {
            field(get(QrPayload.NAME), R.string.field_name) { set(QrPayload.NAME to it) }
            Spacer(Modifier.height(4.dp))
            field(get(QrPayload.PHONE), R.string.qr_phone, KeyboardType.Phone) { set(QrPayload.PHONE to it) }
            Spacer(Modifier.height(4.dp))
            field(get(QrPayload.EMAIL), R.string.qr_email_addr, KeyboardType.Email) { set(QrPayload.EMAIL to it) }
        }
    }
}

private fun symbologyLabel(s: Symbology): String = when (s) {
    Symbology.QR_CODE -> "QR"
    Symbology.CODE_128 -> "Code 128"
    Symbology.EAN_13 -> "EAN-13"
    Symbology.UPC_A -> "UPC-A"
    Symbology.CODE_39 -> "Code 39"
    Symbology.ITF -> "ITF"
}

/**
 * Style, Smooth and Invert side by side, each with its own heading (like the Rotate/Scale row).
 * Shared by icons and images; shown in outline mode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutlineOptionsRow(
    method: OutlineMethod,
    smooth: Boolean,
    invert: Boolean,
    onMethod: (OutlineMethod) -> Unit,
    onSmooth: (Boolean) -> Unit,
    onInvert: (Boolean) -> Unit,
) {
    val options = listOf(
        OutlineMethod.LINES to R.string.outline_lines,
        OutlineMethod.CANNY to R.string.outline_canny,
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column {
            GroupLabel(stringResource(R.string.prop_outline_style))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (m, label) ->
                    ChoiceChip(selected = method == m, onClick = { onMethod(m) }, label = { Text(stringResource(label)) })
                }
            }
        }
        Column {
            GroupLabel(stringResource(R.string.outline_smooth))
            Switch(checked = smooth, onCheckedChange = onSmooth)
        }
        Column {
            GroupLabel(stringResource(R.string.prop_invert))
            Switch(checked = invert, onCheckedChange = onInvert)
        }
    }
}

@Composable
private fun ImageProperties(element: ImageElement, onUpdate: (LabelElement) -> Unit) {
    var ditherThumbs by remember(element.pngBase64) { mutableStateOf<Map<DitherMode, MonoImage?>>(emptyMap()) }
    var ditherThumbErrors by remember(element.pngBase64) { mutableStateOf<Map<DitherMode, String?>>(emptyMap()) }
    LaunchedEffect(element.pngBase64) {
        val results = withContext(Dispatchers.IO) {
            val bytes = runCatching { Base64.decode(element.pngBase64, Base64.NO_WRAP) }.getOrNull()
                ?: return@withContext null
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val headDots = io.github.soulxyz.xyprt.printer.Protocol.HEAD_DOTS
            val cropped = if (full.height.toFloat() / full.width > 3f) {
                val targetHeight = (full.width * 3f).toInt()
                val top = (full.height - targetHeight) / 2
                Bitmap.createBitmap(full, 0, top, full.width, targetHeight)
            } else full
            val small = if (cropped.width != headDots) {
                Bitmap.createScaledBitmap(
                    cropped,
                    headDots,
                    (cropped.height.toLong() * headDots / cropped.width).toInt().coerceAtLeast(1),
                    true,
                )
            } else cropped
            try {
                val monos = mutableMapOf<DitherMode, MonoImage?>()
                val errors = mutableMapOf<DitherMode, String?>()
                DitherMode.entries.forEach { mode ->
                    val r = runCatching {
                        QuickPrintRenderer.toMono(small, QuickImageAdjustments(mode = mode, threshold = element.threshold, contrast = element.contrast))
                    }
                    if (r.isSuccess) {
                        monos[mode] = r.getOrNull()
                        errors[mode] = null
                    } else {
                        monos[mode] = null
                        errors[mode] = r.exceptionOrNull()?.let { it::class.simpleName + ": " + (it.message ?: "") }
                        Log.w("EffectThumb", "toMono failed for mode=$mode", r.exceptionOrNull())
                    }
                }
                Pair(monos, errors)
            } finally {
                small.recycle()
                if (cropped !== full) cropped.recycle()
                if (small !== full) full.recycle()
            }
        }
        if (results != null) {
            ditherThumbs = results.first
            ditherThumbErrors = results.second
        } else {
            ditherThumbs = emptyMap()
            ditherThumbErrors = emptyMap()
        }
    }
    Spacer(Modifier.height(6.dp))
    GroupLabel("效果预设")
    EffectThumbRow(
        selectedId = element.dither.name,
        thumbs = DitherMode.entries.map { mode ->
            EffectThumb(
                id = mode.name,
                label = ditherLabel(mode),
                mono = ditherThumbs[mode],
                error = ditherThumbErrors[mode],
            )
        },
        onSelect = { id -> DitherMode.entries.firstOrNull { it.name == id }?.let { onUpdate(element.copy(dither = it)) } },
    )
    Spacer(Modifier.height(10.dp))
    RasterAdjustmentTabs(
        mode = element.dither,
        threshold = element.threshold,
        contrast = element.contrast,
        invert = element.invert,
        onThreshold = { onUpdate(element.copy(threshold = it)) },
        onContrast = { onUpdate(element.copy(contrast = it)) },
        onInvert = { onUpdate(element.copy(invert = it)) },
        outlineSensitivity = element.outlineSensitivity,
        outlineThickness = element.outlineThickness,
        outlineMethod = element.outlineMethod,
        outlineSmooth = element.outlineSmooth,
        onOutlineSensitivity = { onUpdate(element.copy(outlineSensitivity = it)) },
        onOutlineThickness = { onUpdate(element.copy(outlineThickness = it)) },
        onOutlineMethod = { onUpdate(element.copy(outlineMethod = it)) },
        onOutlineSmooth = { onUpdate(element.copy(outlineSmooth = it)) },
        rotationDegrees = element.rotation,
        onRotationDegrees = { onUpdate(element.copy(rotation = it)) },
        removeRedInk = element.removeRedInk,
        removeBlueInk = element.removeBlueInk,
        onRemoveRedInk = { onUpdate(element.copy(removeRedInk = it)) },
        onRemoveBlueInk = { onUpdate(element.copy(removeBlueInk = it)) },
    )
}

private fun ditherLabel(mode: DitherMode): String = when (mode) {
    DitherMode.OUTLINE -> "线稿"
    DitherMode.THRESHOLD -> "黑白"
    DitherMode.FLOYD_STEINBERG -> "细腻"
    DitherMode.ATKINSON -> "清晰"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextContentSection(element: TextElement, onUpdate: (LabelElement) -> Unit) {
    OutlinedTextField(
        value = element.text,
        onValueChange = { onUpdate(element.copy(text = it)) },
        label = { Text(stringResource(R.string.prop_text)) },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = { if (element.text.isNotEmpty()) ClearButton { onUpdate(element.copy(text = "")) } },
        minLines = 1,
        maxLines = 4
    )
    Spacer(Modifier.height(10.dp))
    GroupLabel(stringResource(R.string.group_variables))
    val tokens = listOf(
        stringResource(R.string.var_date) to "{date}",
        stringResource(R.string.var_time) to "{time}",
        stringResource(R.string.var_number) to "{#}",
        stringResource(R.string.var_var) to "{var:Text}",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tokens.forEach { (label, token) ->
            ChoiceChip(
                selected = false,
                onClick = { onUpdate(element.copy(text = element.text + token)) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun TextLayoutSection(element: TextElement, onUpdate: (LabelElement) -> Unit) {
    Stepper(
        label = stringResource(R.string.prop_size) + ": ",
        value = "${element.fontSizePx.toInt()} px",
        onDecrease = { onUpdate(element.copy(fontSizePx = (element.fontSizePx - 4).coerceAtLeast(8f))) },
        onIncrease = { onUpdate(element.copy(fontSizePx = (element.fontSizePx + 4).coerceAtMost(96f))) }
    )
    Spacer(Modifier.height(4.dp))
    Stepper(
        label = "行距: ",
        value = "${element.lineSpacingPercent}%",
        onDecrease = { onUpdate(element.copy(lineSpacingPercent = (element.lineSpacingPercent - 5).coerceAtLeast(80))) },
        onIncrease = { onUpdate(element.copy(lineSpacingPercent = (element.lineSpacingPercent + 5).coerceAtMost(200))) },
    )
    Spacer(Modifier.height(10.dp))
    GroupLabel(stringResource(R.string.group_format))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceChip(
            selected = element.bold,
            onClick = { onUpdate(element.copy(bold = !element.bold)) },
            label = { Text(stringResource(R.string.prop_bold)) })
        ChoiceChip(
            selected = element.italic,
            onClick = { onUpdate(element.copy(italic = !element.italic)) },
            label = { Text(stringResource(R.string.prop_italic)) })
        ChoiceChip(
            selected = element.underline,
            onClick = { onUpdate(element.copy(underline = !element.underline)) },
            label = { Text(stringResource(R.string.prop_underline)) })
    }
    Spacer(Modifier.height(10.dp))
    GroupLabel(stringResource(R.string.group_align))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelTextAlign.entries.forEach { align ->
            ChoiceChip(
                selected = element.align == align,
                onClick = { onUpdate(element.copy(align = align)) },
                label = {
                    Text(
                        when (align) {
                            LabelTextAlign.LEFT -> stringResource(R.string.align_left)
                            LabelTextAlign.CENTER -> stringResource(R.string.align_center)
                            LabelTextAlign.RIGHT -> stringResource(R.string.align_right)
                        }
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontSection(
    element: TextElement,
    remoteFonts: List<RemoteAsset>,
    remoteFontDownloads: Map<Int, RemoteFontDownloadState>,
    remoteFontsRefreshing: Boolean,
    onUseRemoteFont: (String, RemoteAsset) -> Unit,
    onCancelRemoteFont: (String) -> Unit,
    onRefreshRemoteFonts: () -> Unit,
    onUpdate: (LabelElement) -> Unit,
    onOpenCoCreator: () -> Unit = {},
) {
    var showRemoteFonts by remember { mutableStateOf(false) }
    var pendingRemoteSlug by remember { mutableStateOf<String?>(null) }
    val selectedRemote = remoteFonts.firstOrNull { it.slug == element.fontAssetId }
    LaunchedEffect(element.fontAssetId, pendingRemoteSlug) {
        if (pendingRemoteSlug != null && element.fontAssetId == pendingRemoteSlug) {
            pendingRemoteSlug = null
            showRemoteFonts = false
        }
    }
    GroupLabel(stringResource(R.string.group_font))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelFont.entries.forEach { f ->
            ChoiceChip(
                selected = element.fontAssetId == null && element.font == f,
                onClick = {
                    onCancelRemoteFont(element.id)
                    pendingRemoteSlug = null
                    onUpdate(element.copy(font = f, fontAssetId = null))
                },
                label = {
                    Text(
                        when (f) {
                            LabelFont.SANS -> stringResource(R.string.font_sans)
                            LabelFont.SERIF -> stringResource(R.string.font_serif)
                            LabelFont.MONO -> stringResource(R.string.font_mono)
                            LabelFont.OSWALD -> "Oswald"
                            LabelFont.ZILLA_SLAB -> "Slab"
                            LabelFont.COMFORTAA -> "Rund"
                            LabelFont.CAVEAT -> "Caveat"
                            LabelFont.PACIFICO -> "Pacifico"
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            GroupLabel("在线字体")
            Text(
                selectedRemote?.let { "正在使用 · ${it.name}" } ?: "需要时再下载，下载后可离线使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = { showRemoteFonts = true }) {
            Text(if (selectedRemote == null) "字体库" else "更换")
        }
    }

    if (showRemoteFonts) {
        RemoteFontLibraryDialog(
            element = element,
            remoteFonts = remoteFonts,
            remoteFontDownloads = remoteFontDownloads,
            remoteFontsRefreshing = remoteFontsRefreshing,
            onUseRemoteFont = { asset ->
                pendingRemoteSlug = asset.slug
                onUseRemoteFont(element.id, asset)
            },
            onRefreshRemoteFonts = onRefreshRemoteFonts,
            onDismiss = { showRemoteFonts = false },
            onOpenCoCreator = onOpenCoCreator,
        )
    }
}

@Composable
private fun RemoteFontLibraryDialog(
    element: TextElement,
    remoteFonts: List<RemoteAsset>,
    remoteFontDownloads: Map<Int, RemoteFontDownloadState>,
    remoteFontsRefreshing: Boolean,
    onUseRemoteFont: (RemoteAsset) -> Unit,
    onRefreshRemoteFonts: () -> Unit,
    onDismiss: () -> Unit,
    onOpenCoCreator: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在线字体") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        "字体只在你选择时下载；已经下载的字体离线也能继续使用。部分字体暂时处于",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "共创计划",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenCoCreator() },
                    )
                    Text(
                        "内测阶段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (remoteFonts.isEmpty()) {
                    Text(
                        if (remoteFontsRefreshing) "正在同步字体目录…" else "暂时没有可用在线字体",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    remoteFonts.forEachIndexed { index, font ->
                        RemoteFontCard(
                            font = font,
                            active = element.fontAssetId == font.slug,
                            state = remoteFontDownloads[font.id],
                            onUse = { onUseRemoteFont(font) },
                        )
                        if (index != remoteFonts.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefreshRemoteFonts, enabled = !remoteFontsRefreshing) {
                Text(if (remoteFontsRefreshing) "同步中…" else "刷新")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun RemoteFontCard(
    font: RemoteAsset,
    active: Boolean,
    state: RemoteFontDownloadState?,
    onUse: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as App).container }
    var preview by remember(font.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var previewReady by remember(font.id) { mutableStateOf(font.preview == null) }
    val installed = FontRegistry.remote(font.slug) != null
    val enabled = font.downloadable && !font.locked && state?.loading != true
    LaunchedEffect(font.preview?.sha256) {
        if (font.preview != null) {
            previewReady = false
            container.remoteAssets.previewFile(font).onSuccess { file ->
                preview = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
                previewReady = true
            }.onFailure { previewReady = true }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val bmp = preview
                when {
                    bmp != null -> Image(
                        bmp.asImageBitmap(),
                        contentDescription = font.name,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                        contentScale = ContentScale.Fit,
                    )
                    font.preview != null && !previewReady -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    installed -> {
                        val face = FontRegistry.remote(font.slug)
                        if (face == null) {
                            // 字体文件已存在但加载失败（损坏）
                            Text(
                                "字体文件损坏",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                            )
                        } else {
                            Text(
                                "口袋小印 · 字体预览",
                                fontSize = 16.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily(face),
                                maxLines = 1,
                            )
                        }
                    }
                    else -> Text(
                        "口袋小印 · 字体预览",
                        fontSize = 16.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(font.name, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sizeText = font.file?.size?.let(::humanFileSize).orEmpty()
                Text(
                    listOfNotNull(
                        sizeText.takeIf { it.isNotEmpty() },
                when {
                    active -> "使用中"
                    installed -> "已下载"
                    else -> null
                },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                                    state?.error?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
            }
            Button(
                onClick = onUse,
                enabled = enabled && !active,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                if (state?.loading == true) {
                    val p = state.progress
                    if (p != null) {
                        val animP by animateFloatAsState(p, label = "fontDl")
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.width(52.dp).height(20.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    ),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animP.coerceIn(0f, 1f))
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Text(
                                "${(animP * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = if (animP < 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.width(40.dp).height(20.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                } else Text(if (installed) "使用" else "下载")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconProperties(element: IconElement, onUpdate: (LabelElement) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    GroupLabel(stringResource(R.string.symbol_current))
    Box(
        modifier = Modifier
            .size(72.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable { showPicker = true },
        contentAlignment = Alignment.Center
    ) {
        Text(element.glyph, fontSize = 40.sp)
    }
    Spacer(Modifier.height(8.dp))
    Stepper(
        label = stringResource(R.string.prop_size) + ": ",
        value = "${element.sizePx.toInt()} px",
        onDecrease = { onUpdate(element.copy(sizePx = (element.sizePx - 8).coerceAtLeast(16f))) },
        onIncrease = { onUpdate(element.copy(sizePx = (element.sizePx + 8).coerceAtMost(96f))) }
    )
    Spacer(Modifier.height(6.dp))
    GroupLabel(stringResource(R.string.group_raster))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DitherMode.entries.forEach { mode ->
            ChoiceChip(
                selected = element.dither == mode,
                onClick = { onUpdate(element.copy(dither = mode)) },
                label = {
                    Text(
                        when (mode) {
                            DitherMode.THRESHOLD -> stringResource(R.string.dither_threshold)
                            DitherMode.FLOYD_STEINBERG -> stringResource(R.string.dither_fs)
                            DitherMode.ATKINSON -> stringResource(R.string.dither_atkinson)
                            DitherMode.OUTLINE -> stringResource(R.string.dither_outline)
                        }
                    )
                }
            )
        }
    }
    Text(
        stringResource(R.string.raster_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )

    Spacer(Modifier.height(6.dp))
    // Outline mode only controls the number of bands; the contrast slider would interfere there
    // with the fixed quantization (outlines flicker) and is therefore omitted. Otherwise: contrast.
    if (element.dither == DitherMode.OUTLINE) {
        GroupLabel(stringResource(R.string.prop_outline_detail) + ": ${element.outlineSensitivity}")
        Slider(
            value = element.outlineSensitivity.toFloat(),
            onValueChange = { onUpdate(element.copy(outlineSensitivity = it.roundToInt())) },
            valueRange = 0f..100f
        )
        Spacer(Modifier.height(6.dp))
        Stepper(
            label = stringResource(R.string.prop_line_width) + ": ",
            value = "${element.outlineThickness} px",
            onDecrease = { onUpdate(element.copy(outlineThickness = (element.outlineThickness - 1).coerceAtLeast(1))) },
            onIncrease = { onUpdate(element.copy(outlineThickness = (element.outlineThickness + 1).coerceAtMost(3))) }
        )
        OutlineOptionsRow(
            method = element.outlineMethod,
            smooth = element.outlineSmooth,
            invert = element.invert,
            onMethod = { onUpdate(element.copy(outlineMethod = it)) },
            onSmooth = { onUpdate(element.copy(outlineSmooth = it)) },
            onInvert = { onUpdate(element.copy(invert = it)) },
        )
    } else {
        GroupLabel(stringResource(R.string.prop_contrast) + ": ${element.contrast}")
        Slider(
            value = element.contrast.toFloat(),
            onValueChange = { onUpdate(element.copy(contrast = it.roundToInt())) },
            valueRange = -100f..100f
        )
    }
    if (element.dither != DitherMode.OUTLINE) {
        Spacer(Modifier.height(4.dp))
        ToggleRow(stringResource(R.string.prop_invert), element.invert) { onUpdate(element.copy(invert = it)) }
    }

    if (showPicker) {
        SymbolPickerSheet(
            onPick = { glyph, isEmoji ->
                // Set the default dither only on first assignment (still the placeholder glyph):
                // emoji -> outline, single-color symbols -> threshold. On a later change the choice stays.
                val newDither = if (element.glyph == "□") {
                    if (isEmoji) DitherMode.OUTLINE else DitherMode.THRESHOLD
                } else {
                    element.dither
                }
                onUpdate(element.copy(glyph = glyph, dither = newDither))
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun FrameProperties(element: FrameElement, onUpdate: (LabelElement) -> Unit) {
    val rectSelected = element.style == FrameStyle.RECT || element.style == FrameStyle.ROUND_RECT
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceChip(
            selected = rectSelected,
            onClick = { onUpdate(element.copy(style = FrameStyle.RECT)) },
            label = { Text(stringResource(R.string.frame_rect)) }
        )
        ChoiceChip(
            selected = element.style == FrameStyle.LINE_H,
            onClick = { onUpdate(element.copy(style = FrameStyle.LINE_H)) },
            label = { Text(stringResource(R.string.frame_line_h)) }
        )
        ChoiceChip(
            selected = element.style == FrameStyle.LINE_V,
            onClick = { onUpdate(element.copy(style = FrameStyle.LINE_V)) },
            label = { Text(stringResource(R.string.frame_line_v)) }
        )
    }
    Spacer(Modifier.height(4.dp))
    Stepper(
        label = stringResource(R.string.prop_stroke) + ": ",
        value = "${element.strokePx.toInt()} px",
        onDecrease = { onUpdate(element.copy(strokePx = (element.strokePx - 1).coerceAtLeast(1f))) },
        onIncrease = { onUpdate(element.copy(strokePx = (element.strokePx + 1).coerceAtMost(10f))) }
    )
    if (rectSelected) {
        Stepper(
            label = stringResource(R.string.prop_radius) + ": ",
            value = "${element.cornerRadiusPx.toInt()} px",
            onDecrease = { onUpdate(element.copy(cornerRadiusPx = (element.cornerRadiusPx - 2).coerceAtLeast(0f))) },
            onIncrease = { onUpdate(element.copy(cornerRadiusPx = (element.cornerRadiusPx + 2).coerceAtMost(48f))) }
        )
    }
    Stepper(
        label = stringResource(R.string.prop_width) + ": ",
        value = "${element.widthPx.toInt()} px",
        onDecrease = { onUpdate(element.copy(widthPx = (element.widthPx - 8).coerceAtLeast(8f))) },
        onIncrease = { onUpdate(element.copy(widthPx = element.widthPx + 8)) }
    )
    Stepper(
        label = stringResource(R.string.prop_height) + ": ",
        value = "${element.heightPx.toInt()} px",
        onDecrease = { onUpdate(element.copy(heightPx = (element.heightPx - 8).coerceAtLeast(8f))) },
        onIncrease = { onUpdate(element.copy(heightPx = (element.heightPx + 8).coerceAtMost(2000f))) }
    )
}
