package com.mapconductor.marker.clustering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.compose.MapViewScope
import com.mapconductor.compose.marker.LocalMarkerCollector
import com.mapconductor.compose.marker.Markers
import com.mapconductor.compose.polygon.LocalPolygonCollector
import com.mapconductor.compose.polyline.LocalPolylineCollector
import com.mapconductor.core.ChildCollector
import com.mapconductor.core.map.LocalMapServiceRegistry
import com.mapconductor.core.map.LocalMapViewController
import com.mapconductor.core.marker.MarkerCollector
import com.mapconductor.core.marker.MarkerIconInterface
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.marker.StrategyMarkerController
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

// Debug hull polygon styling. Fixed rather than configurable: `debugHullPolygons`
// is the only debug knob the public API exposes on all three platforms.
private val DEBUG_HULL_STROKE_WIDTH: Dp = 2.dp
private const val DEBUG_HULL_STROKE_ALPHA: Float = 0.8f
private const val DEBUG_HULL_FILL_ALPHA: Float = 0.18f

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
        val polygonCollector = LocalPolygonCollector.current
        val debugInfos by strategy.debugInfoFlow.collectAsState()
        var activeHullIds by remember(strategy, polygonCollector) { mutableStateOf<Set<String>>(emptySet()) }
        val latestActiveHullIds by rememberUpdatedState(activeHullIds)

        // When debug hull polygons are enabled, force a fresh cluster recompute so polygons
        // reflect the current camera position rather than whatever the coverage-bounds cache holds.
        LaunchedEffect(strategy, state.debugHullPolygons) {
            if (state.debugHullPolygons) {
                strategy.forceRender()
            }
        }

        LaunchedEffect(
            strategy,
            polygonCollector,
            state.debugHullPolygons,
            debugInfos,
        ) {
            activeHullIds =
                if (state.debugHullPolygons) {
                    syncDebugHullPolygons(
                        polygonCollector = polygonCollector,
                        debugInfos = debugInfos,
                        strokeWidth = DEBUG_HULL_STROKE_WIDTH,
                        strokeAlpha = DEBUG_HULL_STROKE_ALPHA,
                        fillAlpha = DEBUG_HULL_FILL_ALPHA,
                        activeHullIds = activeHullIds,
                    )
                } else {
                    removeDebugHullPolygons(polygonCollector, activeHullIds)
                }
        }

        // Capture style values on the main thread so the background callback
        // never reads Compose state off-thread. The DisposableEffect re-runs
        // (installing a fresh callback) whenever the debug hull setting or style changes.
        DisposableEffect(
            strategy,
            polygonCollector,
            state.debugHullPolygons,
        ) {
            if (state.debugHullPolygons) {
                val strokeWidth = DEBUG_HULL_STROKE_WIDTH
                val strokeAlpha = DEBUG_HULL_STROKE_ALPHA
                val fillAlpha = DEBUG_HULL_FILL_ALPHA

                strategy.onBeforeAnimation = { nextDebugInfos ->
                    // Build polygon states on a background thread, then commit
                    // them on the main thread — both complete before returning,
                    // so updateRenderedMarkers() (animation) cannot start until
                    // polygon add/remove operations are fully applied.
                    activeHullIds =
                        syncDebugHullPolygons(
                            polygonCollector = polygonCollector,
                            debugInfos = nextDebugInfos,
                            strokeWidth = strokeWidth,
                            strokeAlpha = strokeAlpha,
                            fillAlpha = fillAlpha,
                            activeHullIds = activeHullIds,
                        )
                }

                onDispose {
                    strategy.onBeforeAnimation = null
                }
            } else {
                strategy.onBeforeAnimation = null

                onDispose {
                    strategy.onBeforeAnimation = null
                }
            }
        }

        DisposableEffect(strategy, polygonCollector) {
            onDispose {
                strategy.onBeforeAnimation = null
                latestActiveHullIds.forEach { polygonCollector.remove(it) }
            }
        }

        // ── Spiderfy leg polylines ────────────────────────────────────────
        // The strategy publishes the legs of the currently open fan (empty
        // when collapsed); mirror them into the map's polyline collector.
        val polylineCollector = LocalPolylineCollector.current
        val spiderfyLegs by strategy.spiderfyLegsFlow.collectAsState()
        var activeLegIds by remember(strategy, polylineCollector) { mutableStateOf<Set<String>>(emptySet()) }
        val latestActiveLegIds by rememberUpdatedState(activeLegIds)

        LaunchedEffect(
            strategy,
            polylineCollector,
            spiderfyLegs,
            state.spiderfyLegColor,
            state.spiderfyLegWidth,
        ) {
            val nextIds = spiderfyLegs.map { it.id }.toSet()
            (activeLegIds - nextIds).forEach { polylineCollector.remove(it) }
            spiderfyLegs.forEach { leg ->
                polylineCollector.add(
                    PolylineState(
                        points = listOf(leg.start, leg.end),
                        id = leg.id,
                        strokeColor = state.spiderfyLegColor,
                        strokeWidth = state.spiderfyLegWidth,
                        geodesic = false,
                    ),
                )
            }
            activeLegIds = nextIds
        }

        DisposableEffect(strategy, polylineCollector) {
            onDispose {
                latestActiveLegIds.forEach { polylineCollector.remove(it) }
            }
        }

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
    spiderfyLegColor: Color = Color(0xFF666666),
    spiderfyLegWidth: Dp = 1.5.dp,
    onSpiderfyChange: ((Boolean) -> Unit)? = null,
    clusterRadiusStrokeColor: Color = Color.Red,
    clusterRadiusStrokeWidth: Dp = 1.dp,
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
    spiderfyLegColor: Color = Color(0xFF666666),
    spiderfyLegWidth: Dp = 1.5.dp,
    onSpiderfyChange: ((Boolean) -> Unit)? = null,
    clusterRadiusStrokeColor: Color = Color.Red,
    clusterRadiusStrokeWidth: Dp = 1.dp,
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

