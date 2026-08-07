package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.spherical.Spherical
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 位置だけの指紋。前回の描画からマーカーが動いたかを見るために使う。
 *
 * マーカー全体を見る `MarkerFingerPrint` とは別物で、あちらは
 * 「作り直しが要るか」を、こちらは「クラスタの再計算が要るか」を決める。
 */
internal data class PositionFingerPrint(
    val latBits: Long,
    val lonBits: Long,
) {
    companion object {
        fun from(position: GeoPointInterface): PositionFingerPrint =
            PositionFingerPrint(
                latBits = java.lang.Double.doubleToLongBits(position.latitude),
                lonBits = java.lang.Double.doubleToLongBits(position.longitude),
            )
    }
}

/** 1 つのクラスタとして描くと決まったまとまり。 */
internal data class PlannedCluster(
    val id: String,
    val center: GeoPoint,
    val members: List<MarkerState>,
    val radiusMeters: Double,
    val cell: ClusterCell,
    val hullPoints: List<GeoPointInterface>,
)

/**
 * 計画の 1 要素。**元の並び順を保つため**にクラスタと素通しを 1 つの列で持つ。
 *
 * 並び順は描画側の追加順になり、プロバイダによっては重なり順に影響する。
 * クラスタだけ・素通しだけを別々のリストにすると順序が変わってしまう。
 */
internal sealed interface PlannedEntry {
    /** クラスタとして 1 つのマーカーで描くもの。 */
    data class Cluster(
        val cluster: PlannedCluster,
    ) : PlannedEntry

    /** [MarkerClusterStrategy] の minClusterSize に満たず、そのまま描くもの。 */
    data class Singles(
        val states: List<MarkerState>,
    ) : PlannedEntry
}

/** [ClusterPlanner.plan] の結果一式。 */
internal class ClusterPlan(
    val entries: List<PlannedEntry>,
    val clusterMemberCenters: Map<String, GeoPoint>,
    val clusterPositions: Map<String, GeoPoint>,
    val assignments: Map<String, String>,
    val coverageBounds: GeoRectBounds,
    val sourceFingerprints: Map<String, PositionFingerPrint>,
)

/** 前回の描画結果のうち、今回の計算で再利用するもの。 */
internal class ClusterPlanCache(
    val assignments: Map<String, String>,
    val clusterPositions: Map<String, GeoPoint>,
    val coverageBounds: GeoRectBounds?,
    val sourceFingerprints: Map<String, PositionFingerPrint>,
)

/**
 * 「今の画面に何をどう描くか」を決める部分。マーカーの状態から計画を作るだけで、
 * 描画も状態の書き換えもしない。
 *
 * 前回の割り当てをできるだけ再利用するのが要点で、これが無いとパンのたびに
 * クラスタの中心が微妙に動いてちらつく。動いていないマーカーは前回の
 * クラスタに置いたまま、新しく入ってきたものだけを [ClusterBuilder] にかける。
 *
 * ios-sdk の `ClusterPlanner.swift` / react-sdk の `ClusterPlanner.ts` と同じ手順。
 */
