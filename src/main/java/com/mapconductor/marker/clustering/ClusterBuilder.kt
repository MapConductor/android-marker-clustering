package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.spherical.Spherical
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** クラスタリング格子のセル座標。 */
internal data class ClusterCell(
    val x: Int,
    val y: Int,
)

/** 格子セル 1 つ分のまとまり。[ClusterBuilder.mergeClusters] の入力。 */
internal data class ClusterCandidate(
    val cell: ClusterCell,
    val center: GeoPoint,
    val members: MutableList<MarkerState>,
)

/** 近傍セルを吸収したあとのまとまり。[ClusterBuilder.mergeClusters] の出力。 */
internal data class MergedCluster(
    val center: GeoPoint,
    val members: List<MarkerState>,
)

/**
 * 「どのマーカーを 1 つのクラスタにまとめるか」を決める部分。
 *
 * 状態を持たず、渡された候補だけから結果を決める。カメラや描画の都合は
 * [MarkerClusterStrategy] と [ClusterPlanner] 側にあり、ここには入れない。
 *
 * ios-sdk の `ClusterBuilder.swift` / react-sdk の `ClusterBuilder.ts` と同じ計算。
 * 結果が 3 者で一致していることが前提なので、しきい値や順序を変えるときは
 * 必ず 3 つとも直すこと。
 */
