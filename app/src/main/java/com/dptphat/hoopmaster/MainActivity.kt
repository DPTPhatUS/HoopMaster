package com.dptphat.hoopmaster

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: HoopViewModel by viewModels()
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var isSpeaking: Boolean = false
    private var pendingSpeech: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        textToSpeech = TextToSpeech(this, this, "com.google.android.tts")

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
            val languageReady = textToSpeech?.setLanguage(Locale.US) != TextToSpeech.LANG_MISSING_DATA
            textToSpeech?.setSpeechRate(0.66f)
            textToSpeech?.setPitch(1.0f)
            textToSpeech?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    runOnUiThread { onSpeechCompleted() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { onSpeechCompleted() }
                }
            })
            languageReady
        } else {
            false
        }
    }

    private fun speak(text: String) {
        val normalizedText = text.trim().replace("\\s+".toRegex(), " ")
        if (normalizedText.isBlank()) return
        if (!isTtsReady) return

        synchronized(this) {
            if (isSpeaking) {
                // Keep only the latest coaching sentence to avoid long queued audio.
                pendingSpeech = normalizedText
                return
            }
            isSpeaking = true
        }

        enqueueSpeech(normalizedText, addInterUtteranceGap = false)
    }

    private fun onSpeechCompleted() {
        val nextSpeech: String?
        synchronized(this) {
            isSpeaking = false
            nextSpeech = pendingSpeech
            pendingSpeech = null
            if (nextSpeech.isNullOrBlank()) {
                return
            }
            isSpeaking = true
        }

        val phrase = nextSpeech ?: return
        enqueueSpeech(phrase, addInterUtteranceGap = true)
    }

    private fun enqueueSpeech(text: String, addInterUtteranceGap: Boolean) {
        val tts = textToSpeech
        if (tts == null) {
            synchronized(this) {
                isSpeaking = false
                pendingSpeech = null
            }
            return
        }

        if (addInterUtteranceGap) {
            tts.playSilentUtterance(80L, TextToSpeech.QUEUE_ADD, "speech_gap_${System.nanoTime()}")
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "hoop_feedback_${System.nanoTime()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
        isSpeaking = false
        pendingSpeech = null
    }
}

private object AppRoute {
    const val HOME = "home"
    const val VOLUME_TEST = "volume_test"
    const val CURRENT_SESSION = "current_session"
    const val SESSION_RESULTS = "session_results"
}

@Composable
private fun HoopMasterApp(viewModel: HoopViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppRoute.HOME
    ) {
        composable(AppRoute.HOME) {
            HomeScreen(
                state = state,
                onNewPractice = {
                    viewModel.disconnect()
                    navController.navigate(AppRoute.VOLUME_TEST)
                }
            )
        }
        composable(AppRoute.VOLUME_TEST) {
            VolumeTestScreen(onSkip = { navController.navigate(AppRoute.CURRENT_SESSION) })
        }
        composable(AppRoute.CURRENT_SESSION) {
            CurrentSessionScreen(
                state = state,
                viewModel = viewModel,
                onSessionStopped = {
                    navController.navigate(AppRoute.SESSION_RESULTS) {
                        popUpTo(AppRoute.CURRENT_SESSION) { inclusive = true }
                    }
                }
            )
        }
        composable(AppRoute.SESSION_RESULTS) {
            SessionResultsScreen(
                state = state,
                onHome = {
                    viewModel.disconnect()
                    navController.navigate(AppRoute.HOME) {
                        popUpTo(AppRoute.HOME) { inclusive = true }
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
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
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
                    icon = { Icon(Icons.Filled.Person, contentDescription = "Profile") },
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
    var hasNavigatedToResults by remember { mutableStateOf(false) }
    var showGoodShotFlash by remember { mutableStateOf(false) }
    val goodShotFlashAlpha by animateFloatAsState(
        targetValue = if (showGoodShotFlash) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "goodShotFlashAlpha"
    )

    LaunchedEffect(state.throwCount) {
        val latestEntry = state.sessionLog.lastOrNull()
        val isGoodShot = latestEntry?.throwIndex == state.throwCount &&
            latestEntry.mistakeTitle.equals("No mistake detected", ignoreCase = true)

        if (isGoodShot) {
            showGoodShotFlash = true
            delay(900)
        }
        showGoodShotFlash = false
    }

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

    LaunchedEffect(state.sessionCompleted, state.sessionActive) {
        if (!state.sessionCompleted) {
            hasNavigatedToResults = false
            return@LaunchedEffect
        }

        if (!state.sessionActive && !hasNavigatedToResults) {
            hasNavigatedToResults = true
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

        if (goodShotFlashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF4ADE80).copy(alpha = 0.55f * goodShotFlashAlpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Good shot",
                    tint = Color.White.copy(alpha = goodShotFlashAlpha),
                    modifier = Modifier.size(120.dp)
                )
            }
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
                if (showGoodShotFlash) {
                    Text(
                        text = "Great shot!",
                        color = Color(0xFF86EFAC),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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

    val cameraSelector = when {
        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }

    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        preview,
        analysis
    )
}