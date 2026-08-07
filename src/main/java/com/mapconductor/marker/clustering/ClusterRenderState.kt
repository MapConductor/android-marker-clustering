package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import kotlin.math.roundToInt

/** [ClusterRenderState.updateClusteringTurn] の結果。 */
internal data class ZoomChange(
    val turn: Int,
    val zoomChanged: Boolean,
)

/**
 * 前回の再クラスタの結果。次回に再利用するためだけに持つ。
 *
 * ここが空だと毎回ゼロから計算することになり、動作は正しいがパンのたびに
 * クラスタ中心が動いてちらつく。[ClusterPlanner] がこれを見て
 * 「動いていないマーカーは前のクラスタに置いたまま」にする。
 *
 * 単一の描画ワーカーからのみ触るので同期は要らない。
 *
 * ios-sdk の `ClusterRenderState.swift` /
 * react-sdk の `ClusterRenderState.ts` と同じ持ち物。
 */
internal class ClusterRenderState {
    var clusterMemberCenters: Map<String, GeoPoint> = emptyMap()
    var clusterPositions: Map<String, GeoPoint> = emptyMap()
    var assignments: Map<String, String> = emptyMap()
    var coverageBounds: GeoRectBounds? = null
    var sourceStateVersion: Long = 0
    var sourceFingerprints: Map<String, PositionFingerPrint> = emptyMap()
    var renderCameraPosition: MapCameraPosition? = null

    private var lastZoomKey: Int? = null
    private var clusteringTurn = 0

    /**
     * ズームが変わったかを見て、変わっていれば周回数を進める。
     *
     * 周回数はアイコン提供側（`clusterIconProviderWithTurn`）へ渡り、
     * 「ズームするたびに色を変える」といった表現に使われる。
     * 小数第 2 位まででズームを丸めるので、わずかな揺れでは進まない。
     */
    fun updateClusteringTurn(zoom: Double): ZoomChange {
        val zoomKey = (zoom * ZOOM_KEY_SCALE).roundToInt()
        if (lastZoomKey == null) {
            clusteringTurn = 1
            lastZoomKey = zoomKey
            return ZoomChange(turn = clusteringTurn, zoomChanged = false)
        }
        val zoomChanged = lastZoomKey != zoomKey
        if (zoomChanged) {
            clusteringTurn += 1
            lastZoomKey = zoomKey
        }
        return ZoomChange(turn = clusteringTurn, zoomChanged = zoomChanged)
    }

    /** [ClusterPlanner] に渡す形で取り出す。 */
    fun toPlanCache(): ClusterPlanCache =
        ClusterPlanCache(
            assignments = assignments,
            clusterPositions = clusterPositions,
            coverageBounds = coverageBounds,
            sourceFingerprints = sourceFingerprints,
        )

    /** 計画の結果を次回のキャッシュとして取り込む。 */
    fun commit(
        plan: ClusterPlan,
        cameraPosition: MapCameraPosition,
        sourceStateVersion: Long,
    ) {
        clusterMemberCenters = plan.clusterMemberCenters
        clusterPositions = plan.clusterPositions
        assignments = plan.assignments
        coverageBounds = if (plan.coverageBounds.isEmpty) null else plan.coverageBounds
        sourceFingerprints = plan.sourceFingerprints
        renderCameraPosition = cameraPosition
        this.sourceStateVersion = sourceStateVersion
    }

    fun reset() {
        clusterMemberCenters = emptyMap()
        clusterPositions = emptyMap()
        assignments = emptyMap()
        coverageBounds = null
        sourceStateVersion = 0
        sourceFingerprints = emptyMap()
        renderCameraPosition = null
        lastZoomKey = null
        clusteringTurn = 0
    }

    companion object {
        private const val ZOOM_KEY_SCALE: Double = 100.0
    }
}
