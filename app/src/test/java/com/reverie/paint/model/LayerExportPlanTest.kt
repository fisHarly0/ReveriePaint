/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class LayerExportPlanTest {
    private fun layer(index: Int, name: String = "part", depth: Int = 0, type: Int = 0, visible: Boolean = true) =
        LayerExportSource(index, index.toLong() + 100, name, depth, type, visible, 0.5, "normal")

    @Test fun `hidden group also hides descendants but not its next sibling`() {
        val plan = LayerExportPlan.build(listOf(
            layer(0, type = 1, visible = false), layer(1, depth = 1),
            layer(2, depth = 1, type = 1), layer(3, depth = 2), layer(4),
        ), false)
        assertEquals(listOf("group_container", "hidden", "group_container", "hidden", null), plan.map { it.omission })
        assertEquals(listOf(null, 0, 0, 2, null), plan.map { it.parentIndex })
        assertEquals(listOf(4), plan.filter { it.fileName != null }.map { it.source.index })
    }

    @Test fun `include hidden preserves paint pixels and all omission records`() {
        val sources = listOf(layer(0, type = 1, visible = false), layer(1, depth = 1),
            layer(2, type = 3), layer(3, type = 10), layer(4).copy(live3D = true), layer(5, type = 2))
        val result = LayerExportPlan.build(sources, true)
        assertNotNull(result[1].fileName)
        assertFalse(result[1].effectiveVisible)
        assertEquals("unsupported_node_type", result[2].omission)
        assertEquals("unsupported_node_type", result[3].omission)
        assertEquals("live_3d", result[4].omission)
        assertEquals("unsupported_node_type", result[5].omission)
        assertEquals(sources, result.map { it.source })
    }

    @Test fun `names cannot escape the archive and duplicate names stay unique`() {
        val result = LayerExportPlan.build(listOf(layer(0, "../a\\b:\n"), layer(1, ".."),
            layer(2, "same"), layer(3, "same"), layer(4, "x".repeat(63) + "😀")), true)
        val names = result.map { requireNotNull(it.fileName) }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { '/' !in it && '\\' !in it && '\n' !in it && ':' !in it })
        assertEquals("0002_layer.png", names[1])
        assertFalse(names.last().any { it.isSurrogate() })
    }

    @Test fun `dimensions reject overflow and excessive memory without scaling`() {
        assertTrue(LayerExportPlan.validDimensions(4096, 4096))
        assertFalse(LayerExportPlan.validDimensions(4097, 4096))
        assertFalse(LayerExportPlan.validDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(LayerExportPlan.validDimensions(0, 100))
        assertFalse(LayerExportPlan.validDimensions(-1, 100))
        assertTrue(LayerExportPlan.build(emptyList(), true).isEmpty())
    }
}
