/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import com.reverie.paint.R
import com.reverie.paint.core.export.LayerPngArchive
import com.reverie.paint.model.LayerExportPlan
import com.reverie.paint.model.LayerExportSource
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class LayerExportState {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var saving by mutableStateOf(false)
    var cancelling by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    internal var cancellation = AtomicBoolean(false)
    fun cancel() { cancelling = true; cancellation.set(true) }
}

/** One render-queue operation keeps all layers at the same document/frame without toggling visibility. */
internal fun PaintViewModel.exportLayerPngs(destination: Uri, includeHidden: Boolean) {
    val state = layerExportState
    if (state.running) return
    animationPause()
    val cancelled = AtomicBoolean(false)
    state.cancellation = cancelled
    state.running = true
    state.done = 0
    state.total = 0
    state.saving = false
    state.cancelling = false
    state.message = null
    val live3DIds = active3DLayers.keys.toSet()
    val unfinishedStroke = isStrokeSessionActive || isQuickShapeEditing || quickShapeCommitting
    var archive: File? = null
    var problem: String? = getString(R.string.layer_export_failed)
    var exportedCount = 0
    var omittedCount = 0
    runCore(render = false, after = {
        state.saving = problem == null && !cancelled.get()
        // A bounded I/O worker outlives composition, so backing out/rotation cannot strand the staged ZIP.
        // All JNI work has already finished on the render Handler; this worker only copies bytes.
        thread(name = "layer-export-save") {
            var success = false
            try {
                if (cancelled.get()) throw CancellationException()
                if (problem != null) throw IOException(problem)
                val file = archive ?: throw IOException(getString(R.string.layer_export_failed))
                appContext.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) throw CancellationException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                } ?: throw IOException(getString(R.string.layer_export_failed))
                success = true
            } catch (_: CancellationException) {
                problem = getString(R.string.layer_export_cancelled)
            } catch (e: Exception) {
                android.util.Log.w("LayerExport", "Layer archive save failed", e)
                if (problem == null) problem = getString(R.string.layer_export_failed)
            } finally {
                archive?.delete()
                if (!success) {
                    // CreateDocument created this destination. Some providers cannot delete it; report that explicitly.
                    val removed = runCatching {
                        DocumentsContract.deleteDocument(appContext.contentResolver, destination)
                    }.getOrDefault(false)
                    if (!removed) problem = "${problem.orEmpty()}\n${getString(R.string.layer_export_cleanup_hint)}"
                }
                val message = if (success) {
                    getString(R.string.layer_export_complete, exportedCount, omittedCount)
                } else problem ?: getString(R.string.layer_export_failed)
                mainHandler.post {
                    state.running = false
                    state.saving = false
                    state.message = message
                }
            }
        }
    }) {
        var bitmap: Bitmap? = null
        try {
            if (cancelled.get()) throw CancellationException()
            if (unfinishedStroke || engineMirrorSeq != 0) {
                problem = getString(R.string.layer_export_finish_stroke)
                return@runCore
            }
            val width = ReverieCoreBridge.docWidth()
            val height = ReverieCoreBridge.docHeight()
            if (!LayerExportPlan.validDimensions(width, height)) {
                problem = getString(R.string.layer_export_too_large)
                return@runCore
            }
            val entries = LayerExportPlan.build((0 until ReverieCoreBridge.layerCount()).map { index ->
                val id = ReverieCoreBridge.layerId(index)
                LayerExportSource(
                    index, id, ReverieCoreBridge.layerName(index), ReverieCoreBridge.layerDepth(index),
                    ReverieCoreBridge.layerNodeType(index), ReverieCoreBridge.layerVisible(index),
                    ReverieCoreBridge.layerOpacity(index), ReverieCoreBridge.layerBlendMode(index),
                    ReverieCoreBridge.layerClipped(index), ReverieCoreBridge.layerAlphaInherited(index),
                    id in live3DIds,
                )
            }, includeHidden)
            exportedCount = entries.count { it.fileName != null }
            omittedCount = entries.count { it.omission != null && it.omission != "group_container" }
            if (exportedCount == 0) {
                problem = getString(R.string.layer_export_empty)
                return@runCore
            }
            mainHandler.post { state.total = exportedCount }
            val frame = ReverieCoreBridge.animationCurrentTime()
            val buffer = createBitmap(width, height)
            bitmap = buffer
            val file = File.createTempFile("layers-", ".zip", appContext.cacheDir)
            archive = file
            LayerPngArchive.write(file.outputStream().buffered(), entries, width, height, frame,
                cancelled = cancelled::get,
                progress = { done, total -> mainHandler.post { state.done = done; state.total = total } },
            ) { entry, output ->
                if (!ReverieCoreBridge.renderLayerCanvas(entry.source.index, buffer)) {
                    throw IOException("Cannot render layer ${entry.source.index}")
                }
                if (!buffer.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Cannot encode layer ${entry.source.index}")
                }
            }
            problem = null
        } catch (_: CancellationException) {
            problem = getString(R.string.layer_export_cancelled)
        } catch (_: OutOfMemoryError) {
            problem = getString(R.string.layer_export_memory)
        } catch (e: Exception) {
            android.util.Log.w("LayerExport", "Layer archive render failed", e)
            problem = getString(R.string.layer_export_failed)
        } finally {
            bitmap?.recycle()
        }
    }
}
