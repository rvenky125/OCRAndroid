package com.example.characterrecognition

import android.Manifest
import android.R.attr.bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.characterrecognition.ui.theme.CharacterRecognitionTheme
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY
import org.opencv.imgproc.Imgproc.THRESH_BINARY
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume


class MainActivity : ComponentActivity() {
    private val tess = TessBaseAPI()
    private val executor = Executors.newSingleThreadExecutor()
    private var tessInitialized by mutableStateOf(false)

    private var previousTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeepScreenOn()
            CharacterRecognitionTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background
                ) {
                    var text by remember {
                        mutableStateOf("")
                    }

                    val scrollState = rememberScrollState()

//                    var bitmap by remember {
//                        mutableStateOf<Bitmap?>(null)
//                    }

                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val cameraProviderFuture = remember {
                        ProcessCameraProvider.getInstance(context)
                    }

                    var hasCamPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }
                    val launcher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = { granted ->
                            hasCamPermission = granted
                        }
                    )

                    LaunchedEffect(key1 = true) {
                        launcher.launch(Manifest.permission.CAMERA)
                    }

                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (hasCamPermission && tessInitialized) {
//                            Box(modifier = Modifier.weight(1f)) {
                                AndroidView(
                                    factory = { context ->
                                        val previewView = PreviewView(context)
                                        val preview = Preview.Builder().build()
                                        val selector = CameraSelector.Builder()
                                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                            .build()
                                        preview.setSurfaceProvider(previewView.surfaceProvider)
                                        val imageAnalysis = ImageAnalysis.Builder()
                                            .setTargetResolution(
                                                Size(
                                                    previewView.width,
                                                    previewView.height
                                                )
                                            )
                                            .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        imageAnalysis.setAnalyzer(
                                            executor,
                                            OCRAnalyzer(
                                                tess = tess,
                                            ) { txt, bp ->
                                                text = txt
//                                                bitmap = bp
                                            }
                                        )

                                        try {
                                            cameraProviderFuture.get().bindToLifecycle(
                                                lifecycleOwner,
                                                selector,
                                                preview,
                                                imageAnalysis
                                            )
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                        previewView
                                    },
//                                    modifier = Modifier.weight(1f)
                                )

//                                bitmap?.let {
//                                    Image(
//                                        bitmap = it.asImageBitmap(),
//                                        contentDescription = null,
//                                        modifier = Modifier
//                                            .width(100.dp)
//                                            .height(150.dp)
//                                    )
//                                }
//                            }
                        }

                        Text(
                            text = text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                                .weight(1f)
                                .verticalScroll(scrollState),
                            color = MaterialTheme.colors.onSurface
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val tesseractDir = File(filesDir, "tesseract")
            val tessDir = File(tesseractDir, "tessdata")

            val engFile = File(tessDir, "eng.traineddata")
            if (!engFile.exists()) {
                copyFile(assets, "eng.traineddata", engFile)
            }

            val dataPath: String = File(filesDir, "tesseract").absolutePath
            Log.d("myTag", dataPath)
            if (!tess.init(dataPath, "eng")) {
                tess.recycle()
                return@launch
            }

            tessInitialized = true
        }
    }

    private suspend fun copyFile(
        am: AssetManager,
        assetName: String,
        outFile: File
    ) {
        suspendCancellableCoroutine {
            try {
                am.open(assetName).use { `in` ->
                    FileOutputStream(outFile).use { out ->
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (`in`.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
                it.resume(Unit)
            } catch (e: IOException) {
                e.printStackTrace()
                it.resume(Unit)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tess.recycle()
        executor.shutdown()
    }
}


@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}