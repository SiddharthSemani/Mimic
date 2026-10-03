package com.ssemani.mimic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Class ids produced by the multiclass selfie model (assets/selfie_segmenter.tflite):
 *   0 = background   1 = hair         2 = body-skin
 *   3 = face-skin    4 = clothes      5 = others (accessories)
 */
// The single knob: which classes the grade must NOT touch. Change this set to protect
// more/less (e.g. add 1 to also protect hair).
val PROTECTED_CLASSES = setOf(2, 3) // body-skin + face-skin

const val MODEL_ASSET = "selfie_segmenter.tflite"

// Edge softening. The radius is calibrated for a REFERENCE_EDGE_PX-long image and scaled
// up/down with the actual image so a 1 MP and a 12 MP photo get the same visual softness.
const val BLUR_RADIUS_BASE_PX = 15
const val REFERENCE_EDGE_PX = 1080

// Cap the longest edge on decode. This verification step only needs a mask to eyeball, and
// the model runs at 256x256 internally anyway — capping keeps memory bounded on huge photos.
const val MAX_IMAGE_DIM = 2048

/** Builds the IMAGE-mode segmenter once. Caller owns it and must call close(). */
fun createSkinSegmenter(context: Context): ImageSegmenter {
    val baseOptions = BaseOptions.builder()
        .setModelAssetPath(MODEL_ASSET)
        .setDelegate(Delegate.CPU)
        .build()

    // IMAGE = one-shot on a single still frame (not LIVE_STREAM); category mask = per-pixel class ids.
    val options = ImageSegmenter.ImageSegmenterOptions.builder()
        .setBaseOptions(baseOptions)
        .setRunningMode(RunningMode.IMAGE)
        .setOutputCategoryMask(true)
        .build()

    return ImageSegmenter.createFromOptions(context, options)
}

/**
 * Runs segmentation on [bitmap] and returns a soft grayscale skin-protection mask
 * (white = fully protected skin, black = unprotected, gray = crossfade at edges).
 */
fun buildSoftSkinMask(segmenter: ImageSegmenter, bitmap: Bitmap): Bitmap {
    val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
    val mask = result.categoryMask().orElseThrow { IllegalStateException("segmenter returned no category mask") }
    val width = mask.width
    val height = mask.height

    // --- Build the hard protection mask -------------------------------------------------
    // ByteBufferExtractor gives one unsigned byte per pixel = the winning class id.
    // protection[i] = 1.0 if that pixel's class is one we protect (skin), else 0.0.
    val classIds = ByteBufferExtractor.extract(mask)
    val protection = FloatArray(width * height)
    for (i in protection.indices) {
        val classId = classIds.get(i).toInt() and 0xFF
        protection[i] = if (classId in PROTECTED_CLASSES) 1f else 0f
    }
    mask.close() // free the native mask now that its bytes are copied out

    // --- Soften the edges ----------------------------------------------------------------
    // A crisp 0/1 boundary bands badly; blurring turns it into a crossfade so a later grade
    // fades across the skin edge instead of cutting a hard line. Three passes of a box blur
    // closely approximate a Gaussian while staying O(pixels) regardless of radius.
    val radius = (BLUR_RADIUS_BASE_PX * max(width, height) / REFERENCE_EDGE_PX)
        .coerceAtLeast(1)
    boxBlurApproxGaussian(protection, width, height, radius)

    // --- Pack 0..1 into a grayscale bitmap (equal R=G=B) ---------------------------------
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val g = (protection[i] * 255f).roundToInt().coerceIn(0, 255)
        pixels[i] = Color.rgb(g, g, g)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** Writes [mask] as a PNG into the app external files dir and returns the file. */
fun saveMaskPng(context: Context, mask: Bitmap): File {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "skin_mask_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/** Decodes [uri] to a Bitmap, sampled down to [maxDim] on its longest edge and EXIF-rotated upright. */
fun decodeUprightBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap {
    val resolver = context.contentResolver

    // Pass 1: read the dimensions only, to pick a power-of-two downsample factor. Note
    // decodeStream returns null in inJustDecodeBounds mode by design — it only fills
    // outWidth/outHeight — so the null-check is on opening the stream, not on the decode.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (resolver.openInputStream(uri) ?: error("cannot open image: $uri")).use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2

    // Pass 2: decode at that sample size. Here a null result means the decode itself failed.
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = (resolver.openInputStream(uri) ?: error("cannot open image: $uri")).use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: error("cannot decode image: $uri")

    // BitmapFactory ignores EXIF, so rotate to match what the picker/Coil shows upright.
    val orientation = (resolver.openInputStream(uri) ?: error("cannot open image: $uri")).use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    return applyExifOrientation(decoded, orientation)
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bitmap // already upright
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// --- Separable box blur (3 passes ≈ Gaussian), in place on a single-channel 0..1 array -----

private fun boxBlurApproxGaussian(data: FloatArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
    if (radius < 1) return
    val tmp = FloatArray(data.size)
    repeat(passes) {
        boxBlurHorizontal(data, tmp, width, height, radius) // data -> tmp
        boxBlurVertical(tmp, data, width, height, radius)    // tmp  -> data
    }
}

private fun boxBlurHorizontal(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
    for (y in 0 until height) {
        val row = y * width
        // Seed the sliding window [0, radius]; count tracks how many samples are inside the
        // image so edge pixels average over fewer samples instead of darkening.
        var sum = 0f
        var count = 0
        for (x in 0..minOf(radius, width - 1)) { sum += src[row + x]; count++ }
        dst[row] = sum / count
        for (x in 1 until width) {
            val add = x + radius
            val remove = x - radius - 1
            if (add < width) { sum += src[row + add]; count++ }
            if (remove >= 0) { sum -= src[row + remove]; count-- }
            dst[row + x] = sum / count
        }
    }
}

private fun boxBlurVertical(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
    for (x in 0 until width) {
        var sum = 0f
        var count = 0
        for (y in 0..minOf(radius, height - 1)) { sum += src[y * width + x]; count++ }
        dst[x] = sum / count
        for (y in 1 until height) {
            val add = y + radius
            val remove = y - radius - 1
            if (add < height) { sum += src[add * width + x]; count++ }
            if (remove >= 0) { sum -= src[remove * width + x]; count-- }
            dst[y * width + x] = sum / count
        }
    }
}
