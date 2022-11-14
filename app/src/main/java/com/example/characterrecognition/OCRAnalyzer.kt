package com.example.characterrecognition

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel.RIL_PARA
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.Core.rotate
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc.*
import java.nio.ByteBuffer


class OCRAnalyzer(
    private val tess: TessBaseAPI,
    private val onTextDetected: (String, Bitmap?) -> Unit
) : ImageAnalysis.Analyzer {
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        try {
//            val bitmap = image.toMat().toBitMap()?.rotate(image.imageInfo.rotationDegrees)
            val bitmap = BitmapUtils.getBitmap(image)
            tess.clear()
            tess.setImage(bitmap)
//            tess.getHOCRText(0)
            if (tess.meanConfidence() > 30) {
                onTextDetected(tess.resultIterator.getUTF8Text(RIL_PARA), null)
            }
//            bitmap?.recycle()
        } catch (e: Exception) {
            Log.d("myTag", "Failed to read text", e)
        } finally {
            image.close()
        }
    }

    fun Image.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
        return bmp
    }

    private fun ImageProxy.toMat(): Mat {
        val graySourceMatrix = Mat(height, width, CvType.CV_8UC1)
        val yBuffer = planes[0].buffer
        val ySize = yBuffer.remaining()
        val yPlane = ByteArray(ySize)
        yBuffer[yPlane, 0, ySize]
        graySourceMatrix.put(0, 0, yPlane)
        rotate(graySourceMatrix, graySourceMatrix, imageInfo.rotationDegrees)
        return graySourceMatrix
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(size = remaining()).also {
            get(it)
        }
    }
}


fun Bitmap.toThresholdBitmap(): Bitmap {
    val imageMat = Mat()
    Utils.bitmapToMat(this, imageMat)
    cvtColor(imageMat, imageMat, COLOR_BGR2GRAY)
    adaptiveThreshold(
        imageMat,
        imageMat,
        255.0,
        ADAPTIVE_THRESH_GAUSSIAN_C,
        THRESH_BINARY,
        29,
        10.0
    )
    Utils.matToBitmap(imageMat, this)
    return this
}

fun Bitmap.rotate(angle: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(angle.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}


//this funtcion will correct image.
fun deskew(src: Mat, angle: Double): Mat {
    val center = Point((src.width() / 2).toDouble(), (src.height() / 2).toDouble())
    val rotImage = getRotationMatrix2D(center, angle, 1.0)
    //1.0 means 100 % scale
    val size = Size(src.width().toDouble(), src.height().toDouble())
    //Imgproc.warpAffine(src, src, rotImage, size, Imgproc.INTER_LINEAR + Imgproc.CV_WARP_FILL_OUTLIERS);
    warpAffine(
        src,
        src,
        rotImage,
        size,
        INTER_LINEAR + CV_WARP_FILL_OUTLIERS,
        0,
        Scalar(255.0, 255.0, 255.0)
    )
    return src
}

fun Bitmap.deskew(): Bitmap {
    val imgMat = Mat()
    Utils.bitmapToMat(this, imgMat)

    //convert image into grayscale
    cvtColor(imgMat, imgMat, COLOR_BGR2GRAY)


    //Barbarize it
    //Use adaptive threshold if necessary
    // Imgproc.adaptiveThreshold(img, img, 255, ADAPTIVE_THRESH_MEAN_C, THRESH_BINARY, 15, 40);
    threshold(imgMat, imgMat, 200.0, 255.0, THRESH_BINARY)

    //Invert the colors (because objects are represented as white pixels, and the background is represented by black pixels)
    Core.bitwise_not(imgMat, imgMat)
    val element = getStructuringElement(MORPH_RECT, Size(3.0, 3.0))

    //We can now perform our erosion, we must declare our rectangle-shaped structuring element and call the erode function
    erode(imgMat, imgMat, element)

    //Find all white pixels
    val wLocMat: Mat = Mat.zeros(imgMat.size(), imgMat.type())
    Core.findNonZero(imgMat, wLocMat)

    //Create an empty Mat and pass it to the function
    val matOfPoint = MatOfPoint(wLocMat)

    //Translate MatOfPoint to MatOfPoint2f in order to user at a next step
    val mat2f = MatOfPoint2f()
    matOfPoint.convertTo(mat2f, CvType.CV_32FC2)

    //Get rotated rect of white pixels
    val rotatedRect = minAreaRect(mat2f)
    val vertices: Array<Point?> = arrayOfNulls<Point>(4)
    rotatedRect.points(vertices)
    val boxContours: MutableList<MatOfPoint> = ArrayList()
    boxContours.add(MatOfPoint(*vertices))
    drawContours(imgMat, boxContours, 0, Scalar(128.0, 128.0, 128.0), -1)
    for (i in 0..3) line(
        imgMat,
        vertices[i], vertices[(i + 1) % 4], Scalar(255.0, 0.0, 0.0), 2
    )
//    if (rotatedRect.size.width > rotatedRect.size.height && resultAngle < -45f) {
//        rotatedRect.angle += 90.0f
//    } else if (rotatedRect.size.width < rotatedRect.size.height && resultAngle < -45f) {
//        rotatedRect.angle = rotatedRect.angle + 90f
//    }

    //Or
    val angle = if (rotatedRect.angle < -45) -(rotatedRect.angle + 90f) else -rotatedRect.angle

    Utils.bitmapToMat(this, imgMat)
    val result = deskew(imgMat, angle)
    val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(result, bitmap)
    return bitmap
}