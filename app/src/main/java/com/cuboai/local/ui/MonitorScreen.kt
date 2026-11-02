package com.cuboai.local.ui

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cuboai.local.data.CameraCredentials
import com.cuboai.local.data.LullabySong

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    state: UiState,
    onNightLight: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    onSleepMode: (Boolean) -> Unit,
    onPlaySong: (String) -> Unit,
    onStopSong: () -> Unit,
    onDisconnect: () -> Unit,
    onSwitchCamera: (CameraCredentials) -> Unit,
    onShowLicense: () -> Unit,
    onBindVideoView: (TextureView) -> Unit,
    onUnbindVideoView: (TextureView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state.status
    var songMenu by remember { mutableStateOf(false) }
    var selectedSong by remember(state.songs) {
        mutableStateOf(state.songs.firstOrNull())
    }

    var brightness by remember(status.brightness) {
        mutableStateOf((status.brightness ?: 0).coerceIn(0, 100).toFloat())
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.cameras.size > 1) {
            CameraSwitcher(
                cameras = state.cameras,
                selectedUid = state.form.uid,
                enabled = !state.busy,
                onSelect = onSwitchCamera,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            key(state.form.uid) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            onBindVideoView(this)
                        }
                    },
                    update = { onBindVideoView(it) },
                    onRelease = { onUnbindVideoView(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.busy || !state.streamReady) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                    Text("Connecting…", color = Color.White)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.cameras.size <= 1) {
                Text(state.form.label, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "LAN only · ${state.form.ip}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.temperatureC?.let { AssistChip(onClick = {}, label = { Text("%.1f°C".format(it)) }) }
                status.humidityPct?.let { AssistChip(onClick = {}, label = { Text("%.0f%% RH".format(it)) }) }
                status.wifiSsid?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                status.firmware?.let { AssistChip(onClick = {}, label = { Text("fw $it") }) }
            }
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Night light", modifier = Modifier.weight(1f))
                Switch(
                    checked = status.nightLightOn == true,
                    onCheckedChange = onNightLight,
                )
            }
            Text("Brightness")
            Slider(
                value = brightness,
                onValueChange = { brightness = it },
                onValueChangeFinished = { onBrightness(brightness.toInt()) },
                valueRange = 0f..100f,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Sleep / privacy mode")
                    Text(
                        "Suspends the camera video",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = status.sleepMode == true,
                    onCheckedChange = onSleepMode,
                )
            }

            Text("Lullaby", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(expanded = songMenu, onExpandedChange = { songMenu = it }) {
                OutlinedTextField(
                    value = selectedSong?.name ?: "Choose a song",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = songMenu) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = songMenu, onDismissRequest = { songMenu = false }) {
                    state.songs.forEach { song ->
                        DropdownMenuItem(
                            text = { Text("${song.name} (${song.category})") },
                            onClick = {
                                selectedSong = song
                                songMenu = false
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { selectedSong?.let { onPlaySong(it.uuid) } },
                    enabled = selectedSong != null,
                ) { Text(if (status.lullabyPlaying == true) "Playing" else "Play") }
                OutlinedButton(onClick = onStopSong) { Text("Stop") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onShowLicense) { Text("License") }
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraSwitcher(
    cameras: List<CameraCredentials>,
    selectedUid: String,
    enabled: Boolean,
    onSelect: (CameraCredentials) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = cameras.find { it.uid == selectedUid } ?: cameras.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Camera") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            cameras.forEach { camera ->
                DropdownMenuItem(
                    text = { Text("${camera.label} · ${camera.ip.ifBlank { camera.uid }}") },
                    onClick = {
                        onSelect(camera)
                        expanded = false
                    },
                )
            }
        }
    }
}
