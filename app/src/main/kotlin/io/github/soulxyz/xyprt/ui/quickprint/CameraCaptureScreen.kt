package io.github.soulxyz.xyprt.ui.quickprint

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.scanner.DocumentQuad
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 应用内拍摄页：实时预览纸张边缘提示，拍完直接进入四角调整；也可一键切到相册选图。
 */
@Composable
fun CameraCaptureScreen(
    onExit: () -> Unit,
    onCaptured: (Uri) -> Unit,
    onPickFromGallery: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { (context.applicationContext as App).container.scanner }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) onExit()
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var displayedSize by remember { mutableStateOf(Size(16, 9)) }
    var detectorReady by remember { mutableStateOf(false) }
    var quad by remember { mutableStateOf<DocumentQuad?>(null) }
    var detected by remember { mutableStateOf(false) }
    var cameraOpenError by remember { mutableStateOf<String?>(null) }
    var shutterScale by remember { mutableFloatStateOf(1f) }
    val shutterAnimated by animateFloatAsState(shutterScale, tween(130), label = "shutter")
    var capturing by remember { mutableStateOf(false) }

    val cameraController = remember {
        Camera2Controller(
            onPreviewReady = { size ->
                displayedSize = if (size.width > 0) Size(size.height, size.width) else size
                detectorReady = true
            },
            onCameraError = { cameraOpenError = it },
            onJpeg = { bytes ->
                capturing = false
                onCaptured(saveJpegToCache(context, bytes))
            },
        )
    }

    DisposableEffect(hasPermission) {
        if (hasPermission) cameraController.open(context)
        onDispose { cameraController.close() }
    }

    LaunchedEffect(detectorReady) {
        var misses = 0
        while (detectorReady && isActive) {
            delay(650)
            val view = textureView ?: continue
            val frame = view.bitmap?.let { scaleDown(it, 480) } ?: continue
            val result = withContext(Dispatchers.Default) {
                runCatching { scanner.standardDetect(frame) }.getOrNull()
            }
            frame.recycle()
            if (result != null && result.confidence >= 0.45f && result.quad.isReasonable()) {
                quad = result.quad
                detected = true
                misses = 0
            } else if (++misses >= 5) {
                quad = null
                detected = false
            }
        }
    }

    val edgeColor = Color(0xFF62D2FF)
    val cornerColor = Color(0xFFFFB648)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                textureView = this@apply
                                cameraController.onSurfaceReady(this@apply, surface)
                            }
                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                cameraController.configureTransform(this@apply)
                            }
                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                cameraController.onSurfaceDestroyed()
                                return true
                            }
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(
                Modifier.fillMaxSize(),
                onDraw = {
                    val bmpW = displayedSize.width.toFloat()
                    val bmpH = displayedSize.height.toFloat()
                    if (bmpW <= 0f || bmpH <= 0f) return@Canvas
                    val scale = min(size.width / bmpW, size.height / bmpH)
                    val dstW = bmpW * scale
                    val dstH = bmpH * scale
                    val left = (size.width - dstW) / 2f
                    val top = (size.height - dstH) / 2f
                    fun px(x: Float) = left + x * dstW
                    fun py(y: Float) = top + y * dstH

                    quad?.let { q ->
                        val pts = q.points().map { Offset(px(it.x), py(it.y)) }
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            lineTo(pts[1].x, pts[1].y)
                            lineTo(pts[2].x, pts[2].y)
                            lineTo(pts[3].x, pts[3].y)
                            close()
                        }
                        drawPath(path, color = edgeColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                        pts.forEach { p ->
                            drawCircle(cornerColor, radius = 8.dp.toPx(), center = p)
                            drawCircle(Color.White, radius = 3.5.dp.toPx(), center = p)
                        }
                    }
                },
            )
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Text(
                    "拍摄纸张",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.weight(1f))

            Text(
                if (detected) "已找到纸张边缘，保持稳定后拍摄" else "把整张纸放进画面，尽量平行拍摄",
                color = Color.White.copy(alpha = .94f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .background(Color.Black.copy(alpha = .5f), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(26.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(enabled = !capturing) { onPickFromGallery() },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_quick_image),
                        contentDescription = "从相册选择",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("相册", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelMedium)
                }

                Box(
                    Modifier
                        .size(78.dp)
                        .scale(shutterAnimated)
                        .clickable(enabled = !capturing) {
                            scope.launch {
                                shutterScale = 0.86f
                                delay(120)
                                shutterScale = 1f
                            }
                            cameraController.capture()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White, style = Stroke(width = 5.dp.toPx()))
                        drawCircle(Color.White, radius = size.minDimension / 2f - 11.dp.toPx())
                    }
                }

                Spacer(Modifier.size(68.dp))
            }
        }

        if (cameraOpenError != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    cameraOpenError.orEmpty(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "也可以直接从相册选择纸张照片",
                    color = Color.White.copy(alpha = .8f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { onPickFromGallery() }.padding(8.dp),
                )
            }
        }
    }
}

