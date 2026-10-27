/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** Native tree order: bottom-to-top siblings, parent before children. No pixels live in this model. */
data class LayerExportSource(
    val index: Int,
    val id: Long,
    val name: String,
    val depth: Int,
    val nodeType: Int,
    val visible: Boolean,
    val opacity: Double,
    val blendMode: String,
    val clipped: Boolean = false,
    val alphaInherited: Boolean = false,
    val live3D: Boolean = false,
)

data class LayerExportEntry(
    val source: LayerExportSource,
    val parentIndex: Int?,
    val effectiveVisible: Boolean,
    val fileName: String?,
    val omission: String?,
)

object LayerExportPlan {
    // The native renderer also creates a full-canvas QImage. Never silently downsample animation assets.
    const val MAX_PIXELS = 16_777_216L

    fun validDimensions(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS

    fun build(sources: List<LayerExportSource>, includeHidden: Boolean): List<LayerExportEntry> {
        val ancestors = mutableListOf<LayerExportEntry>()
        return sources.map { source ->
            while (ancestors.isNotEmpty() && ancestors.last().source.depth >= source.depth) {
                ancestors.removeAt(ancestors.lastIndex)
            }
            val parent = ancestors.lastOrNull()
            val visible = source.visible && (parent?.effectiveVisible ?: true)
            val omission = when {
                source.nodeType == 1 -> "group_container"
                !includeHidden && !visible -> "hidden"
                source.live3D -> "live_3d"
                source.nodeType != 0 -> "unsupported_node_type"
                else -> null
            }
            val entry = LayerExportEntry(
                source, parent?.source?.index, visible,
                if (omission == null) fileName(source.index, source.name) else null, omission,
            )
            ancestors.add(entry)
            entry
        }
    }

    private fun fileName(index: Int, name: String): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().trim('.').ifBlank { "layer" }.take(64).let {
                if (it.last().isHighSurrogate()) it.dropLast(1) else it
            }
        return "${(index + 1).toString().padStart(4, '0')}_${safeName}.png"
    }
}
