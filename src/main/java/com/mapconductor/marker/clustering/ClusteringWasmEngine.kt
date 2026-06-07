package com.mapconductor.marker.clustering

import android.content.Context
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Accelerates the grid-assignment and greedy-merge steps of clustering by running
 * a compiled Wasm module via Chicory (pure-Java interpreter, no JNI required).
 *
 * Create once and pass to [MarkerClusterStrategy] via the `wasmEngine` parameter.
 * The engine uses Wasm linear memory and must NOT be called concurrently.
 *
 * Chicory 1.x encodes all Wasm values as raw `Long`:
 *   - i32 → `int.toLong()`
 *   - f64 → `java.lang.Double.doubleToRawLongBits(d)`
 *   - Result i32 ← `long.toInt()`
 *   - Result f64 ← `java.lang.Double.longBitsToDouble(long)`
 */
class ClusteringWasmEngine private constructor(private val instance: Instance) {

    data class MergedGroup(
        val center: GeoPoint,
        val members: List<MarkerState>,
    )

    /**
     * Runs grid-assignment + greedy-merge on [markers] and returns merged groups.
     * Groups with fewer members than [minClusterSize] are still returned as-is;
     * the caller is responsible for the final split into clusters vs. individual markers.
     */
    fun computeClusters(
        markers: List<MarkerState>,
        zoom: Double,
        clusterRadiusPx: Double,
        tileSize: Double,
    ): List<MergedGroup> {
        val count = markers.size
        if (count == 0) return emptyList()

        val byteCount = count * Double.SIZE_BYTES

        // Allocate input buffers inside Wasm linear memory
        val latsPtr = wasmAlloc(byteCount)
        val lonsPtr = wasmAlloc(byteCount)

        try {
            val memory = instance.memory()
            val buf = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)

            buf.clear()
            markers.forEach { buf.putDouble(it.position.latitude) }
            memory.write(latsPtr, buf.array())

            buf.clear()
            markers.forEach { buf.putDouble(it.position.longitude) }
            memory.write(lonsPtr, buf.array())

            // Chicory 1.x: all values encoded as Long
            val groupCount = instance.export("compute_clusters").apply(
                latsPtr.toLong(),
                lonsPtr.toLong(),
                count.toLong(),
                java.lang.Double.doubleToRawLongBits(zoom),
                java.lang.Double.doubleToRawLongBits(clusterRadiusPx),
                java.lang.Double.doubleToRawLongBits(tileSize),
            )[0].toInt()

            if (groupCount == 0) return emptyList()

            val clusterLatsPtr = callI32("get_result_cluster_lats_ptr")
            val clusterLonsPtr = callI32("get_result_cluster_lons_ptr")
            val clusterSizesPtr = callI32("get_result_cluster_sizes_ptr")
            val memberOffsetsPtr = callI32("get_result_member_offsets_ptr")
            val memberIdsPtr = callI32("get_result_member_ids_ptr")
            val memberIdsLen = callI32("get_result_member_ids_len")

            val latsBytes = memory.readBytes(clusterLatsPtr, groupCount * Double.SIZE_BYTES)
            val lonsBytes = memory.readBytes(clusterLonsPtr, groupCount * Double.SIZE_BYTES)
            val sizesBytes = memory.readBytes(clusterSizesPtr, groupCount * Int.SIZE_BYTES)
            val offsetsBytes = memory.readBytes(memberOffsetsPtr, groupCount * Int.SIZE_BYTES)
            val memberIdsBytes = memory.readBytes(memberIdsPtr, memberIdsLen * Int.SIZE_BYTES)

            val latsBuf = ByteBuffer.wrap(latsBytes).order(ByteOrder.LITTLE_ENDIAN)
            val lonsBuf = ByteBuffer.wrap(lonsBytes).order(ByteOrder.LITTLE_ENDIAN)
            val sizesBuf = ByteBuffer.wrap(sizesBytes).order(ByteOrder.LITTLE_ENDIAN)
            val offsetsBuf = ByteBuffer.wrap(offsetsBytes).order(ByteOrder.LITTLE_ENDIAN)
            val memberIdsBuf = ByteBuffer.wrap(memberIdsBytes).order(ByteOrder.LITTLE_ENDIAN)

            val memberIds = IntArray(memberIdsLen) { memberIdsBuf.int }

            return List(groupCount) { _ ->
                val centerLat = latsBuf.double
                val centerLon = lonsBuf.double
                val size = sizesBuf.int
                val offset = offsetsBuf.int
                MergedGroup(
                    center = GeoPoint.fromLatLong(centerLat, centerLon),
                    members = List(size) { j -> markers[memberIds[offset + j]] },
                )
            }
        } finally {
            instance.export("wasm_dealloc").apply(latsPtr.toLong(), byteCount.toLong())
            instance.export("wasm_dealloc").apply(lonsPtr.toLong(), byteCount.toLong())
        }
    }

    private fun wasmAlloc(bytes: Int): Int =
        instance.export("wasm_alloc").apply(bytes.toLong())[0].toInt()

    private fun callI32(name: String): Int =
        instance.export(name).apply()[0].toInt()

    companion object {
        private const val WASM_ASSET = "clustering_wasm.wasm"

        fun create(context: Context): ClusteringWasmEngine {
            val bytes = context.assets.open(WASM_ASSET).use { it.readBytes() }
            val module = Parser.parse(bytes.inputStream())
            val instance = Instance.builder(module).build()
            return ClusteringWasmEngine(instance)
        }
    }
}
