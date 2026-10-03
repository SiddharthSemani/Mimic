package com.ssemani.mimic.ui

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
import com.ssemani.mimic.buildSoftSkinMask
import com.ssemani.mimic.createSkinSegmenter
import com.ssemani.mimic.decodeUprightBitmap
import com.ssemani.mimic.saveMaskPng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Mimic"

@Composable
fun MimicScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // The two picked images. rememberSaveable persists them across recomposition AND
    // configuration changes / process death — Uri is Parcelable, so it needs no custom Saver.
    var referenceUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var targetUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // The soft skin mask, shown in the target slot once produced. Transient verification aid,
    // so plain remember is fine — tapping Grade re-produces it.
    var maskPreview by remember { mutableStateOf<Bitmap?>(null) }
    var isGrading by remember { mutableStateOf(false) }

    // Segmenter is created once and closed when this screen leaves composition, so it never leaks.
    val segmenter = remember { createSkinSegmenter(context) }
    DisposableEffect(Unit) { onDispose { segmenter.close() } }

    // One Photo Picker launcher per slot. PickVisualMedia grants read access to just the
    // chosen item, so no runtime storage permission is involved.
    val pickReference = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) referenceUri = uri
    }
    val pickTarget = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            targetUri = uri
            maskPreview = null // a new target invalidates any previous mask
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
                    ImageSlot(
                        label = "Reference",
                        uri = referenceUri,
                        onClick = { pickReference.launch(imagesOnly) },
                        modifier = it,
                    )
                },
                target = {
                    ImageSlot(
                        label = "Target",
                        uri = targetUri,
                        onClick = { pickTarget.launch(imagesOnly) },
                        modifier = it,
                        // Once produced, the mask takes over the target slot for verification.
                        previewBitmap = maskPreview,
                        previewCaption = "skin mask",
                    )
                },
            )

            val bothPicked = referenceUri != null && targetUri != null
            Button(
                onClick = {
                    val uri = targetUri
                    if (uri != null) {
                        isGrading = true
                        scope.launch {
                            try {
                                // Segmentation + blur on a full-size bitmap is real work — keep it
                                // off the main thread so the UI never freezes.
                                val (file, mask) = withContext(Dispatchers.Default) {
                                    val bitmap = decodeUprightBitmap(context, uri, MAX_IMAGE_DIM)
                                    val mask = buildSoftSkinMask(segmenter, bitmap)
                                    saveMaskPng(context, mask) to mask
                                }
                                maskPreview = mask
                                Log.d(TAG, "skin mask saved: ${file.absolutePath}")
                                snackbarHostState.showSnackbar("Mask saved: ${file.name}")
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
        }
    }
}

/**
 * Places the two slots side by side on wide screens (landscape / tablet) and stacked on
 * narrow ones. Each slot is handed a modifier carrying its share of the available space.
 */
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
            // A preview bitmap (the mask) wins over the picked photo. Fit, not Crop, so the
            // whole mask is visible for verification, with a small caption to label it.
            previewBitmap != null -> Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = previewCaption ?: label,
                    contentScale = ContentScale.Fit,
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
