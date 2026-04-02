package com.dptphat.hoopmaster.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun imageProxyToJpeg(image: ImageProxy, jpegQuality: Int = 60): ByteArray? {
    return when (image.format) {
        ImageFormat.YUV_420_888 -> yuv420888ToJpeg(image, jpegQuality)
        ImageFormat.JPEG -> {
            val buffer = image.planes.firstOrNull()?.buffer ?: return null
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            data
        }
        else -> null
    }
}

private fun yuv420888ToJpeg(image: ImageProxy, quality: Int): ByteArray {
    val nv21 = yuv420888ToNv21(image)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
    return out.toByteArray()
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)

    val chromaRowStride = image.planes[1].rowStride
    val chromaPixelStride = image.planes[1].pixelStride
    val width = image.width
    val height = image.height

    var offset = ySize
    val uBytes = ByteArray(uSize)
    val vBytes = ByteArray(vSize)
    uBuffer.get(uBytes)
    vBuffer.get(vBytes)

    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val vuPos = row * chromaRowStride + col * chromaPixelStride
            nv21[offset++] = vBytes[vuPos]
            nv21[offset++] = uBytes[vuPos]
        }
    }

    return nv21
}

