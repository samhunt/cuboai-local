package com.cuboai.local.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LicenseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val text = remember(context) { loadBundledLicenses(context) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("License", style = MaterialTheme.typography.headlineMedium)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private fun loadBundledLicenses(context: Context): String {
    fun assetOrEmpty(name: String): String =
        runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .trim()
    val license = assetOrEmpty("LICENSE")
    val notice = assetOrEmpty("NOTICE")
    return listOf(license, notice).filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
        .ifBlank { "License files were not bundled in this build." }
}
