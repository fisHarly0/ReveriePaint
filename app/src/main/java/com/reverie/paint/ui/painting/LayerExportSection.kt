/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.exportLayerPngs
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** An optional action inside the existing export page, not another export mode for animation frames. */
@Composable
internal fun LayerExportSection(vm: PaintViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = vm.layerExportState
    var includeHidden by rememberSaveable { mutableStateOf(true) }
    var requestedDocument by rememberSaveable { mutableStateOf("") }
    val documentStamp = "${vm.canvasCreatedTime}:${vm.currentProjectFile}:${vm.docName}"
    val documentChangedMessage = stringResource(R.string.layer_export_document_changed)
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            if (requestedDocument == documentStamp) {
                vm.exportLayerPngs(uri, includeHidden)
            } else {
                state.message = documentChangedMessage
                scope.launch(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.layer_export_title), color = Morandi.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.layer_export_description), color = Morandi.subText, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeHidden, onCheckedChange = { includeHidden = it }, enabled = !state.running)
            Text(stringResource(R.string.layer_export_include_hidden), color = Morandi.text, fontSize = 13.sp)
        }
        Text(stringResource(R.string.layer_export_limitations), color = Morandi.subText, fontSize = 12.sp)
        Button(
            onClick = {
                requestedDocument = documentStamp
                val name = vm.docName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(64)
                    .trimEnd { it.isHighSurrogate() }
                createFile.launch("${name.ifBlank { "ReveriePaint" }}_layers.zip")
            },
            enabled = enabled && !state.running,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Morandi.accent),
        ) { Text(stringResource(R.string.layer_export_action)) }
    }
    if (state.running) {
        AlertDialog(
            onDismissRequest = { state.cancel() },
            properties = DialogProperties(dismissOnClickOutside = false),
            containerColor = Morandi.panel,
            title = { Text(stringResource(R.string.layer_export_title), color = Morandi.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.cancelling) stringResource(R.string.layer_export_cancelling)
                        else if (state.total == 0) stringResource(R.string.layer_export_preparing)
                        else if (state.saving) stringResource(R.string.layer_export_saving)
                        else stringResource(R.string.layer_export_progress, state.done, state.total),
                        color = Morandi.text,
                    )
                    if (state.total > 0 && !state.saving) {
                        LinearProgressIndicator(
                            progress = { state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(), color = Morandi.accent,
                        )
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Morandi.accent)
                }
            },
            confirmButton = {
                TextButton(onClick = { state.cancel() }, enabled = !state.cancelling) {
                    Text(stringResource(R.string.layer_export_cancel))
                }
            },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = { state.message = null }, containerColor = Morandi.panel,
            title = { Text(stringResource(R.string.layer_export_title), color = Morandi.text) },
            text = { Text(message, color = Morandi.text) },
            confirmButton = {
                TextButton(onClick = { state.message = null }) { Text(stringResource(R.string.layer_export_done)) }
            },
        )
    }
}
