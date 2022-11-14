package com.example.characterrecognition

import android.app.Application
import android.content.res.AssetManager
import android.os.StrictMode
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Suppress("SameParameterValue")
class CharacterRecognition : Application() {
    override fun onCreate() {
        super.onCreate()

        if (!OpenCVLoader.initDebug())
            Log.e("OpenCV", "Unable to load OpenCV!")
        else
            Log.d("OpenCV", "OpenCV loaded Successfully!")

        val tesseractDir = File(filesDir, "tesseract")

        if (!tesseractDir.exists()) {
            tesseractDir.mkdir()
        }

        val tessDir = File(tesseractDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdir()
        }

        val imgFile = File(cacheDir, "img.png")
        if (!imgFile.exists()) {
            imgFile.createNewFile()
        }
    }
}