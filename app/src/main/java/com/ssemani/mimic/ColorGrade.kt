package com.ssemani.mimic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kotlin port of bake_lut.py — the Reinhard reference→target colour transfer, baked into a
 * LUT. Same math as the Python: per channel in LAB,
 *
 *     out = (in - target_mean) / target_std * ref_std + ref_mean   (clipped)
 *
 * done ONCE here to bake the cube, never per-pixel. LAB is used because its channels are
 * near-independent (L = lightness, a/b = colour-opponent axes), so shifting a mean warms or
 * cools without dragging exposure along.
 *
 * To keep the image statistics and the cube directly comparable, everything uses the same LAB
 * convention OpenCV's uint8 cv2.COLOR_BGR2LAB uses: the 0..255 scale (L*255/100, a+128, b+128)
 * with sRGB gamma applied. There's no colour library on the classpath, so it's implemented here.
 */

const val LUT_SIZE = 33 // 33 is the near-universal .cube size; matches bake_lut.py

// D65 white point and sRGB<->XYZ matrices, same constants OpenCV uses for BGR2LAB.
private const val XN = 0.950456f
private const val YN = 1.0f
private const val ZN = 1.088754f

/** Reference and target both measured in LAB: per-channel mean and (population) std. */
private class LabStats(val mean: FloatArray, val std: FloatArray)

/**
 * Bakes a region-restricted reference→target grade into a LUT laid out as a 2D "atlas" bitmap
 * so it can be uploaded as a plain 2D GL ES 2.0 texture (no 3D textures).
 *
 * The stats that define the transfer are measured over ONE region of each image, chosen by
 * [subject] against each image's own mask: subject pixels (mask >= 128) when [subject] is true,
 * background pixels otherwise. So `subject = false` bakes reference-background → target-background.
 *
 * ATLAS LAYOUT (the shader MUST match this):
 *   Cube is LUT_SIZE^3 (33^3). The blue axis is sliced; the 33 slices sit side by side
 *   horizontally. Within a slice, red runs along x and green along y. So for cube indices
 *   (ri, gi, bi), each in 0..LUT_SIZE-1:
 *
 *       atlasX = bi * LUT_SIZE + ri
 *       atlasY = gi
 *
 *   Atlas size = (LUT_SIZE*LUT_SIZE) x LUT_SIZE = 1089 x 33. Row 0 is green index 0 (top of
 *   the PNG). The pixel at (atlasX, atlasY) holds the graded RGB for input (ri, gi, bi).
 *   Sampling in the shader: blue picks the slice (lerp between floor/ceil slices), red = x
 *   within the slice, green = y. Use CLAMP_TO_EDGE + LINEAR + no mipmaps (valid for this
 *   NPOT texture on ES 2.0).
 */
fun bakeLutAtlas(
    reference: Bitmap,
    referenceMask: Bitmap,
    target: Bitmap,
    targetMask: Bitmap,
    subject: Boolean,
): Bitmap {
    val ref = computeLabStats(reference, referenceMask, subject)
    val tgt = computeLabStats(target, targetMask, subject)
    // Guard a flat channel (std 0): substitute 1.0, not epsilon, so the ratio can't blow the
    // channel out to a solid colour — same choice as bake_lut.py's safe_std.
    val tgtStdSafe = FloatArray(3) { if (tgt.std[it] < 1e-6f) 1f else tgt.std[it] }

    val n = LUT_SIZE
    val atlasW = n * n
    val atlasH = n
    val pixels = IntArray(atlasW * atlasH)
    val step = 255f / (n - 1) // input value for each grid index, matching np.linspace(0,255,33)

    val lab = FloatArray(3)
    val graded = FloatArray(3)
    val rgb = IntArray(3)

    for (bi in 0 until n) {
        val bIn = (bi * step).roundToInt()
        for (gi in 0 until n) {
            val gIn = (gi * step).roundToInt()
            val rowBase = gi * atlasW
            for (ri in 0 until n) {
                val rIn = (ri * step).roundToInt()

                // Input RGB -> LAB via the exact same path the images took.
                rgbToLab(rIn, gIn, bIn, lab)

                // Reinhard, per channel: (a) recenter by target mean, (b) scale by
                // ref_std / target_std, (c) add ref mean. Then clip to the 0..255 LAB range.
                for (c in 0..2) {
                    val v = (lab[c] - tgt.mean[c]) / tgtStdSafe[c] * ref.std[c] + ref.mean[c]
                    graded[c] = v.coerceIn(0f, 255f)
                }

                labToRgb(graded, rgb)
                pixels[rowBase + bi * n + ri] = Color.rgb(rgb[0], rgb[1], rgb[2])
            }
        }
    }
    return Bitmap.createBitmap(pixels, atlasW, atlasH, Bitmap.Config.ARGB_8888)
}

