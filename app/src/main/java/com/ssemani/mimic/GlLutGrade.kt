package com.ssemani.mimic

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MimicGL"

// Full-screen quad in clip space, drawn as a TRIANGLE_STRIP: BL, BR, TL, TR.
private val QUAD_XY = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

// UVs per vertex, same order. (0,0) on clip bottom-left -> texture top (t=0). This double
// flip (clip bottom = image top, and glReadPixels reads bottom-up) cancels out, so the pixels
// read back upright with no manual row flip. Photo, mask and this quad all share these UVs.
private val QUAD_UV = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aUV;
varying vec2 vUV;
void main() {
    gl_Position = aPosition;
    vUV = aUV;
}
"""

// LUT_SIZE (33) is baked in as a const so it matches ColorGrade.LUT_SIZE and the atlas packing.
private val FRAGMENT_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uPhoto;
uniform sampler2D uLutBg;       // reference-background -> target-background grade
uniform sampler2D uLutSubject;  // reference-subject    -> target-subject    grade
uniform sampler2D uMask;
varying vec2 vUV;

const float N = ${LUT_SIZE}.0;   // LUT cube size — must match ColorGrade.LUT_SIZE
const float W = N * N;           // atlas width  = 1089 (N blue-slices side by side)
const float H = N;               // atlas height = 33

// Look up the graded colour for an input RGB in a given LUT atlas, matching
// ColorGrade.bakeLutAtlas EXACTLY: atlasX = blue*N + red, atlasY = green (blue picks the
// horizontal slice). Same code serves both LUTs — only the sampler differs.
vec3 sampleLut(sampler2D lut, vec3 color) {
    color = clamp(color, 0.0, 1.0);

    float bf = color.b * (N - 1.0);          // blue position in [0, N-1]
    float slice0 = floor(bf);                // lower blue slice
    float slice1 = min(slice0 + 1.0, N - 1.0);
    float fracB = bf - slice0;               // blend weight between the two slices

    float rf = color.r * (N - 1.0);          // red   index within a slice, [0, N-1]
    float gf = color.g * (N - 1.0);          // green index (atlas row),     [0, N-1]

    // Texel k sits at pixel (k + 0.5). rf/gf span 0..N-1 across N texels, so the sample
    // coordinate stays between the first and last texel centre of the tile — GL_LINEAR then
    // interpolates red (x) and green (y) inside the tile WITHOUT bleeding into the neighbour
    // slice. The blue axis is done by hand: one tap per slice, mixed by fracB (trilinear).
    float v  = (gf + 0.5) / H;
    float u0 = (slice0 * N + rf + 0.5) / W;
    float u1 = (slice1 * N + rf + 0.5) / W;

    vec3 c0 = texture2D(lut, vec2(u0, v)).rgb;
    vec3 c1 = texture2D(lut, vec2(u1, v)).rgb;
    return mix(c0, c1, fracB);
}

void main() {
    vec3 original = texture2D(uPhoto, vUV).rgb;

    // Grade the same pixel two ways: once with the background LUT, once with the subject LUT.
    vec3 gradedBg = sampleLut(uLutBg, original);
    vec3 gradedSubj = sampleLut(uLutSubject, original);

    // Mask: 1.0 = subject, 0.0 = background. mix(gradedBg, gradedSubj, m) picks the background
    // grade where m=0 and the subject grade where m=1, crossfading smoothly through the soft
    // blurred mask edge in between — a soft region select, not a hard switch.
    float m = texture2D(uMask, vUV).r;
    vec3 result = mix(gradedBg, gradedSubj, m);

    gl_FragColor = vec4(result, 1.0);
}
"""

/**
 * Grades [photo] region-aware on the GPU: the [bgLut] where [mask] is background and the
 * [subjLut] where it is subject, crossfading across the soft mask edge. Runs in an offscreen
 * FBO at the photo's resolution and reads the result back into a Bitmap. One-shot; sets up and
 * tears down its own EGL context, so it must run off the main thread.
 */