private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
    val scale = min(1f, maxSide / maxOf(bitmap.width, bitmap.height).toFloat())
    if (scale >= 0.999f) return bitmap
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(48),
        (bitmap.height * scale).toInt().coerceAtLeast(48),
        true,
    )
}

private fun saveJpegToCache(context: Context, jpeg: ByteArray): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { it.write(jpeg) }
    return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Camera2 预览与拍照控制器：拍照字节输出前已按传感器方向旋转修正。 */
private class Camera2Controller(
    private val onPreviewReady: (Size) -> Unit,
    private val onCameraError: (String) -> Unit,
    private val onJpeg: (ByteArray) -> Unit,
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var textureView: TextureView? = null
    private var pendingSurface: Surface? = null
    private var sensorOrientation = 90
    private var previewSize = Size(1440, 1080)
    private var sessionConfigured = false
    private var sessionReady = false
    private var openFailed = false
    private var capturing = false

    fun open(context: Context) {
        if (cameraDevice != null) return
        handlerThread = HandlerThread("xyprt-camera").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        }.getOrNull()
        if (cameraId == null) {
            onCameraError("没有检测到相机")
            return
        }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        previewSize = previewSizes?.firstOrNull { it.width in 640..1920 && it.height in 480..1440 }
            ?: previewSizes?.firstOrNull()
            ?: Size(1440, 1080)
        val jpegSize = jpegSizes?.firstOrNull { it == previewSize }
            ?: jpegSizes?.firstOrNull { it.width in 640..1920 && it.height in 480..1440 }
            ?: previewSize
        imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes.firstOrNull()?.buffer ?: run { image.close(); return@setOnImageAvailableListener }
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                image.close()
                capturing = false
                onJpeg(rotateJpeg(bytes, sensorOrientation))
            }, handler)
        }
        runCatching {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    maybeCreateSession()
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    sessionConfigured = false
                    sessionReady = false
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    sessionConfigured = false
                    sessionReady = false
                    if (!openFailed) {
                        openFailed = true
                        onCameraError("相机打开失败，请重试")
                    }
                }
            }, handler)
        }.onFailure { onCameraError("无法打开相机") }
    }

    fun onSurfaceReady(view: TextureView, surface: SurfaceTexture) {
        textureView = view
        pendingSurface = Surface(surface)
        maybeCreateSession()
    }

    fun onSurfaceDestroyed() {
        pendingSurface = null
        sessionConfigured = false
        sessionReady = false
        runCatching { captureSession?.close() }
        captureSession = null
    }

    private fun maybeCreateSession() {
        val device = cameraDevice ?: return
        val surface = pendingSurface ?: return
        val reader = imageReader ?: return
        if (sessionConfigured) return
        runCatching {
            device.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sessionConfigured = true
                        sessionReady = true
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        previewRequest = request.build()
                        runCatching { session.setRepeatingRequest(previewRequest!!, null, handler) }
                        onPreviewReady(previewSize)
                        textureView?.let { configureTransform(it) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionConfigured = false
                        sessionReady = false
                        onCameraError("相机初始化失败")
                    }
                },
                handler,
            )
        }.onFailure {
            sessionConfigured = false
            sessionReady = false
            onCameraError("相机初始化失败")
        }
    }

    fun configureTransform(view: TextureView) {
        if (previewSize.width <= 0 || view.width <= 0 || view.height <= 0) return
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                view.height.toFloat() / previewSize.height.toFloat(),
                view.width.toFloat() / previewSize.width.toFloat(),
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        view.setTransform(matrix)
    }

    fun capture() {
        if (capturing) return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        if (!sessionReady) return
        capturing = true
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        runCatching { session.capture(request.build(), null, handler) }
            .onFailure {
                capturing = false
                onCameraError("拍照失败，请重试")
            }
    }

    fun close() {
        sessionReady = false
        sessionConfigured = false
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { pendingSurface?.release() }
        pendingSurface = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun rotateJpeg(bytes: ByteArray, orientation: Int): ByteArray {
        if (orientation == 0) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val swap = orientation % 180 == 90
        val outW = if (swap) bmp.height else bmp.width
        val outH = if (swap) bmp.width else bmp.height
        val rotated = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(rotated)
        canvas.drawColor(AndroidColor.WHITE)
        val matrix = Matrix().apply {
            postRotate(orientation.toFloat(), bmp.width / 2f, bmp.height / 2f)
            postTranslate((outW - bmp.width) / 2f, (outH - bmp.height) / 2f)
        }
        canvas.drawBitmap(bmp, matrix, null)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        rotated.recycle()
        return out.toByteArray()
    }
}