private data class DebugCellKey(
    val x: Int,
    val y: Int,
)

private suspend fun syncDebugHullPolygons(
    polygonCollector: ChildCollector<PolygonState>,
    debugInfos: List<MarkerClusterDebugInfo>,
    strokeWidth: Dp,
    strokeAlpha: Float,
    fillAlpha: Float,
    activeHullIds: Set<String>,
): Set<String> {
    val nextStates =
        withContext(Dispatchers.Default) {
            val colorsByCell = assignDistinctDebugColors(debugInfos)
            debugInfos
                .filter { it.hullPoints.size >= 3 }
                .map { info ->
                    val base = colorsByCell[DebugCellKey(info.cellX, info.cellY)] ?: Color.Magenta
                    PolygonState(
                        id = "cluster-hull-${info.id}",
                        points = info.hullPoints,
                        strokeColor = base.copy(alpha = strokeAlpha),
                        strokeWidth = strokeWidth,
                        fillColor = base.copy(alpha = fillAlpha),
                        geodesic = false,
                        zIndex = 9,
                        extra = null,
                        onClick = null,
                    )
                }
        }

    return withContext(Dispatchers.Main) {
        val nextIds = nextStates.map { it.id }.toSet()
        (activeHullIds - nextIds).forEach { polygonCollector.remove(it) }
        nextStates.forEach { polygonCollector.add(it) }
        nextIds
    }
}

private fun removeDebugHullPolygons(
    polygonCollector: ChildCollector<PolygonState>,
    activeHullIds: Set<String>,
): Set<String> {
    activeHullIds.forEach { polygonCollector.remove(it) }
    return emptySet()
}

