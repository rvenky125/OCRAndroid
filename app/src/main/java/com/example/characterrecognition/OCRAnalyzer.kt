package com.example.characterrecognition

import android.annotation.SuppressLint
import android.graphics.*
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer


class OCRAnalyzer(
    private val tess: TessBaseAPI,
    private val previewView: PreviewView,
    private val onTextDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        CoroutineScope(Dispatchers.Unconfined).launch {
//            val bytes = image.planes.first().buffer.toByteArray()
//            Log.d("myTag", "bitmap length: ${bytes.size}")
            try {
//                val bitmap = image.toBitmap()
                val bitmap = image.toMat().toBitMap()
//                val bitmap = previewView.bitmap
                tess.setImage(bitmap)
                tess.getHOCRText(0)
//                Log.d("myTag", tess.utF8Text)
                onTextDetected(tess.utF8Text)
            } catch (e: Exception) {
                Log.d("myTag", "Failed to read text", e)
            } finally {
                image.close()
            }
        }
    }

    private fun Mat.toBitMap(): Bitmap? {
        var bmp: Bitmap? = null
        val rgb = Mat()
        cvtColor(this, rgb, COLOR_BGR2RGB)
        try {
            bmp = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgb, bmp)
        } catch (e: CvException) {
            Log.d("Exception", e.message!!)
        }
        return bmp?.toThresholdBitmap()
    }

    private fun ImageProxy.toMat(): Mat {
        val graySourceMatrix = Mat(height, width, CvType.CV_8UC1)
        val yBuffer = planes[0].buffer
        val ySize = yBuffer.remaining()
        val yPlane = ByteArray(ySize)
        yBuffer[yPlane, 0, ySize]
        graySourceMatrix.put(0, 0, yPlane)
        return graySourceMatrix
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(size = remaining()).also {
            get(it)
        }
    }

    private suspend fun ImageProxy.toBitmap(): Bitmap? {
        val nv21 = yuv420888ToNv21(this)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return yuvImage.toBitmap()
    }

    private suspend fun YuvImage.toBitmap(): Bitmap? {
        return withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            if (!compressToJpeg(Rect(0, 0, width, height), 100, out))
                return@withContext null
            val imageBytes: ByteArray = out.toByteArray()
            return@withContext BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }
    }

    private suspend fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val pixelCount = image.cropRect.width() * image.cropRect.height()
        val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
        val outputBuffer = ByteArray(pixelCount * pixelSizeBits / 8)
        imageToByteBuffer(image, outputBuffer, pixelCount)
        return outputBuffer
    }

    private suspend fun imageToByteBuffer(image: ImageProxy, outputBuffer: ByteArray, pixelCount: Int) {
        assert(image.format == ImageFormat.YUV_420_888)

        val imageCrop = image.cropRect
        val imagePlanes = image.planes

        imagePlanes.forEachIndexed { planeIndex, plane ->
            // How many values are read in input for each output value written
            // Only the Y plane has a value for every pixel, U and V have half the resolution i.e.
            //
            // Y Plane            U Plane    V Plane
            // ===============    =======    =======
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            val outputStride: Int

            // The index in the output buffer the next value will be written at
            // For Y it's zero, for U and V we start at the end of Y and interleave them i.e.
            //
            // First chunk        Second chunk
            // ===============    ===============
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            var outputOffset: Int

            when (planeIndex) {
                0 -> {
                    outputStride = 1
                    outputOffset = 0
                }
                1 -> {
                    outputStride = 2
                    // For NV21 format, U is in odd-numbered indices
                    outputOffset = pixelCount + 1
                }
                2 -> {
                    outputStride = 2
                    // For NV21 format, V is in even-numbered indices
                    outputOffset = pixelCount
                }
                else -> {
                    // Image contains more than 3 planes, something strange is going on
                    return@forEachIndexed
                }
            }

            val planeBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // We have to divide the width and height by two if it's not the Y plane
            val planeCrop = if (planeIndex == 0) {
                imageCrop
            } else {
                Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
                )
            }

            val planeWidth = planeCrop.width()
            val planeHeight = planeCrop.height()

            // Intermediate buffer used to store the bytes of each row
            val rowBuffer = ByteArray(plane.rowStride)

            // Size of each row in bytes
            val rowLength = if (pixelStride == 1 && outputStride == 1) {
                planeWidth
            } else {
                // Take into account that the stride may include data from pixels other than this
                // particular plane and row, and that could be between pixels and not after every
                // pixel:
                //
                // |---- Pixel stride ----|                    Row ends here --> |
                // | Pixel 1 | Other Data | Pixel 2 | Other Data | ... | Pixel N |
                //
                // We need to get (N-1) * (pixel stride bytes) per row + 1 byte for the last pixel
                (planeWidth - 1) * pixelStride + 1
            }

            for (row in 0 until planeHeight) {
                // Move buffer position to the beginning of this row
                planeBuffer.position(
                    (row + planeCrop.top) * rowStride + planeCrop.left * pixelStride)

                if (pixelStride == 1 && outputStride == 1) {
                    // When there is a single stride value for pixel and output, we can just copy
                    // the entire row in a single step
                    planeBuffer.get(outputBuffer, outputOffset, rowLength)
                    outputOffset += rowLength
                } else {
                    // When either pixel or output have a stride > 1 we must copy pixel by pixel
                    planeBuffer.get(rowBuffer, 0, rowLength)
                    for (col in 0 until planeWidth) {
                        outputBuffer[outputOffset] = rowBuffer[col * pixelStride]
                        outputOffset += outputStride
                    }
                }
            }
        }
    }
}


fun Bitmap.toThresholdBitmap(): Bitmap {
    val imageMat = Mat()
    Utils.bitmapToMat(this, imageMat)
    cvtColor(imageMat, imageMat, COLOR_BGR2GRAY)
    adaptiveThreshold(imageMat, imageMat, 255.0, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY,29, 10.0)
    Utils.matToBitmap(imageMat, this)
    return this
}