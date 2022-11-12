package com.example.characterrecognition

import android.app.Application
import android.content.res.AssetManager
import android.os.StrictMode
import android.util.Log
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Suppress("SameParameterValue")
class CharacterRecognition: Application() {
    override fun onCreate() {
        super.onCreate()

        if (!OpenCVLoader.initDebug())
            Log.e("OpenCV", "Unable to load OpenCV!")
        else
            Log.d("OpenCV", "OpenCV loaded Successfully!")

        val tesseractDir = File(filesDir,"tesseract")

        if (!tesseractDir.exists()) {
            tesseractDir.mkdir()
        }

        val tessDir = File(tesseractDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdir()
        }
        val engFile = File(tessDir, "eng.traineddata")
        if (!engFile.exists()) {
            copyFile(assets, "eng.traineddata", engFile)
        }
    }


    private fun copyFile(
        am: AssetManager,
        assetName: String,
        outFile: File
    ) {
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
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}