private fun assignDistinctDebugColors(infos: List<MarkerClusterDebugInfo>): Map<DebugCellKey, Color> {
    if (infos.isEmpty()) return emptyMap()

    val palette =
        listOf(
            Color(0xFFE53935), // red
            Color(0xFFD81B60), // pink
            Color(0xFF8E24AA), // purple
            Color(0xFF5E35B1), // deep purple
            Color(0xFF3949AB), // indigo
            Color(0xFF1E88E5), // blue
            Color(0xFF039BE5), // light blue
            Color(0xFF00ACC1), // cyan
            Color(0xFF00897B), // teal
            Color(0xFF43A047), // green
            Color(0xFF7CB342), // light green
            Color(0xFFFDD835), // yellow
            Color(0xFFFFB300), // amber
            Color(0xFFFB8C00), // orange
        )

    val result = LinkedHashMap<DebugCellKey, Color>(infos.size * 2)
    val sorted = infos.sortedWith(compareBy<MarkerClusterDebugInfo> { it.cellX }.thenBy { it.cellY })

    fun neighborColors(key: DebugCellKey): Set<Color> {
        val used = mutableSetOf<Color>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val c = result[DebugCellKey(key.x + dx, key.y + dy)] ?: continue
                used.add(c)
            }
        }
        return used
    }

    sorted.forEach { info ->
        val key = DebugCellKey(info.cellX, info.cellY)
        val used = neighborColors(key)
        val start = (info.id.hashCode() and 0x7fffffff) % palette.size
        var chosen: Color? = null
        for (i in palette.indices) {
            val candidate = palette[(start + i) % palette.size]
            if (candidate !in used) {
                chosen = candidate
                break
            }
        }
        result[key] = chosen ?: palette[start]
    }

    return result
}

@OptIn(FlowPreview::class)
@Composable
private fun MarkerRenderingGroup(
    strategy: MarkerClusterStrategy,
    trackMarkerUpdates: Boolean,
    content: @Composable () -> Unit,
) {
    val mapController = LocalMapViewController.current

    val services = LocalMapServiceRegistry.current

    @Suppress("UNCHECKED_CAST")
    val renderingSupport =
        services.get(MarkerRenderingSupportKey) as? MarkerRenderingSupport<Any> ?: return
    val markerCollector = remember { MarkerCollector() }
    val renderer =
        remember(mapController) {
            renderingSupport.createMarkerRenderer(strategy)
        }
    val markerController =
        remember(strategy, renderer) {
            StrategyMarkerController(
                strategy = strategy,
                renderer = renderer,
            )
        }
    val eventController =
        remember(markerController, renderer) {
            renderingSupport.createMarkerEventController(markerController, renderer)
        }

    var isRegistered by remember { mutableStateOf(false) }

    LaunchedEffect(mapController, markerController, eventController) {
        mapController.registerOverlayController(markerController)
        renderingSupport.registerMarkerEventController(eventController)
        isRegistered = true
    }

    DisposableEffect(markerCollector, markerController, strategy, trackMarkerUpdates) {
        if (trackMarkerUpdates) {
            // Gated on the strategy's source set, not on `getEntity(id) != null`.
            // A member swallowed by a cluster has no entity of its own, so the
            // entity check would drop exactly the markers that need forwarding:
            // moving one must still re-cluster. Matches ios-marker-cluster's
            // `statesById[state.id] != nil` guard.
            markerCollector.setUpdateHandler { markerState ->
                if (strategy.hasSourceMarker(markerState.id)) {
                    withContext(Dispatchers.Default) {
                        markerController.update(markerState)
                    }
                }
            }
        } else {
            markerCollector.setUpdateHandler(null)
        }
        onDispose {
            markerCollector.setUpdateHandler(null)
        }
    }

    val mapLoaded = renderingSupport.mapLoadedState?.collectAsState()?.value ?: true
    var requestedInitialCameraUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(mapLoaded, isRegistered) {
        if (!mapLoaded || !isRegistered || requestedInitialCameraUpdate) return@LaunchedEffect
        requestedInitialCameraUpdate = true
        renderingSupport.onMarkerRenderingReady()
    }

    LaunchedEffect(mapLoaded) {
        if (!mapLoaded) return@LaunchedEffect
        markerCollector.flow.collectLatest { markerMap ->
            // Avoid doing O(n) work on the UI thread for very large marker sets.
            val snapshot =
                withContext(Dispatchers.Default) {
                    markerMap.values.toList()
                }
            withContext(Dispatchers.Default) {
                markerController.add(snapshot)
            }
        }
    }

    CompositionLocalProvider(LocalMarkerCollector provides markerCollector) {
        content()
    }
}
