package com.mapconductor.marker.clustering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.compose.MapViewScope
import com.mapconductor.compose.marker.Markers
import com.mapconductor.core.marker.MarkerIconInterface
import com.mapconductor.core.marker.MarkerState

private val DEFAULT_SPIDERFY_LEG_COLOR: Color = Color(0xFF666666)
private val DEFAULT_SPIDERFY_LEG_WIDTH: Dp = 1.5.dp
private val DEFAULT_CLUSTER_RADIUS_STROKE_WIDTH: Dp = 1.dp

/**
 * 近くのマーカーを 1 つにまとめて描くグループ。
 *
 * 実際のクラスタリングは [MarkerClusterStrategy] が行い、ここは Compose 側の
 * 入口として、状態の記憶とデバッグ表示・spiderfy の脚の配線をまとめる。
 */
@Composable
fun MapViewScope.MarkerClusterGroup(
    state: MarkerClusterGroupState,
    markers: List<MarkerState>,
    trackMarkerUpdates: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    MarkerClusterGroup(
        state = state,
        trackMarkerUpdates = trackMarkerUpdates,
    ) {
        Markers(markers)
        content()
    }
}

@Composable
fun MapViewScope.MarkerClusterGroup(
    state: MarkerClusterGroupState,
    trackMarkerUpdates: Boolean = true,
    content: @Composable () -> Unit,
) {
    val strategy =
        remember(
            state.clusterRadiusPx,
            state.minClusterSize,
            state.expandMargin,
            state.clusterIconProvider,
            state.onClusterClick,
            state.prepareExpand,
            state.spiderfyMinZoom,
            state.spiderfyMarkerSizePx,
            state.spiderfyMarkerMarginPx,
            state.onSpiderfyChange,
            state.enableZoomAnimation,
            state.enablePanAnimation,
            state.zoomAnimationDurationMillis,
            state.cameraIdleDebounceMillis,
            state.tileSize,
        ) {
            MarkerClusterStrategy(
                clusterRadiusPx = state.clusterRadiusPx,
                minClusterSize = state.minClusterSize,
                expandMargin = state.expandMargin,
                clusterIconProvider = state.clusterIconProvider,
                onClusterClick = state.onClusterClick,
                prepareExpand = state.prepareExpand,
                spiderfyMinZoom = state.spiderfyMinZoom,
                spiderfyMarkerSizePx = state.spiderfyMarkerSizePx,
                spiderfyMarkerMarginPx = state.spiderfyMarkerMarginPx,
                onSpiderfyChange = state.onSpiderfyChange,
                enableZoomAnimation = state.enableZoomAnimation,
                enablePanAnimation = state.enablePanAnimation,
                zoomAnimationDurationMillis = state.zoomAnimationDurationMillis,
                debugHullPolygons = state.debugHullPolygons,
                cameraIdleDebounceMillis = state.cameraIdleDebounceMillis,
                tileSize = state.tileSize,
            )
        }

    MarkerRenderingGroup(
        strategy = strategy,
        trackMarkerUpdates = trackMarkerUpdates,
    ) {
        DebugHullPolygonEffects(
            strategy = strategy,
            enabled = state.debugHullPolygons,
        )
        SpiderfyLegEffects(
            strategy = strategy,
            legColor = state.spiderfyLegColor,
            legWidth = state.spiderfyLegWidth,
        )
        content()
    }
}

@Composable
fun MapViewScope.MarkerClusterGroup(
    clusterRadiusPx: Double = MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX,
    minClusterSize: Int = MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE,
    expandMargin: Double = MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN,
    clusterIconProvider: (Int) -> MarkerIconInterface = MarkerClusterStrategy.DEFAULT_ICON_PROVIDER,
    onClusterClick: ((MarkerCluster) -> Unit)? = null,
    prepareExpand: (suspend (List<MarkerState>) -> Unit)? = null,
    spiderfyMinZoom: Double? = null,
    spiderfyMarkerSizePx: Double = MarkerClusterStrategy.DEFAULT_SPIDERFY_MARKER_SIZE_PX,
    spiderfyMarkerMarginPx: Double = MarkerClusterStrategy.DEFAULT_SPIDERFY_MARKER_MARGIN_PX,
    spiderfyLegColor: Color = DEFAULT_SPIDERFY_LEG_COLOR,
    spiderfyLegWidth: Dp = DEFAULT_SPIDERFY_LEG_WIDTH,
    onSpiderfyChange: ((Boolean) -> Unit)? = null,
    clusterRadiusStrokeColor: Color = Color.Red,
    clusterRadiusStrokeWidth: Dp = DEFAULT_CLUSTER_RADIUS_STROKE_WIDTH,
    clusterRadiusFillColor: Color = Color.Transparent,
    enableZoomAnimation: Boolean = false,
    enablePanAnimation: Boolean = false,
    zoomAnimationDurationMillis: Long = MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    debugIncludeRenderCount: Boolean = false,
    debugHullPolygons: Boolean = false,
    cameraIdleDebounceMillis: Long = MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    tileSize: Double = MarkerClusterStrategy.DEFAULT_TILE_SIZE,
    markers: List<MarkerState>,
    content: @Composable () -> Unit = {},
) {
    MarkerClusterGroup(
        clusterRadiusPx = clusterRadiusPx,
        minClusterSize = minClusterSize,
        expandMargin = expandMargin,
        clusterIconProvider = clusterIconProvider,
        onClusterClick = onClusterClick,
        prepareExpand = prepareExpand,
        spiderfyMinZoom = spiderfyMinZoom,
        spiderfyMarkerSizePx = spiderfyMarkerSizePx,
        spiderfyMarkerMarginPx = spiderfyMarkerMarginPx,
        spiderfyLegColor = spiderfyLegColor,
        spiderfyLegWidth = spiderfyLegWidth,
        onSpiderfyChange = onSpiderfyChange,
        clusterRadiusStrokeColor = clusterRadiusStrokeColor,
        clusterRadiusStrokeWidth = clusterRadiusStrokeWidth,
        clusterRadiusFillColor = clusterRadiusFillColor,
        enableZoomAnimation = enableZoomAnimation,
        enablePanAnimation = enablePanAnimation,
        zoomAnimationDurationMillis = zoomAnimationDurationMillis,
        debugIncludeRenderCount = debugIncludeRenderCount,
        debugHullPolygons = debugHullPolygons,
        cameraIdleDebounceMillis = cameraIdleDebounceMillis,
        tileSize = tileSize,
    ) {
        Markers(markers)
        content()
    }
}

