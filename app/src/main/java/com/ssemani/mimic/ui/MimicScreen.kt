package com.ssemani.mimic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun MimicScreen(modifier: Modifier = Modifier) {
    // The two picked images. rememberSaveable persists them across recomposition AND
    // configuration changes / process death — Uri is Parcelable, so it needs no custom Saver.
    var referenceUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var targetUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // One Photo Picker launcher per slot. PickVisualMedia grants read access to just the
    // chosen item, so no runtime storage permission is involved.
    val pickReference = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) referenceUri = uri
    }
    val pickTarget = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) targetUri = uri
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
                    )
                },
            )

            val bothPicked = referenceUri != null && targetUri != null
            Button(
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar("Grading not wired yet") }
                },
                enabled = bothPicked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grade")
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
) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
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