/** Writes the LUT atlas as a PNG into the app external files dir and returns the file. */
fun saveLutPng(context: Context, atlas: Bitmap, prefix: String = "lut_atlas"): File {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { atlas.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/**
 * Mean/std over ONE region of [bmp]. A pixel is counted only when its [mask] value classifies
 * it into the requested region: mask red >= 128 means "subject", below means "background", and
 * we keep pixels where that matches [subject]. The mask is the soft (blurred) subject mask, so
 * thresholding at 128 lands close to the original hard boundary. Feeding the same function
 * different (mask, subject) pairs is how one Reinhard stat path serves every region.
 */
private fun computeLabStats(bmp: Bitmap, mask: Bitmap, subject: Boolean): LabStats {
    require(mask.width == bmp.width && mask.height == bmp.height) { "mask/image size mismatch" }
    val w = bmp.width
    val h = bmp.height
    val n = w * h
    val px = IntArray(n)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val mpx = IntArray(n)
    mask.getPixels(mpx, 0, w, 0, 0, w, h)

    // Double accumulators; std via E[x^2]-E[x]^2 = numpy's population std (ddof=0).
    var sL = 0.0; var sA = 0.0; var sB = 0.0
    var qL = 0.0; var qA = 0.0; var qB = 0.0
    var count = 0
    val lab = FloatArray(3)
    for (i in 0 until n) {
        // Grayscale mask: any channel works; use red. >= 128 == subject.
        val isSubject = ((mpx[i] shr 16) and 0xFF) >= 128
        if (isSubject != subject) continue
        val p = px[i]
        rgbToLab((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, lab)
        sL += lab[0]; sA += lab[1]; sB += lab[2]
        qL += lab[0].toDouble() * lab[0]; qA += lab[1].toDouble() * lab[1]; qB += lab[2].toDouble() * lab[2]
        count++
    }
    val nn = count.coerceAtLeast(1).toDouble() // avoid /0 if a region is empty (its LUT is unused)
    val mL = sL / nn; val mA = sA / nn; val mB = sB / nn
    fun std(sq: Double, m: Double) = sqrt(max(0.0, sq / nn - m * m))
    return LabStats(
        floatArrayOf(mL.toFloat(), mA.toFloat(), mB.toFloat()),
        floatArrayOf(std(qL, mL).toFloat(), std(qA, mA).toFloat(), std(qB, mB).toFloat()),
    )
}

// --- sRGB (0..255) <-> LAB (0..255 scale, matching OpenCV uint8 BGR2LAB) ---------------------

private fun rgbToLab(r: Int, g: Int, b: Int, out: FloatArray) {
    val rl = srgbToLinear(r / 255f)
    val gl = srgbToLinear(g / 255f)
    val bl = srgbToLinear(b / 255f)

    val x = rl * 0.412453f + gl * 0.357580f + bl * 0.180423f
    val y = rl * 0.212671f + gl * 0.715160f + bl * 0.072169f
    val z = rl * 0.019334f + gl * 0.119193f + bl * 0.950227f

    val fx = labF(x / XN)
    val fy = labF(y / YN)
    val fz = labF(z / ZN)

    val l = (116f * fy - 16f).coerceIn(0f, 100f)
    out[0] = l * 255f / 100f     // L onto 0..255
    out[1] = 500f * (fx - fy) + 128f  // a biased by 128
    out[2] = 200f * (fy - fz) + 128f  // b biased by 128
}

private fun labToRgb(lab: FloatArray, out: IntArray) {
    val l = lab[0] * 100f / 255f
    val a = lab[1] - 128f
    val b = lab[2] - 128f

    val fy = (l + 16f) / 116f
    val fx = fy + a / 500f
    val fz = fy - b / 200f

    val x = labFInv(fx) * XN
    val y = labFInv(fy) * YN
    val z = labFInv(fz) * ZN

    val rl = x * 3.240479f + y * -1.537150f + z * -0.498535f
    val gl = x * -0.969256f + y * 1.875991f + z * 0.041556f
    val bl = x * 0.055648f + y * -0.204043f + z * 1.057311f

    out[0] = (linearToSrgb(rl).coerceIn(0f, 1f) * 255f).roundToInt()
    out[1] = (linearToSrgb(gl).coerceIn(0f, 1f) * 255f).roundToInt()
    out[2] = (linearToSrgb(bl).coerceIn(0f, 1f) * 255f).roundToInt()
}

private fun labF(t: Float): Float =
    if (t > 0.008856f) Math.cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f

private fun labFInv(t: Float): Float {
    val t3 = t * t * t
    return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f
}

private fun srgbToLinear(c: Float): Float =
    if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f

private fun linearToSrgb(c: Float): Float =
    if (c > 0.0031308f) 1.055f * c.pow(1f / 2.4f) - 0.055f else 12.92f * c