@Composable
fun MapViewScope.MarkerClusterGroup(
    clusterRadiusPx: Double = MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX,
    minClusterSize: Int = MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE,
    expandMargin: Double = MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN,
    clusterIconProvider: (Int) -> MarkerIconInterface = MarkerClusterStrategy.DEFAULT_ICON_PROVIDER,
    onClusterClick: ((MarkerCluster) -> Unit)? = null,
    prepareExpand: (suspend (List<MarkerState>) -> Unit)? = null,
    spiderfyMinZoom: Double? = null,
    spiderfyMarkerSizePx: Double = MarkerClusterStrategy.DEFAULT_SPIDERFY_MARKER_SIZE_PX,
    spiderfyMarkerMarginPx: Double = MarkerClusterStrategy.DEFAULT_SPIDERFY_MARKER_MARGIN_PX,
    spiderfyLegColor: Color = DEFAULT_SPIDERFY_LEG_COLOR,
    spiderfyLegWidth: Dp = DEFAULT_SPIDERFY_LEG_WIDTH,
    onSpiderfyChange: ((Boolean) -> Unit)? = null,
    clusterRadiusStrokeColor: Color = Color.Red,
    clusterRadiusStrokeWidth: Dp = DEFAULT_CLUSTER_RADIUS_STROKE_WIDTH,
    clusterRadiusFillColor: Color = Color.Transparent,
    enableZoomAnimation: Boolean = false,
    enablePanAnimation: Boolean = false,
    zoomAnimationDurationMillis: Long = MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    debugIncludeRenderCount: Boolean = false,
    debugHullPolygons: Boolean = false,
    cameraIdleDebounceMillis: Long = MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    tileSize: Double = MarkerClusterStrategy.DEFAULT_TILE_SIZE,
    content: @Composable () -> Unit,
) {
    val state =
        remember(
            clusterRadiusPx,
            minClusterSize,
            expandMargin,
            clusterIconProvider,
            onClusterClick,
            prepareExpand,
            spiderfyMinZoom,
            spiderfyMarkerSizePx,
            spiderfyMarkerMarginPx,
            spiderfyLegColor,
            spiderfyLegWidth,
            onSpiderfyChange,
            clusterRadiusStrokeColor,
            clusterRadiusStrokeWidth,
            clusterRadiusFillColor,
            enableZoomAnimation,
            enablePanAnimation,
            zoomAnimationDurationMillis,
            debugIncludeRenderCount,
            debugHullPolygons,
            cameraIdleDebounceMillis,
            tileSize,
        ) {
            MarkerClusterGroupState(
                clusterRadiusPx = clusterRadiusPx,
                minClusterSize = minClusterSize,
                expandMargin = expandMargin,
                clusterIconProvider = clusterIconProvider,
                onClusterClick = onClusterClick,
                prepareExpand = prepareExpand,
                spiderfyMinZoom = spiderfyMinZoom,
                spiderfyMarkerSizePx = spiderfyMarkerSizePx,
                spiderfyMarkerMarginPx = spiderfyMarkerMarginPx,
                spiderfyLegColor = spiderfyLegColor,
                spiderfyLegWidth = spiderfyLegWidth,
                onSpiderfyChange = onSpiderfyChange,
                enableZoomAnimation = enableZoomAnimation,
                enablePanAnimation = enablePanAnimation,
                zoomAnimationDurationMillis = zoomAnimationDurationMillis,
                debugHullPolygons = debugHullPolygons,
                cameraIdleDebounceMillis = cameraIdleDebounceMillis,
                tileSize = tileSize,
            )
        }
    MarkerClusterGroup(state = state, content = content)
}
