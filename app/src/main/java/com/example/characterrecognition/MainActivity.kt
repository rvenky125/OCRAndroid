package com.example.characterrecognition

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.characterrecognition.ui.theme.CharacterRecognitionTheme
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    private val tess = TessBaseAPI()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var imageAnalysis: ImageAnalysis? = null

        setContent {
            CharacterRecognitionTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background
                ) {
                    var text by remember {
                        mutableStateOf("")
                    }


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
                        if (hasCamPermission) {
                            AndroidView(
                                factory = { context ->
                                    val previewView = PreviewView(context)
                                    val preview = Preview.Builder().build()
                                    val selector = CameraSelector.Builder()
                                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                        .build()
                                    preview.setSurfaceProvider(previewView.surfaceProvider)
                                    imageAnalysis = ImageAnalysis.Builder()
                                        .setTargetResolution(
                                            Size(
                                                previewView.width,
                                                previewView.height
                                            )
                                        )
                                        .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    imageAnalysis?.setAnalyzer(
                                        Executors.newSingleThreadExecutor(),
//                                        ContextCompat.getMainExecutor(context),
                                        OCRAnalyzer(tess = tess, previewView = previewView) {
                                            text = it
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
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = text,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp)
                                    .weight(1f),
                                color = MaterialTheme.colors.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val dataPath: String = File(filesDir, "tesseract").absolutePath
        Log.d("myTag", dataPath)
        if (!tess.init(dataPath, "eng")) {
            tess.recycle()
            return
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tess.recycle()
    }
}