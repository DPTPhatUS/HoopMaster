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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dptphat.hoopmaster.camera.imageProxyToJpeg
import com.dptphat.hoopmaster.ui.theme.HoopMasterTheme
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
        isTtsReady = if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.setLanguage(Locale.US) != TextToSpeech.LANG_MISSING_DATA
        } else {
            false
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

private object AppRoute {
    const val Home = "home"
    const val VolumeTest = "volume_test"
    const val CurrentSession = "current_session"
    const val SessionResults = "session_results"
}

@Composable
private fun HoopMasterApp(viewModel: HoopViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppRoute.Home
    ) {
        composable(AppRoute.Home) {
            HomeScreen(
                state = state,
                onNewPractice = {
                    viewModel.disconnect()
                    navController.navigate(AppRoute.VolumeTest)
                }
            )
        }
        composable(AppRoute.VolumeTest) {
            VolumeTestScreen(onSkip = { navController.navigate(AppRoute.CurrentSession) })
        }
        composable(AppRoute.CurrentSession) {
            CurrentSessionScreen(
                state = state,
                viewModel = viewModel,
                onSessionStopped = {
                    navController.navigate(AppRoute.SessionResults) {
                        popUpTo(AppRoute.CurrentSession) { inclusive = true }
                    }
                }
            )
        }
        composable(AppRoute.SessionResults) {
            SessionResultsScreen(
                state = state,
                onHome = {
                    viewModel.disconnect()
                    navController.navigate(AppRoute.Home) {
                        popUpTo(AppRoute.Home) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: HoopUiState,
    onNewPractice: () -> Unit
) {
    val progress = (state.weeklyTotalShots.toFloat() / state.weeklyGoal.toFloat()).coerceIn(0f, 1f)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(painterResource(id = android.R.drawable.ic_menu_view), contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = { Icon(painterResource(id = android.R.drawable.ic_menu_recent_history), contentDescription = "History") },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    icon = { Icon(painterResource(id = android.R.drawable.ic_menu_myplaces), contentDescription = "Profile") },
                    label = { Text("Profile") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(text = "Hoop Master", style = MaterialTheme.typography.headlineMedium)

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp)) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 14.dp
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            Text(
                text = "Target this week: ${state.weeklyGoal} shots",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WeeklyInfoCard(
                    modifier = Modifier.weight(1f),
                    title = "Accuracy",
                    value = "${state.weeklyAccuracyPercent}%"
                )
                WeeklyInfoCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Shots",
                    value = state.weeklyTotalShots.toString()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onNewPractice,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("New practice session", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun WeeklyInfoCard(
    modifier: Modifier,
    title: String,
    value: String
) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = value, style = MaterialTheme.typography.titleLarge)
            Text(text = "This Week", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VolumeTestScreen(onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text(text = "Hoop Master", style = MaterialTheme.typography.headlineMedium)
        Icon(
            painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
            contentDescription = "Headphones",
            modifier = Modifier.size(140.dp)
        )
        Text(
            text = "Connect Bluetooth\nEarbuds",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Test Volume", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Icon(
                painter = painterResource(id = android.R.drawable.ic_lock_silent_mode_off),
                contentDescription = "Volume"
            )
        }
        Button(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Skip")
        }
    }
}

@Composable
private fun CurrentSessionScreen(
    state: HoopUiState,
    viewModel: HoopViewModel,
    onSessionStopped: () -> Unit
) {
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
    var waitingForStop by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        viewModel.ensureConnected()
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            viewModel.ensureConnected()
        }
    }

    LaunchedEffect(waitingForStop, state.sessionActive, state.sessionCompleted) {
        if (waitingForStop && !state.sessionActive && state.sessionCompleted) {
            waitingForStop = false
            onSessionStopped()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF07132A), Color(0xFF101D3A))))
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
                Text(text = "Status: ${state.statusMessage}", color = Color(0xFFD3E3FF))
                Text(text = "Throws: ${state.throwCount} | Points: ${state.totalPoints}", color = Color(0xFFC7D2FE))
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (state.sessionActive) {
                            waitingForStop = true
                            viewModel.stopSession()
                        } else {
                            viewModel.startSession()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    enabled = state.sessionId != null && !state.isConnecting,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (state.sessionActive) "Stop Session" else "Start Practice")
                }

                Text(
                    text = "Make sure your whole body is visible in camera view.",
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )

                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFFCA5A5),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionResultsScreen(
    state: HoopUiState,
    onHome: () -> Unit
) {
    val summary = state.lastSessionSummary ?: SessionResultSummary(
        totalThrows = state.throwCount,
        totalPoints = state.totalPoints,
        noMistakeRate = state.weeklyAccuracyPercent.toDouble()
    )
    val mistakeLog = state.sessionLog.filter {
        !it.mistakeTitle.equals("No mistake detected", ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = "Session Results", style = MaterialTheme.typography.headlineMedium)

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Total Throws: ${summary.totalThrows}", style = MaterialTheme.typography.titleMedium)
                Text(text = "Total Points: ${summary.totalPoints}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "No-Mistake Rate: ${summary.noMistakeRate.roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Text(text = "Mistake Log", style = MaterialTheme.typography.titleLarge)

        if (mistakeLog.isEmpty()) {
            Card(shape = RoundedCornerShape(14.dp)) {
                Text(
                    text = "No mistakes logged in this session.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            mistakeLog.forEach { entry ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Throw #${entry.throwIndex}: ${entry.mistakeTitle}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(text = "Feedback: ${entry.feedback}", style = MaterialTheme.typography.bodySmall)
                        Text(text = "Target: ${entry.target}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onHome,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Home")
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
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    var lastFrameAt = 0L
    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
        imageProxy.use { imageProxyFrame ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt >= 300L) {
                imageProxyToJpeg(imageProxyFrame, jpegQuality = 55)?.let(onFrame)
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