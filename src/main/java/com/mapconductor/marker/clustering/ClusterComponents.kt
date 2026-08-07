package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.geocell.HexGeocellInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

/**
 * [MarkerClusterStrategy] が使う内部部品の組み立て。
 *
 * 部品どうしはコンストラクタで注入し合う（[ClusterBuilder] は [ClusterGeometry] を、
 * [ClusterMarkerRenderer] は [ClusterMarkerAnimator] を受け取る、など）。
 * その配線をストラテジ本体から切り離しておくことで、本体は段取りだけを持つ。
 *
 * 差し替えは [Overrides] から行う。**公開コンストラクタには出していない** —
 * 出すと android / ios / react で公開 API の形が食い違い、
 * 「同じ API」を保てなくなるため。
 */
internal class ClusterComponents(
    geocell: HexGeocellInterface,
    clusterRadiusPx: Double,
    minClusterSize: Int,
    tileSize: Double,
    cameraIdleDebounceMillis: Long,
    zoomAnimationDurationMillis: Long,
    spiderfyMinZoom: Double?,
    spiderfyMarkerSizePx: Double,
    spiderfyMarkerMarginPx: Double,
    prepareExpand: (suspend (List<MarkerState>) -> Unit)?,
    onSpiderfyChange: ((Boolean) -> Unit)?,
    markerManager: MarkerManager<Any>,
    renderedMarkerEntities: ConcurrentHashMap<String, MarkerEntityInterface<Any>>,
    defaultMarkerIcon: BitmapIcon,
    scope: CoroutineScope,
    onRender: suspend (
        cameraPosition: MapCameraPosition,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) -> Unit,
    sourceStateProvider: (String) -> MarkerState?,
    overrides: Overrides?,
) {
    val geometry: ClusterGeometry = overrides?.geometry ?: ClusterGeometry(geocell)

    val builder: ClusterBuilder =
        overrides?.builder ?: ClusterBuilder(
            geometry = geometry,
            clusterRadiusPx = clusterRadiusPx,
            tileSize = tileSize,
        )

    val planner: ClusterPlanner =
        overrides?.planner ?: ClusterPlanner(
            geometry = geometry,
            builder = builder,
            minClusterSize = minClusterSize,
        )

    val scheduler: ClusterRenderScheduler =
        ClusterRenderScheduler(
            scope = scope,
            geometry = geometry,
            debounceMillis = cameraIdleDebounceMillis,
            onRender = onRender,
        )

    val animator: ClusterMarkerAnimator =
        overrides?.animator ?: ClusterMarkerAnimator(
            geometry = geometry,
            markerManager = markerManager,
            renderedMarkerEntities = renderedMarkerEntities,
            defaultMarkerIcon = defaultMarkerIcon,
            isCurrent = { scheduler.isCurrent(it) },
        )

    val markerRenderer: ClusterMarkerRenderer =
        ClusterMarkerRenderer(
            geometry = geometry,
            animator = animator,
            markerManager = markerManager,
            renderedMarkerEntities = renderedMarkerEntities,
            defaultMarkerIcon = defaultMarkerIcon,
            zoomAnimationDurationMillis = zoomAnimationDurationMillis,
            isCurrent = { scheduler.isCurrent(it) },
        )

    val spiderfy: SpiderfyController =
        SpiderfyController(
            geometry = geometry,
            layout = overrides?.spiderfyLayout ?: SpiderfyLayout(),
            markerManager = markerManager,
            renderedMarkerEntities = renderedMarkerEntities,
            defaultMarkerIcon = defaultMarkerIcon,
            scope = scope,
            minZoom = spiderfyMinZoom,
            markerSizePx = spiderfyMarkerSizePx,
            markerMarginPx = spiderfyMarkerMarginPx,
            prepareExpand = prepareExpand,
            onChange = onSpiderfyChange,
            cameraProvider = { scheduler.lastCameraPosition },
            rendererProvider = { scheduler.lastRenderer },
            sourceStateProvider = sourceStateProvider,
        )

    /**
     * 差し替えたい部品だけを入れる。null のものは既定の実装が使われる。
     *
     * [ClusterRenderScheduler] / [ClusterMarkerRenderer] / [SpiderfyController] は
     * ストラテジ本体の状態と密に結びついているため差し替え対象から外してある。
     * 計算だけを持つ部品（幾何・併合・計画・アニメーション）が対象。
     */
    class Overrides(
        val geometry: ClusterGeometry? = null,
        val builder: ClusterBuilder? = null,
        val planner: ClusterPlanner? = null,
        val animator: ClusterMarkerAnimator? = null,
        val spiderfyLayout: SpiderfyLayout? = null,
    )
}