fun applyLutGrade(photo: Bitmap, bgLut: Bitmap, subjLut: Bitmap, mask: Bitmap): Bitmap {
    val width = photo.width
    val height = photo.height

    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)

    val configs = arrayOfNulls<EGLConfig>(1)
    EGL14.eglChooseConfig(
        display,
        intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        ),
        0, configs, 0, 1, IntArray(1), 0,
    )
    val config = configs[0] ?: error("no suitable EGL config")

    val context = EGL14.eglCreateContext(
        display, config, EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
    )
    // A 1x1 pbuffer just to have a current surface; the real rendering target is the FBO.
    val surface = EGL14.eglCreatePbufferSurface(
        display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
    )
    EGL14.eglMakeCurrent(display, surface, surface, context)

    try {
        val program = buildProgram()
        require(program != 0) { "shader program failed to build — see Logcat ($TAG)" }
        GLES20.glUseProgram(program)

        // Upload the four inputs to units 0/1/2/3. LUTs use LINEAR so the tile interpolation
        // in the shader works; all clamp to edge.
        val photoTex = uploadTexture(photo, 0)
        val bgLutTex = uploadTexture(bgLut, 1)
        val subjLutTex = uploadTexture(subjLut, 2)
        val maskTex = uploadTexture(mask, 3)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPhoto"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLutBg"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLutSubject"), 2)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMask"), 3)

        // Offscreen colour target: a texture attached to an FBO, at the photo's resolution.
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        val colorTex = IntArray(1)
        GLES20.glGenTextures(1, colorTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTex[0], 0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "FBO incomplete"
        }

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Bind the quad geometry and draw the two triangles that cover the target.
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val uvHandle = GLES20.glGetAttribLocation(program, "aUV")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(QUAD_XY))
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(QUAD_UV))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Read the FBO back. glReadPixels gives R,G,B,A bytes, which is exactly the memory
        // layout Bitmap.copyPixelsFromBuffer wants for ARGB_8888 — no channel swizzle needed.
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(buffer)

        // Tidy GL objects (context teardown happens in finally).
        GLES20.glDeleteTextures(5, intArrayOf(photoTex, bgLutTex, subjLutTex, maskTex, colorTex[0]), 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteProgram(program)
        return out
    } finally {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/**
 * Saves [graded] to the device gallery under Pictures/Mimic via MediaStore, so it appears in the
 * user's photos. minSdk is 29, so scoped storage is always available — no storage permission is
 * needed (RELATIVE_PATH + IS_PENDING). The full-resolution bitmap is encoded straight into the
 * MediaStore stream. Returns true on success.
 */
fun saveGradedToGallery(context: Context, graded: Bitmap): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "mimic_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Mimic")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { graded.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        Log.e(TAG, "gallery save failed", e)
        resolver.delete(uri, null, null) // drop the empty pending entry
        false
    }
}

/**
 * Writes [graded] into the FileProvider-shared cache dir and returns a content:// URI that
 * ACTION_SEND can grant to other apps. Full-resolution PNG, so a share matches a gallery save.
 */
fun gradedShareUri(context: Context, graded: Bitmap): Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "mimic_graded.png")
    FileOutputStream(file).use { graded.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun uploadTexture(bitmap: Bitmap, unit: Int): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    // LINEAR so the LUT tile interpolation (and photo/mask sampling) is smooth; clamp to edge.
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    return ids[0]
}

private fun floatBuffer(data: FloatArray) =
    ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(data); position(0) }

// --- Shader compile/link with the shadersandbox logging pattern (glGet*InfoLog to Logcat) ---

private fun buildProgram(): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER, "VERTEX")
    if (vs == 0) return 0
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER, "FRAGMENT")
    if (fs == 0) {
        GLES20.glDeleteShader(vs)
        return 0
    }
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)

    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        Log.e(TAG, "PROGRAM LINK FAILED\n${GLES20.glGetProgramInfoLog(program)}")
        GLES20.glDeleteProgram(program)
        return 0
    }
    return program
}

private fun compileShader(type: Int, source: String, label: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        Log.e(TAG, "$label SHADER COMPILE FAILED\n${GLES20.glGetShaderInfoLog(shader)}")
        GLES20.glDeleteShader(shader)
        return 0
    }
    return shader
}
