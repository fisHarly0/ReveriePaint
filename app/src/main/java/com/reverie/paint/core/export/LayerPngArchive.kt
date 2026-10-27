/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core.export

import com.reverie.paint.model.LayerExportEntry
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Streaming archive: a caller-owned render buffer is reused, never one bitmap per layer. */
internal object LayerPngArchive {
    fun write(
        output: OutputStream,
        entries: List<LayerExportEntry>,
        width: Int,
        height: Int,
        frame: Int,
        cancelled: () -> Boolean,
        progress: (Int, Int) -> Unit,
        writePng: (LayerExportEntry, OutputStream) -> Unit,
    ) {
        val exported = entries.filter { it.fileName != null }
        require(exported.isNotEmpty())
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            exported.forEachIndexed { index, entry ->
                if (cancelled()) throw CancellationException()
                zip.putNextEntry(ZipEntry(requireNotNull(entry.fileName)))
                writePng(entry, zip)
                zip.closeEntry()
                progress(index + 1, exported.size)
            }
            if (cancelled()) throw CancellationException()
            val manifest = JSONObject()
                .put("version", 1).put("width", width).put("height", height).put("frame", frame)
                .put("pixelMode", "raw_paint_pixels")
                .put("order", "bottom_to_top_siblings_parent_before_children")
                .put("opacityApplied", false)
                .put("compositingApplied", false)
                .put("layers", JSONArray().apply {
                    entries.forEach { entry ->
                        val source = entry.source
                        put(JSONObject().put("index", source.index).put("id", source.id.toString())
                            .put("name", source.name).put("depth", source.depth).put("nodeType", source.nodeType)
                            .put("parentIndex", entry.parentIndex ?: JSONObject.NULL)
                            .put("visible", source.visible).put("effectiveVisible", entry.effectiveVisible)
                            .put("opacity", source.opacity).put("blendMode", source.blendMode)
                            .put("clipped", source.clipped).put("alphaInherited", source.alphaInherited)
                            .put("file", entry.fileName ?: JSONObject.NULL)
                            .put("omission", entry.omission ?: JSONObject.NULL))
                    }
                })
            zip.putNextEntry(ZipEntry("layers.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
