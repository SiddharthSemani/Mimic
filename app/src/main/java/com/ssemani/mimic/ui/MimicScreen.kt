package com.ssemani.mimic.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ssemani.mimic.MAX_IMAGE_DIM
import com.ssemani.mimic.applyLutGrade
import com.ssemani.mimic.bakeLutAtlas
import com.ssemani.mimic.buildSoftSkinMask
import com.ssemani.mimic.createSkinSegmenter
import com.ssemani.mimic.decodeUprightBitmap
import com.ssemani.mimic.gradedShareUri
import com.ssemani.mimic.saveGradedToGallery
import com.ssemani.mimic.saveLutPng
import com.ssemani.mimic.saveMaskPng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Mimic"

/**
 * When true, every Grade dumps its intermediate artifacts (decoded inputs, both masks, both LUT
 * atlases) as PNGs into the app's external files dir for verification. Diagnostic only — never
 * shown in the UI. Ships OFF; flip to true when debugging the grade pipeline.
 */
private const val DEBUG_ARTIFACTS = false

@Composable
fun MimicScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // The two picked images. rememberSaveable persists them across recomposition AND
    // configuration changes / process death — Uri is Parcelable, so it needs no custom Saver.
    var referenceUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var targetUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // The full-resolution graded output. Shown over the target slot once Grade runs, and the
    // source for Save/Share (full bitmap, not a downscaled preview). Plain remember — Grade
    // re-produces it, and picking either image clears it.
    var graded by remember { mutableStateOf<Bitmap?>(null) }
    var isGrading by remember { mutableStateOf(false) }

    // Segmenter is created once and closed when this screen leaves composition, so it never leaks.
    val segmenter = remember { createSkinSegmenter(context) }
    DisposableEffect(Unit) { onDispose { segmenter.close() } }

    // One Photo Picker launcher per slot. PickVisualMedia grants read access to just the
    // chosen item, so no runtime storage permission is involved.
    // Picking either image invalidates any existing graded result, so the target slot reverts to
    // the newly picked photo, Save/Share disappear, and the in-memory bitmap is dropped.
    val pickReference = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            referenceUri = uri
            graded = null
        }
    }
    val pickTarget = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            targetUri = uri
            graded = null
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val imagesOnly = PickVisualMediaRequest(PickVisualMedia.ImageOnly)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Mimic", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pick a reference and a target, then grade the target to match.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SlotLayout(
                modifier = Modifier.weight(1f),
                reference = {
                    // Always the picked reference photo — never an intermediate artifact.
                    ImageSlot(
                        label = "Reference",
                        uri = referenceUri,
                        onClick = { pickReference.launch(imagesOnly) },
                        modifier = it,
                    )
                },
                target = {
                    // The graded result replaces the picked target once Grade completes.
                    ImageSlot(
                        label = "Target",
                        uri = targetUri,
                        onClick = { pickTarget.launch(imagesOnly) },
                        modifier = it,
                        previewBitmap = graded,
                        previewCaption = "Graded",
                    )
                },
            )

            val bothPicked = referenceUri != null && targetUri != null
            Button(
                onClick = {
                    val refUri = referenceUri
                    val tgtUri = targetUri
                    if (refUri != null && tgtUri != null) {
                        isGrading = true
                        scope.launch {
                            try {
                                // Real work on full-size bitmaps (segmentation, LUT bake, GL pass)
                                // — keep it off the main thread so the UI never freezes.
                                graded = withContext(Dispatchers.Default) {
                                    val targetBmp = decodeUprightBitmap(context, tgtUri, MAX_IMAGE_DIM)
                                    val refBmp = decodeUprightBitmap(context, refUri, MAX_IMAGE_DIM)
                                    // Segment BOTH images through the one segmentation path, so we
                                    // know subject vs background for each.
                                    val targetMask = buildSoftSkinMask(segmenter, targetBmp)
                                    val refMask = buildSoftSkinMask(segmenter, refBmp)
                                    // Two region grades from the one bake function: background
                                    // (ref-bg -> tgt-bg) and subject (ref-subject -> tgt-subject).
                                    val bgLut = bakeLutAtlas(refBmp, refMask, targetBmp, targetMask, subject = false)
                                    val subjLut = bakeLutAtlas(refBmp, refMask, targetBmp, targetMask, subject = true)
                                    if (DEBUG_ARTIFACTS) {
                                        saveMaskPng(context, refBmp, "input_reference")
                                        saveMaskPng(context, targetBmp, "input_target")
                                        saveMaskPng(context, targetMask)
                                        saveMaskPng(context, refMask, "reference_mask")
                                        saveLutPng(context, bgLut, "lut_bg")
                                        saveLutPng(context, subjLut, "lut_subject")
                                    }
                                    // Apply: background LUT where mask=background, subject LUT
                                    // where mask=subject, crossfading across the soft edge.
                                    applyLutGrade(targetBmp, bgLut, subjLut, targetMask)
                                }
                                snackbarHostState.showSnackbar("Graded")
                            } catch (e: Exception) {
                                Log.e(TAG, "grade failed", e)
                                snackbarHostState.showSnackbar("Grade failed: ${e.message}")
                            } finally {
                                isGrading = false
                            }
                        }
                    }
                },
                enabled = bothPicked && !isGrading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isGrading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Grade")
                }
            }

            // Save + Share appear only once a graded result exists. Both act on the full-resolution
            // bitmap and encode off the main thread.
            val gradedBmp = graded
            if (gradedBmp != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    saveGradedToGallery(context, gradedBmp)
                                }
                                snackbarHostState.showSnackbar(
                                    if (ok) "Saved to Pictures/Mimic" else "Save failed",
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) {
                                        gradedShareUri(context, gradedBmp)
                                    }
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share graded image"),
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "share failed", e)
                                    snackbarHostState.showSnackbar("Share failed: ${e.message}")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Share") }
                }
            }
        }
    }
}

/**
 * Places the two slots side by side on wide screens (landscape / tablet) and stacked on
 * narrow ones. Each slot is handed a modifier carrying its share of the available space.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun SlotLayout(
    reference: @Composable (Modifier) -> Unit,
    target: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= 500.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                reference(Modifier.weight(1f).fillMaxHeight())
                target(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                reference(Modifier.weight(1f).fillMaxWidth())
                target(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ImageSlot(
    label: String,
    uri: Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    previewBitmap: Bitmap? = null,
    previewCaption: String? = null,
) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        when {
            // The graded result wins over the picked photo. Crop to fill the slot like the picked
            // image, with a small caption badge to mark it as graded.
            previewBitmap != null -> Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = previewCaption ?: label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (previewCaption != null) {
                    Text(
                        previewCaption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            uri != null -> AsyncImage(
                model = uri,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap to pick a photo",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