internal class ClusterPlanner(
    private val geometry: ClusterGeometry,
    private val builder: ClusterBuilder,
    private val minClusterSize: Int,
) {
    suspend fun plan(
        sourceStates: Collection<MarkerState>,
        expandedBounds: GeoRectBounds,
        zoom: Double,
        effectiveRadiusPx: Double,
        zoomChanged: Boolean,
        tileSize: Double,
        cache: ClusterPlanCache,
    ): ClusterPlan {
        val currentFingerprints = mutableMapOf<String, PositionFingerPrint>()
        val cachedMarkers = mutableListOf<MarkerState>()
        val newMarkers = mutableListOf<MarkerState>()

        // ズームが変わったときに前回の割り当てを捨てるのは呼び出し側の責任
        // （[ClusterRenderState.assignments] を空にしてから渡す）。ここで
        // 握りつぶすと、計画が途中で打ち切られたときに捨て損ねる。
        val assignments = cache.assignments

        sourceStates.forEach { state ->
            currentCoroutineContext().ensureActive()
            if (!geometry.containsInViewport(expandedBounds, state.position, zoom)) return@forEach

            val fp = PositionFingerPrint.from(state.position)
            currentFingerprints[state.id] = fp
            val movedSinceLastRender = cache.sourceFingerprints[state.id]?.let { it != fp } ?: true

            if (!zoomChanged &&
                geometry.containsInViewport(cache.coverageBounds, state.position, zoom) &&
                assignments.containsKey(state.id) &&
                !movedSinceLastRender
            ) {
                cachedMarkers.add(state)
            } else {
                newMarkers.add(state)
            }
        }

        val cachedClusterGroups = mutableMapOf<String, MutableList<MarkerState>>()
        val cachedMarkerGroups = mutableMapOf<String, MutableList<MarkerState>>()
        cachedMarkers.forEach { marker ->
            val clusterId = assignments[marker.id]
            if (clusterId != null && clusterId.startsWith(CLUSTER_ID_PREFIX)) {
                cachedClusterGroups.getOrPut(clusterId) { mutableListOf() }.add(marker)
            } else {
                val key = clusterId ?: marker.id
                cachedMarkerGroups.getOrPut(key) { mutableListOf() }.add(marker)
            }
        }

        val clustered = mutableMapOf<ClusterCell, MutableList<MarkerState>>()
        newMarkers.forEach { state ->
            currentCoroutineContext().ensureActive()
            val cell = builder.cellOf(GeoPoint.from(state.position), zoom, effectiveRadiusPx)
            clustered.getOrPut(cell) { mutableListOf() }.add(state)
        }
        val candidates =
            clustered.entries
                .sortedWith(
                    compareBy<MutableMap.MutableEntry<ClusterCell, MutableList<MarkerState>>> {
                        it.key.x
                    }.thenBy { it.key.y },
                ).mapNotNull { entry ->
                    val members = entry.value
                    val center = members.firstOrNull()?.position ?: return@mapNotNull null
                    ClusterCandidate(
                        cell = entry.key,
                        center = GeoPoint.from(center),
                        members = members.toMutableList(),
                    )
                }
        val mergedClusters = builder.mergeClusters(candidates, zoom, effectiveRadiusPx)

        val finalMergedClusters =
            absorbCachedClusters(
                mergedClusters = mergedClusters,
                cachedClusterGroups = cachedClusterGroups,
                cachedMarkerGroups = cachedMarkerGroups,
                cachedClusterPositions = cache.clusterPositions,
                zoom = zoom,
                effectiveRadiusPx = effectiveRadiusPx,
                tileSize = tileSize,
            )

        return buildPlan(
            finalMergedClusters = finalMergedClusters,
            zoom = zoom,
            effectiveRadiusPx = effectiveRadiusPx,
            currentFingerprints = currentFingerprints,
        )
    }

    /**
     * 新しく計算したまとまりを、位置が近い前回のクラスタへ吸収させる。
     *
     * 吸収できたものは**前回の中心をそのまま使う**。メンバーが変わっていないのに
     * 中心だけ動くと、パンのたびにクラスタマーカーが小刻みに揺れて見えるため。
     */
    private suspend fun absorbCachedClusters(
        mergedClusters: List<MergedCluster>,
        cachedClusterGroups: Map<String, MutableList<MarkerState>>,
        cachedMarkerGroups: Map<String, MutableList<MarkerState>>,
        cachedClusterPositions: Map<String, GeoPoint>,
        zoom: Double,
        effectiveRadiusPx: Double,
        tileSize: Double,
    ): List<MergedCluster> {
        val result = mutableListOf<MergedCluster>()
        val usedCachedClusters = mutableSetOf<String>()

        mergedClusters.forEach { merged ->
            currentCoroutineContext().ensureActive()
            var mergedWithCached = false
            val newCenter = merged.center

            cachedClusterGroups.forEach { (cachedClusterId, cachedMembers) ->
                if (mergedWithCached || cachedClusterId in usedCachedClusters) return@forEach
                val cachedPosition = cachedClusterPositions[cachedClusterId] ?: return@forEach
                val metersPerPixelVal = geometry.metersPerPixel(newCenter, zoom, tileSize)
                val thresholdMeters = effectiveRadiusPx * metersPerPixelVal
                val distance = Spherical.computeDistanceBetween(newCenter, cachedPosition)
                if (distance <= thresholdMeters) {
                    val combinedMembers = cachedMembers + merged.members
                    result.add(
                        MergedCluster(
                            center = cachedPosition,
                            members = combinedMembers.toMutableList(),
                        ),
                    )
                    usedCachedClusters.add(cachedClusterId)
                    mergedWithCached = true
                }
            }

            if (!mergedWithCached) {
                result.add(merged)
            }
        }

        cachedClusterGroups.forEach { (cachedClusterId, cachedMembers) ->
            if (cachedClusterId in usedCachedClusters) return@forEach
            val cachedPosition = cachedClusterPositions[cachedClusterId] ?: return@forEach
            result.add(
                MergedCluster(
                    center = cachedPosition,
                    members = cachedMembers,
                ),
            )
        }

        cachedMarkerGroups.values.forEach { cachedMembers ->
            val center = cachedMembers.firstOrNull()?.position ?: return@forEach
            result.add(
                MergedCluster(
                    center = GeoPoint.from(center),
                    members = cachedMembers,
                ),
            )
        }

        return result
    }

    /** まとまりごとに「クラスタにする／そのまま描く」を決め、中心と半径を確定させる。 */
    private suspend fun buildPlan(
        finalMergedClusters: List<MergedCluster>,
        zoom: Double,
        effectiveRadiusPx: Double,
        currentFingerprints: Map<String, PositionFingerPrint>,
    ): ClusterPlan {
        val entries = mutableListOf<PlannedEntry>()
        val clusterMemberCenters = mutableMapOf<String, GeoPoint>()
        val clusterPositions = mutableMapOf<String, GeoPoint>()
        val assignments = mutableMapOf<String, String>()
        val coverageBounds = GeoRectBounds()

        finalMergedClusters.forEach { merged ->
            currentCoroutineContext().ensureActive()
            if (merged.members.size < minClusterSize) {
                merged.members.forEach { member ->
                    coverageBounds.extend(member.position)
                    assignments[member.id] = member.id
                }
                entries.add(PlannedEntry.Singles(merged.members))
                return@forEach
            }

            // 凸包の靴ひも重心を中心にする。全員がほぼ同じ点にいて凸包が潰れる場合は
            // メンバー平均へ落とす（同じ会場のクラスタが最初の 1 人の位置や
            // 前回のキャッシュ位置ではなく、その会場に出るようにするため）。
            val hull = geometry.convexHullProjected(merged.members)
            val centroidProjected = geometry.polygonCentroidProjected(hull)
            val centroid = centroidProjected?.let { geometry.unproject(it) }

            // 中心は毎回の再クラスタで現在のメンバーから計算し直す。メンバーが
            // 変わらないパンでは同じ重心になるのでちらつかず、メンバーが変われば
            // 古いキャッシュ位置に貼り付かず本来の中心へ動く。
            val center = GeoPoint.from(centroid ?: geometry.averagePosition(merged.members))
            val cell = builder.cellOf(center, zoom, effectiveRadiusPx)
            val clusterId = builder.buildClusterId(cell, zoom)
            val radiusMeters = geometry.calculateClusterRadiusMeters(center, merged.members)

            merged.members.forEach { member ->
                clusterMemberCenters[member.id] = center
                assignments[member.id] = clusterId
            }
            clusterPositions[clusterId] = center
            geometry.extendCoverageBounds(coverageBounds, center, radiusMeters)

            entries.add(
                PlannedEntry.Cluster(
                    PlannedCluster(
                        id = clusterId,
                        center = center,
                        members = merged.members,
                        radiusMeters = radiusMeters,
                        cell = cell,
                        hullPoints =
                            if (hull.size >= 3) {
                                hull.map { geometry.unproject(it) }
                            } else {
                                emptyList()
                            },
                    ),
                ),
            )
        }

        return ClusterPlan(
            entries = entries,
            clusterMemberCenters = clusterMemberCenters,
            clusterPositions = clusterPositions,
            assignments = assignments,
            coverageBounds = coverageBounds,
            sourceFingerprints = currentFingerprints,
        )
    }

    companion object {
        const val CLUSTER_ID_PREFIX: String = "cluster_"
    }
}
