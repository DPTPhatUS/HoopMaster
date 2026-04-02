package com.dptphat.hoopmaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dptphat.hoopmaster.camera.imageProxyToJpeg
import com.dptphat.hoopmaster.ui.theme.HoopMasterTheme
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: HoopViewModel by viewModels()
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        textToSpeech = TextToSpeech(this, this)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.speechRequests.collect { feedback ->
                    speak(feedback)
                }
            }
        }

        setContent {
            HoopMasterTheme {
                HoopMasterApp(viewModel = viewModel)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = textToSpeech?.setLanguage(Locale.US) != TextToSpeech.LANG_MISSING_DATA
        } else {
            isTtsReady = false
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        if (!isTtsReady) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hoop_feedback")
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
    }
}

@Composable
private fun HoopMasterApp(viewModel: HoopViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF07132A), Color(0xFF101D3A))
                    )
                )
        ) {
            if (hasCameraPermission) {
                CameraFeed(
                    modifier = Modifier.fillMaxSize(),
                    onFrame = { bytes -> viewModel.sendFrame(bytes) }
                )
            } else {
                PermissionHint(
                    modifier = Modifier.fillMaxSize(),
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .alpha(0.9f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xAA000000)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Hoop Master", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        text = "Status: ${state.statusMessage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD3E3FF)
                    )
                    Text(
                        text = "Session: ${state.sessionId ?: "not connected"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB2C5EE)
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC111827))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::onBaseUrlChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Backend URL") },
                        singleLine = true,
                        enabled = state.sessionId == null
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = viewModel::connect, enabled = state.sessionId == null && !state.isConnecting) {
                            Text(if (state.isConnecting) "Connecting..." else "Connect")
                        }
                        Button(onClick = viewModel::startSession, enabled = state.sessionId != null && !state.sessionActive) {
                            Text("Start")
                        }
                        Button(onClick = viewModel::stopSession, enabled = state.sessionActive) {
                            Text("Stop")
                        }
                        Button(onClick = viewModel::resetSession, enabled = state.sessionId != null) {
                            Text("Reset")
                        }
                        Button(onClick = viewModel::disconnect, enabled = state.sessionId != null) {
                            Text("Disconnect")
                        }
                    }

                    Text(
                        text = "Event WS: ${state.eventsConnected} | Video WS: ${state.videoConnected} | Camera(active): ${state.cameraConnected}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC7D2FE)
                    )
                    Text(
                        text = "Throws: ${state.throwCount} | Points: ${state.totalPoints} | Target: ${state.lastTarget}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC7D2FE)
                    )
                    Text(
                        text = "Live feedback: ${state.lastFeedback}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    state.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = Color(0xFFFCA5A5),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionHint(
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC111827))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = "Camera permission is required", color = Color.White)
                Button(onClick = onRequestPermission) { Text("Grant camera access") }
            }
        }
    }
}

@Composable
private fun CameraFeed(
    modifier: Modifier = Modifier,
    onFrame: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewContext ->
            val previewView = PreviewView(previewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(
                    cameraProvider = cameraProvider,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    cameraExecutor = cameraExecutor,
                    onFrame = onFrame
                )
            }, ContextCompat.getMainExecutor(context))

            previewView
        }
    )
}

private fun bindCameraUseCases(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    cameraExecutor: java.util.concurrent.ExecutorService,
    onFrame: (ByteArray) -> Unit
) {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }

    val analysis = ImageAnalysis.Builder()
        .setTargetResolution(Size(640, 480))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    var lastFrameAt = 0L
    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
        imageProxy.use { imageProxy ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt >= 300L) {
                imageProxyToJpeg(imageProxy, jpegQuality = 55)?.let(onFrame)
                lastFrameAt = now
            }
        }
    }

    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        CameraSelector.DEFAULT_BACK_CAMERA,
        preview,
        analysis
    )
}