internal class ClusterBuilder(
    private val geometry: ClusterGeometry,
    private val clusterRadiusPx: Double,
    private val tileSize: Double,
) {
    /**
     * ズームに応じて実効クラスタ半径を縮める。
     *
     * 低ズームでは画面上の固定半径が数百 km に相当してしまい、まとめすぎに見える。
     */
    fun effectiveClusterRadiusPx(zoom: Double): Double {
        val scale = (zoom / RADIUS_REFERENCE_ZOOM).coerceIn(RADIUS_MIN_SCALE, 1.0)
        return max(RADIUS_MIN_PX, clusterRadiusPx * scale)
    }

    fun buildClusterId(
        cell: ClusterCell,
        zoom: Double,
    ): String = "cluster_${zoom.roundToInt()}_${cell.x}_${cell.y}"

    /** マーカー 1 件が属する格子セルを返す。 */
    fun cellOf(
        position: GeoPoint,
        zoom: Double,
        effectiveRadiusPx: Double,
    ): ClusterCell {
        val (x, y) = geometry.projectToPixel(position, zoom, tileSize)
        return ClusterCell(
            x = floor(x / effectiveRadiusPx).toInt(),
            y = floor(y / effectiveRadiusPx).toInt(),
        )
    }

    /**
     * 近い候補どうしをまとめる。
     *
     * 連鎖的な併合（A-B が近く B-C が近いだけで A-C まで 1 つになる）を避けるため、
     * 種となる候補の半径に入るものだけを貪欲に吸収する。
     */
    fun mergeClusters(
        candidates: List<ClusterCandidate>,
        zoom: Double,
        clusterRadiusPx: Double,
    ): List<MergedCluster> {
        if (candidates.isEmpty()) return emptyList()
        val indexByCell =
            HashMap<ClusterCell, Int>(candidates.size * 2).apply {
                candidates.forEachIndexed { index, candidate ->
                    put(candidate.cell, index)
                }
            }
        val visited = BooleanArray(candidates.size)
        val merged = mutableListOf<MergedCluster>()

        for (i in candidates.indices) {
            if (visited[i]) continue
            visited[i] = true

            val seed = candidates[i]
            val seedCenter = seed.center
            val seedMetersPerPixel = geometry.metersPerPixel(seedCenter, zoom, tileSize)

            val members = mutableListOf<MarkerState>()
            members.addAll(seed.members)

            // Because candidates are bucketed into ClusterCell grids of size `clusterRadiusPx`,
            // any candidate within the merge distance must be in the same cell or one of the 8 neighbors.
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val neighborIndex =
                        indexByCell[ClusterCell(x = seed.cell.x + dx, y = seed.cell.y + dy)] ?: continue
                    if (visited[neighborIndex]) continue

                    val neighborCenter = candidates[neighborIndex].center
                    val neighborMetersPerPixel = geometry.metersPerPixel(neighborCenter, zoom, tileSize)
                    val thresholdMeters = clusterRadiusPx * max(seedMetersPerPixel, neighborMetersPerPixel)
                    val distanceMeters = Spherical.computeDistanceBetween(seedCenter, neighborCenter)
                    if (distanceMeters <= thresholdMeters) {
                        visited[neighborIndex] = true
                        members.addAll(candidates[neighborIndex].members)
                    }
                }
            }

            val center = selectDenseCenter(members, zoom, clusterRadiusPx)
            merged.add(MergedCluster(center = center, members = members))
        }

        return merged
    }

    /**
     * まとまりの中で最も密なところにいるメンバーの位置を返す。
     *
     * 単純な平均だと、外れ値ひとつでクラスタの見かけ上の中心が誰もいない場所へ動く。
     * 格子で粗く数えてから上位セルの中だけを総当たりするので、メンバー数に対して線形。
     */
    fun selectDenseCenter(
        members: List<MarkerState>,
        zoom: Double,
        clusterRadiusPx: Double,
    ): GeoPoint {
        if (members.isEmpty()) {
            return GeoPoint.fromLatLong(0.0, 0.0)
        }
        if (members.size == 1) {
            return GeoPoint.from(members[0].position)
        }

        val points =
            members.map { member ->
                val (x, y) = geometry.projectToPixel(member.position, zoom, tileSize)
                PixelPoint(member = member, x = x, y = y)
            }
        val cellSize = clusterRadiusPx
        val cellMap = linkedMapOf<CellKey, MutableList<PixelPoint>>()
        points.forEach { point ->
            val key =
                CellKey(
                    x = floor(point.x / cellSize).toInt(),
                    y = floor(point.y / cellSize).toInt(),
                )
            cellMap.getOrPut(key) { mutableListOf() }.add(point)
        }

        val sortedCells = cellMap.entries.sortedByDescending { it.value.size }
        val candidates =
            sortedCells
                .take(MAX_DENSE_CELLS)
                .flatMap { it.value }
                .take(MAX_DENSE_CANDIDATES)

        val radiusSq = cellSize * cellSize
        var bestPoint = candidates.firstOrNull() ?: points.first()
        var bestNeighborCount = -1
        var bestTotalDistance = Double.MAX_VALUE
        candidates.forEach { candidate ->
            var neighborCount = 0
            var totalDistance = 0.0
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val key =
                        CellKey(
                            x = floor(candidate.x / cellSize).toInt() + dx,
                            y = floor(candidate.y / cellSize).toInt() + dy,
                        )
                    val neighbors = cellMap[key] ?: continue
                    neighbors.forEach { other ->
                        val dxp = candidate.x - other.x
                        val dyp = candidate.y - other.y
                        val distSq = dxp * dxp + dyp * dyp
                        if (distSq <= radiusSq) {
                            neighborCount += 1
                            totalDistance += sqrt(distSq)
                        }
                    }
                }
            }
            if (neighborCount > bestNeighborCount ||
                (neighborCount == bestNeighborCount && totalDistance < bestTotalDistance)
            ) {
                bestNeighborCount = neighborCount
                bestTotalDistance = totalDistance
                bestPoint = candidate
            }
        }

        return GeoPoint.from(bestPoint.member.position)
    }

    private data class PixelPoint(
        val member: MarkerState,
        val x: Double,
        val y: Double,
    )

    private data class CellKey(
        val x: Int,
        val y: Int,
    )

    companion object {
        private const val RADIUS_REFERENCE_ZOOM: Double = 10.0
        private const val RADIUS_MIN_SCALE: Double = 0.35
        private const val RADIUS_MIN_PX: Double = 18.0
        private const val MAX_DENSE_CELLS: Int = 4
        private const val MAX_DENSE_CANDIDATES: Int = 50
    }
}
