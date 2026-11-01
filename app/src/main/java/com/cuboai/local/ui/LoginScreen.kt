package com.cuboai.local.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cuboai.local.data.CameraCredentials
import com.cuboai.local.data.CloudCamera
import com.cuboai.local.data.LoginMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    state: UiState,
    onFormChange: ((CameraCredentials) -> CameraCredentials) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onLoginMode: (LoginMode) -> Unit,
    onCuboEmail: (String) -> Unit,
    onCuboPassword: (String) -> Unit,
    onMfaCode: (String) -> Unit,
    onFetchCameras: () -> Unit,
    onSubmitMfa: () -> Unit,
    onSelectCamera: (CloudCamera) -> Unit,
    onConnect: () -> Unit,
    onShowLicense: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("CuboAI Local", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Get camera credentials once, then connect over your LAN. Only UID / account / password / IP are stored on this phone — Cubo account passwords and tokens are not.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        val modes = listOf(LoginMode.CuboAccount to "Cubo account", LoginMode.Manual to "Manual")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.loginMode == mode,
                    onClick = { onLoginMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    enabled = !state.busy,
                ) {
                    Text(label)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.loginMode) {
                    LoginMode.CuboAccount -> CuboAccountFields(
                        state = state,
                        onCuboEmail = onCuboEmail,
                        onCuboPassword = onCuboPassword,
                        onMfaCode = onMfaCode,
                        onFetchCameras = onFetchCameras,
                        onSubmitMfa = onSubmitMfa,
                        onSelectCamera = onSelectCamera,
                        onFormChange = onFormChange,
                    )
                    LoginMode.Manual -> ManualFields(form = form, onFormChange = onFormChange)
                }
                Field("Camera LAN IP", form.ip, KeyboardType.Uri) { value ->
                    onFormChange { it.copy(ip = value) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.remember, onCheckedChange = onRememberChange)
                    Text("Remember camera credentials on this device")
                }
            }
        }
        if (state.error != null) {
            Text(state.error, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onConnect,
            enabled = !state.busy && form.ip.isNotBlank() && form.uid.isNotBlank() &&
                form.account.isNotBlank() && form.password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Connect on LAN")
            }
        }
        Text(
            "Connect talks only to the camera on your Wi-Fi. Cubo servers are used solely for the optional one-time credential fetch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        TextButton(onClick = onShowLicense) {
            Text("MIT License")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CuboAccountFields(
    state: UiState,
    onCuboEmail: (String) -> Unit,
    onCuboPassword: (String) -> Unit,
    onMfaCode: (String) -> Unit,
    onFetchCameras: () -> Unit,
    onSubmitMfa: () -> Unit,
    onSelectCamera: (CloudCamera) -> Unit,
    onFormChange: ((CameraCredentials) -> CameraCredentials) -> Unit,
) {
    Text(
        "One-time login to Cubo’s servers. Your Cubo password is not saved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Field("Cubo email", state.cuboEmail, KeyboardType.Email) { onCuboEmail(it) }
    Field("Cubo password", state.cuboPassword, KeyboardType.Password, password = true) {
        onCuboPassword(it)
    }
    OutlinedButton(
        onClick = onFetchCameras,
        enabled = !state.busy && state.cuboEmail.isNotBlank() && state.cuboPassword.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.busy && !state.mfaRequired) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
        } else {
            Text("Fetch cameras")
        }
    }
    if (state.mfaRequired) {
        Field(
            label = "Code from ${state.mfaHint.ifBlank { "SMS or authenticator" }}",
            value = state.mfaCode,
            keyboard = KeyboardType.NumberPassword,
        ) { onMfaCode(it) }
        Button(
            onClick = onSubmitMfa,
            enabled = !state.busy && state.mfaCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Verify code")
            }
        }
    }
    if (state.cameras.isNotEmpty()) {
        CameraPicker(state = state, onSelectCamera = onSelectCamera)
        Field("Name", state.form.name) { value -> onFormChange { it.copy(name = value) } }
        Field("Device UID", state.form.uid) { value -> onFormChange { it.copy(uid = value) } }
        Field("Admin account", state.form.account) { value ->
            onFormChange { it.copy(account = value) }
        }
        Field("Admin password", state.form.password, KeyboardType.Password, password = true) { value ->
            onFormChange { it.copy(password = value) }
        }
    }
}

@Composable
private fun ManualFields(
    form: CameraCredentials,
    onFormChange: ((CameraCredentials) -> CameraCredentials) -> Unit,
) {
    Text(
        "Paste credentials you already have from pairing or a packet capture.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Field("Name", form.name) { value -> onFormChange { it.copy(name = value) } }
    Field("Device UID", form.uid) { value -> onFormChange { it.copy(uid = value) } }
    Field("Admin account", form.account) { value -> onFormChange { it.copy(account = value) } }
    Field("Admin password", form.password, KeyboardType.Password, password = true) { value ->
        onFormChange { it.copy(password = value) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraPicker(
    state: UiState,
    onSelectCamera: (CloudCamera) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.cameras.find { it.deviceId == state.selectedCameraId } ?: state.cameras.first()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Camera") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.cameras.forEach { camera ->
                DropdownMenuItem(
                    text = { Text("${camera.name} (${camera.uid})") },
                    onClick = {
                        onSelectCamera(camera)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
