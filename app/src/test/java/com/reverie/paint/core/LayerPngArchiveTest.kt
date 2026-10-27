/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.core.export.LayerPngArchive
import com.reverie.paint.model.LayerExportPlan
import com.reverie.paint.model.LayerExportSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayerPngArchiveTest {
    private val sources = listOf(
        LayerExportSource(0, 10, "group", 0, 1, true, 0.7, "normal"),
        LayerExportSource(1, 11, "arm", 1, 0, true, 0.5, "multiply", clipped = true),
        LayerExportSource(2, 12, "filter", 1, 3, true, 1.0, "normal"),
    )

    @Test fun `archive preserves current frame dimensions order and unsupported layer records`() {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Int, Int>>()
        LayerPngArchive.write(out, LayerExportPlan.build(sources, true), 1920, 1080, 15, { false },
            { done, total -> progress.add(done to total) }) { entry, stream ->
            // Test the real ZIP writer; native PNG rendering is explicitly a separate device check.
            stream.write("pixels-${entry.source.index}".toByteArray())
        }
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { files[entry.name] = zip.readBytes(); entry = zip.nextEntry }
        }
        assertEquals(listOf("0002_arm.png", "layers.json"), files.keys.toList())
        assertEquals("pixels-1", files.getValue("0002_arm.png").toString(Charsets.UTF_8))
        val manifest = JSONObject(files.getValue("layers.json").toString(Charsets.UTF_8))
        assertEquals(1920, manifest.getInt("width"))
        assertEquals(1080, manifest.getInt("height"))
        assertEquals(15, manifest.getInt("frame"))
        assertFalse(manifest.getBoolean("opacityApplied"))
        val layers = manifest.getJSONArray("layers")
        assertEquals(3, layers.length())
        assertEquals(0, layers.getJSONObject(1).getInt("parentIndex"))
        assertEquals(0.5, layers.getJSONObject(1).getDouble("opacity"), 0.0)
        assertTrue(layers.getJSONObject(1).getBoolean("clipped"))
        assertTrue(layers.getJSONObject(2).isNull("file"))
        assertEquals("unsupported_node_type", layers.getJSONObject(2).getString("omission"))
        assertEquals(listOf(1 to 1), progress)
    }

    @Test fun `cancel stops before the next render`() {
        var renders = 0
        try {
            LayerPngArchive.write(ByteArrayOutputStream(), LayerExportPlan.build(sources, true), 1, 1, 0,
                { true }, { _, _ -> }) { _, _ -> renders++ }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(0, renders) }
    }

    @Test fun `encoder failure is not reported as a successful archive`() {
        try {
            LayerPngArchive.write(ByteArrayOutputStream(), LayerExportPlan.build(sources, true), 1, 1, 0,
                { false }, { _, _ -> }) { _, _ -> throw IOException("encode failed") }
            fail("Expected failure")
        } catch (error: IOException) { assertEquals("encode failed", error.message) }
    }
}
