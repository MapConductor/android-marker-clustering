package com.mapconductor.marker.clustering

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.geocell.HexGeocell
import com.mapconductor.core.geocell.HexGeocellInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.AbstractMarkerRenderingStrategy
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.ColorDefaultIcon
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerFingerPrint
import com.mapconductor.core.marker.MarkerIconInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.Spherical
import com.mapconductor.core.spherical.expandBounds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MarkerClusterStrategy(
    private val clusterRadiusPx: Double = DEFAULT_CLUSTER_RADIUS_PX,
    private val minClusterSize: Int = DEFAULT_MIN_CLUSTER_SIZE,
    private val expandMargin: Double = DEFAULT_EXPAND_MARGIN,
    private val clusterIconProvider: (Int) -> MarkerIconInterface = DEFAULT_ICON_PROVIDER,
    private val clusterIconProviderWithTurn: ((Int, Int) -> MarkerIconInterface)? = null,
    private val onClusterClick: ((MarkerCluster) -> Unit)? = null,
    /**
     * Called before newly appearing individual (non-cluster) markers are
     * rendered — e.g. when a cluster expands after a zoom. Applying the new
     * cluster state is deferred until this suspend function returns, so the
     * app can preload marker icon images (and show a loading indicator)
     * before the markers pop in. A newer recluster supersedes any pending
     * deferred apply.
     */
    private val prepareExpand: (suspend (List<MarkerState>) -> Unit)? = null,
    /**
     * At or above this zoom, clicking a cluster fans its members out around
     * the (kept) cluster marker, connected by leg polylines — useful when
     * multiple markers share the same location and can never be separated by
     * zooming. Clicking the same cluster again, or any recluster (camera
     * move / data change), collapses the fan. Below this zoom the click
     * falls through to [onClusterClick]. Null disables the feature.
     */
    private val spiderfyMinZoom: Double? = null,
    /** Marker diameter in px used by the overlap-avoiding spiderfy layout. */
    private val spiderfyMarkerSizePx: Double = DEFAULT_SPIDERFY_MARKER_SIZE_PX,
    /** Extra gap between fanned-out markers in px. */
    private val spiderfyMarkerMarginPx: Double = DEFAULT_SPIDERFY_MARKER_MARGIN_PX,
    /**
     * Called when a spiderfy fan opens (true) or collapses (false) — e.g. to
     * close an info bubble when the user clicks another cluster or the fan
     * is dismissed by a camera move. Invoked on the main thread.
     */
    private val onSpiderfyChange: ((Boolean) -> Unit)? = null,
    private val enableZoomAnimation: Boolean = false,
    private val enablePanAnimation: Boolean = false,
    private val zoomAnimationDurationMillis: Long = DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    @Suppress("UNUSED_PARAMETER")
    debugHullPolygons: Boolean = false,
    private val cameraIdleDebounceMillis: Long = DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    private val tileSize: Double = DEFAULT_TILE_SIZE,
    semaphore: Semaphore = Semaphore(3),
    private val geocell: HexGeocellInterface = HexGeocell.defaultGeocell(),
) : AbstractMarkerRenderingStrategy<Any>(semaphore) {
    override val markerManager: MarkerManager<Any> = MarkerManager(geocell, 0)
    private val sourceStates = ConcurrentHashMap<String, MarkerState>()

    // Full marker fingerprints of the source states. MarkerState.equals() is
    // value-based, so comparing an in-place mutated state against itself always
    // reports "unchanged" and no recluster would ever be triggered by
    // `markerState.position = …`. Comparing fingerprints catches it.
    private val sourceFingerprints = ConcurrentHashMap<String, MarkerFingerPrint>()
    private val sourceStateVersion = AtomicLong(0)
    private var lastCameraPosition: MapCameraPosition? = null
    private var lastKnownViewport: GeoRectBounds? = null
    private var lastKnownViewportZoom: Double? = null
    private var clusteringTurn = 0
    private var lastZoomKey: Int? = null
    private val debounceScope = CoroutineScope(Dispatchers.Default)
    private val cameraUpdateToken = AtomicLong(0)
    private var lastRenderer: MarkerOverlayRendererInterface<Any>? = null
    private var debounceJob: Job? = null

    @Volatile private var isRendering = false
    private val renderRequests = Channel<RenderRequest<Any>>(Channel.CONFLATED)
    private var renderWorker: Job? = null
    private var lastRenderCameraPosition: MapCameraPosition? = null
    private val _debugInfoFlow = MutableStateFlow<List<MarkerClusterDebugInfo>>(emptyList())
    val debugInfoFlow: StateFlow<List<MarkerClusterDebugInfo>> = _debugInfoFlow
    private var lastUsedViewport: GeoRectBounds? = null
    private val forceNextRender = AtomicBoolean(false)

    /**
     * Called synchronously before marker animations start, after cluster computation.
     * Set by [MarkerClusterGroup] to commit hull polygon updates before animations begin,
     * so polygon rendering and marker animation cannot race each other.
     */
    @Volatile
    var onBeforeAnimation: (suspend (List<MarkerClusterDebugInfo>) -> Unit)? = null

    private var lastClusterMemberCenters: Map<String, GeoPoint> = emptyMap()
    private var lastClusterPositions: Map<String, GeoPoint> = emptyMap()
    private var lastClusterAssignments: Map<String, String> = emptyMap()
    private var lastClusterCoverageBounds: GeoRectBounds? = null
    private var lastSourceStateVersion: Long = 0
    private var lastSourceFingerprints: Map<String, PositionFingerPrint> = emptyMap()
    private var renderCount = 0

    // ConcurrentHashMap: mutated by the render worker, also read from the
    // click (main) thread when collecting spiderfy obstacles.
    private val renderedMarkerEntities = ConcurrentHashMap<String, MarkerEntityInterface<Any>>()

    // ── Spiderfy (click-to-fan-out) state ─────────────────────────────────
    private val _spiderfyLegsFlow = MutableStateFlow<List<SpiderfyLeg>>(emptyList())

    /**
     * Leg polylines of the currently open spiderfy fan (empty when no fan is
     * open). [MarkerClusterGroup] observes this to draw the leg polylines.
     */
    val spiderfyLegsFlow: StateFlow<List<SpiderfyLeg>> = _spiderfyLegsFlow
    private val spiderfyMutex = Mutex()
    private val spiderfyToken = AtomicLong(0)

    @Volatile private var spiderfyClusterKey: String? = null
    private val spiderfyEntities = mutableListOf<MarkerEntityInterface<Any>>()

    override fun clear() {
        sourceStates.clear()
        sourceFingerprints.clear()
        sourceStateVersion.set(0)
        markerManager.clear()
        _debugInfoFlow.value = emptyList()
        lastClusterMemberCenters = emptyMap()
        lastClusterPositions = emptyMap()
        lastClusterAssignments = emptyMap()
        lastClusterCoverageBounds = null
        lastSourceStateVersion = 0
        lastSourceFingerprints = emptyMap()
        lastZoomKey = null
        clusteringTurn = 0
        renderCount = 0
        renderedMarkerEntities.clear()
        lastRenderCameraPosition = null
        lastKnownViewport = null
        lastKnownViewportZoom = null
        lastUsedViewport = null
        forceNextRender.set(false)
        spiderfyToken.incrementAndGet()
        spiderfyClusterKey = null
        spiderfyEntities.clear()
        _spiderfyLegsFlow.value = emptyList()
    }

    /**
     * Forces a full cluster recompute on the next render, bypassing the
     * coverage-bounds early-return. Used by [MarkerClusterGroup] to ensure
     * debug hull polygons reflect the current camera position immediately
     * when [debugHullPolygons][MarkerClusterGroupState.debugHullPolygons]
     * is enabled.
     */
    /**
     * True when this source marker has already been handed to the strategy.
     * [MarkerClusterGroup] gates update-handler callbacks on it so a marker the
     * strategy does not track is never forwarded.
     */
    fun hasSourceMarker(id: String): Boolean = sourceStates.containsKey(id)

    fun forceRender() {
        forceNextRender.set(true)
        val cameraPosition = lastCameraPosition ?: return
        val viewport = lastKnownViewport ?: lastUsedViewport ?: return
        val renderer = lastRenderer ?: return
        enqueueRender(cameraPosition, viewport, renderer, cameraUpdateToken.incrementAndGet())
    }

    override suspend fun onAdd(
        data: List<MarkerState>,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): Boolean {
        // `renderClusters()` iterates `sourceStates` on a background worker.
        // Guard mutations with the same semaphore to avoid ConcurrentModificationException.
        semaphore.withPermit {
            updateSourceStates(data)
        }
        val cameraPosition = lastCameraPosition ?: return true
        enqueueRender(cameraPosition, viewport, renderer, cameraUpdateToken.get())
        return true
    }

    override suspend fun onUpdate(
        state: MarkerState,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): Boolean {
        // Guard mutations with the same semaphore to avoid ConcurrentModificationException.
        semaphore.withPermit {
            val nextFingerPrint = state.fingerPrint()
            val prevFingerPrint = sourceFingerprints[state.id]
            sourceStates[state.id] = state
            sourceFingerprints[state.id] = nextFingerPrint
            if (prevFingerPrint != nextFingerPrint) {
                sourceStateVersion.incrementAndGet()
            }
        }
        val cameraPosition = lastCameraPosition ?: return true
        enqueueRender(cameraPosition, viewport, renderer, cameraUpdateToken.get())
        return true
    }

    override suspend fun onCameraChanged(
        cameraPosition: MapCameraPosition,
        renderer: MarkerOverlayRendererInterface<Any>,
    ) {
        lastCameraPosition = cameraPosition
        cameraPosition.visibleRegion?.bounds?.let {
            lastKnownViewport = it
            lastKnownViewportZoom = cameraPosition.zoom
        }
        lastRenderer = renderer
        val token = cameraUpdateToken.incrementAndGet()
        if (debounceJob?.isActive == true && !isRendering) {
            debounceJob?.cancel()
        }
        debounceJob =
            debounceScope.launch {
                if (cameraIdleDebounceMillis > 0) {
                    delay(cameraIdleDebounceMillis)
                }
                if (token != cameraUpdateToken.get()) return@launch
                val currentCamera = lastCameraPosition ?: return@launch
                // Use the viewport from the camera position if available, otherwise fall back to
                // the last known viewport scaled by the zoom delta. Some map providers (e.g. ArcGIS)
                // emit camera-change events with a null visibleRegion during animations; scaling the
                // last known viewport by 2^(zoomDelta) preserves the correct screen-space coverage
                // so that markers newly visible after a zoom-out are included in clustering.
                val viewport =
                    currentCamera.visibleRegion?.bounds
                        ?: estimateViewport(currentCamera.zoom, currentCamera.position)
                        ?: return@launch
                val currentRenderer = lastRenderer ?: return@launch
                enqueueRender(currentCamera, viewport, currentRenderer, token)
            }
    }

    private fun enqueueRender(
        cameraPosition: MapCameraPosition,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        if (renderWorker == null) {
            startRenderWorker()
        }
        renderRequests.trySend(
            RenderRequest(
                cameraPosition = cameraPosition,
                viewport = viewport,
                renderer = renderer,
                token = token,
            ),
        )
    }

    private fun startRenderWorker() {
        if (renderWorker != null) return
        renderWorker =
            debounceScope.launch {
                for (request in renderRequests) {
                    isRendering = true
                    try {
                        renderClusters(
                            cameraPosition = request.cameraPosition,
                            viewport = request.viewport,
                            renderer = request.renderer,
                            token = request.token,
                        )
                    } finally {
                        isRendering = false
                    }
                }
            }
    }

    private fun updateSourceStates(data: List<MarkerState>) {
        val nextIds = data.map { it.id }.toSet()
        val removedIds = sourceStates.keys - nextIds
        var changed = false
        removedIds.forEach {
            sourceStates.remove(it)
            sourceFingerprints.remove(it)
            changed = true
        }
        data.forEach { state ->
            val nextFingerPrint = state.fingerPrint()
            if (sourceFingerprints[state.id] != nextFingerPrint) {
                changed = true
            }
            sourceStates[state.id] = state
            sourceFingerprints[state.id] = nextFingerPrint
        }
        if (changed) {
            sourceStateVersion.incrementAndGet()
        }
    }

    private suspend fun renderClusters(
        cameraPosition: MapCameraPosition,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        semaphore.withPermit {
            if (token != cameraUpdateToken.get()) return@withPermit
            currentCoroutineContext().ensureActive()
            renderCount++
            val expandedBounds = expandBounds(viewport, expandMargin)
            val zoom = cameraPosition.zoom
            val effectiveRadiusPx = effectiveClusterRadiusPx(zoom)
            val zoomChange = updateClusteringTurn(zoom)
            val turn = zoomChange.turn
            val zoomChanged = zoomChange.zoomChanged
            val sourceStateVersionSnapshot = sourceStateVersion.get()
            val lastSourceStateVersionSnapshot = lastSourceStateVersion
            val lastSourceFingerprintsSnapshot = lastSourceFingerprints
            val currentFingerprints = mutableMapOf<String, PositionFingerPrint>()
            val stableSource = sourceStateVersionSnapshot == lastSourceStateVersionSnapshot
            val cameraMoved =
                lastRenderCameraPosition?.let { previous ->
                    hasCameraMoved(previous, cameraPosition)
                } ?: false
            val animateTransitions =
                (enableZoomAnimation && zoomChanged) ||
                    (enablePanAnimation && cameraMoved)
            val forced = forceNextRender.getAndSet(false)
            lastUsedViewport = viewport

            // Any recluster (camera move / data change) collapses an open
            // spiderfy fan and supersedes a pending fan open.
            spiderfyToken.incrementAndGet()
            spiderfyMutex.withLock {
                collapseSpiderfyLocked(renderer)
            }

            if (!forced &&
                !zoomChanged &&
                lastClusterCoverageBounds != null &&
                containsBounds(lastClusterCoverageBounds!!, expandedBounds) &&
                stableSource
            ) {
                lastRenderCameraPosition = cameraPosition
                return@withPermit
            }

            cleanupStaleMarkers(
                currentZoom = zoom,
                renderer = renderer,
                skipClusterRemoval = animateTransitions,
            )

            val debugInfos = mutableListOf<MarkerClusterDebugInfo>()
            val clusterMemberCenters = mutableMapOf<String, GeoPoint>()
            val clusterPositions = mutableMapOf<String, GeoPoint>()

            if (zoomChanged) {
                lastClusterAssignments = emptyMap()
            }

            val cachedMarkers = mutableListOf<MarkerState>()
            val newMarkers = mutableListOf<MarkerState>()

            fun containsInViewport(
                bounds: GeoRectBounds?,
                point: GeoPointInterface,
            ): Boolean {
                if (bounds == null || bounds.isEmpty) return false
                val sw = bounds.southWest ?: return false
                val ne = bounds.northEast ?: return false

                val wrappedPoint = GeoPoint.from(point).wrap()
                val wrappedSw = sw.wrap()
                val wrappedNe = ne.wrap()

                if (wrappedPoint.latitude !in wrappedSw.latitude..wrappedNe.latitude) return false

                val west = wrappedSw.longitude
                val east = wrappedNe.longitude

                // Normal case (no antimeridian crossing).
                if (west <= east) {
                    return wrappedPoint.longitude in west..east
                }

                // Antimeridian-crossing representation: `west > east`.
                //
                // GeoRectBounds prefers the minimal longitudinal arc, which is good for small viewports near the dateline.
                // But when zoomed far out (globe-like view), the *actual* visible region can exceed 180° and the minimal arc
                // becomes the complement, incorrectly excluding large portions of the screen (e.g. western Japan disappearing).
                //
                // Heuristic: at low zoom, treat dateline-crossing bounds as a "large span" and accept the complement range.
                val lowZoom = zoom <= 4.0
                return if (lowZoom) {
                    wrappedPoint.longitude in east..west
                } else {
                    wrappedPoint.longitude >= west || wrappedPoint.longitude <= east
                }
            }

            sourceStates.values.forEach { state ->
                currentCoroutineContext().ensureActive()
                if (!containsInViewport(expandedBounds, state.position)) return@forEach

                val fp = PositionFingerPrint.from(state.position)
                currentFingerprints[state.id] = fp
                val movedSinceLastRender = lastSourceFingerprintsSnapshot[state.id]?.let { it != fp } ?: true

                if (!zoomChanged &&
                    containsInViewport(lastClusterCoverageBounds, state.position) &&
                    lastClusterAssignments.containsKey(state.id) &&
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
                val clusterId = lastClusterAssignments[marker.id]
                if (clusterId != null && clusterId.startsWith("cluster_")) {
                    cachedClusterGroups.getOrPut(clusterId) { mutableListOf() }.add(marker)
                } else {
                    val key = clusterId ?: marker.id
                    cachedMarkerGroups.getOrPut(key) { mutableListOf() }.add(marker)
                }
            }

            val desiredMarkerStates = mutableListOf<MarkerState>()
            val clustered = mutableMapOf<ClusterCell, MutableList<MarkerState>>()
            newMarkers.forEach { state ->
                currentCoroutineContext().ensureActive()
                val (x, y) = projectToPixel(state.position, zoom, tileSize)
                val cell =
                    ClusterCell(
                        x = floor(x / effectiveRadiusPx).toInt(),
                        y = floor(y / effectiveRadiusPx).toInt(),
                    )
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
            val mergedClusters: List<MergedCluster> = mergeClusters(candidates, zoom, effectiveRadiusPx)

            val finalMergedClusters = mutableListOf<MergedCluster>()
            val usedCachedClusters = mutableSetOf<String>()

            mergedClusters.forEach { merged ->
                currentCoroutineContext().ensureActive()
                var mergedWithCached = false
                val newCenter = merged.center

                cachedClusterGroups.forEach { (cachedClusterId, cachedMembers) ->
                    if (mergedWithCached || cachedClusterId in usedCachedClusters) return@forEach
                    val cachedPosition = lastClusterPositions[cachedClusterId] ?: return@forEach
                    val metersPerPixelVal = metersPerPixel(newCenter, zoom, tileSize)
                    val thresholdMeters = effectiveRadiusPx * metersPerPixelVal
                    val distance = Spherical.computeDistanceBetween(newCenter, cachedPosition)
                    if (distance <= thresholdMeters) {
                        val combinedMembers = cachedMembers + merged.members
                        finalMergedClusters.add(
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
                    finalMergedClusters.add(merged)
                }
            }

            cachedClusterGroups.forEach { (cachedClusterId, cachedMembers) ->
                if (cachedClusterId in usedCachedClusters) return@forEach
                val cachedPosition = lastClusterPositions[cachedClusterId] ?: return@forEach
                finalMergedClusters.add(
                    MergedCluster(
                        center = cachedPosition,
                        members = cachedMembers,
                    ),
                )
            }

            cachedMarkerGroups.values.forEach { cachedMembers ->
                val center = cachedMembers.firstOrNull()?.position ?: return@forEach
                finalMergedClusters.add(
                    MergedCluster(
                        center = GeoPoint.from(center),
                        members = cachedMembers,
                    ),
                )
            }

            val coverageBounds = GeoRectBounds()
            val nextClusterAssignments = mutableMapOf<String, String>()

            finalMergedClusters.forEach { merged ->
                currentCoroutineContext().ensureActive()
                if (merged.members.size >= minClusterSize) {
                    // Compute centroid via convex-hull shoelace formula (in projected space).
                    // Degenerate hulls (all members at nearly the same point) fall back
                    // to the member average, so a same-venue cluster is rendered exactly
                    // at that venue rather than at the first member / a cached position.
                    val hull = convexHullProjected(merged.members)
                    val centroidProjected = polygonCentroidProjected(hull)
                    val centroid =
                        centroidProjected?.let { p ->
                            geocell.projection.unproject(Offset(p.x.toFloat(), p.y.toFloat())).wrap()
                        }

                    // The rendered center is recomputed from the CURRENT members on
                    // every recluster (camera idle). Membership-stable pans therefore
                    // yield the identical centroid — no flicker — while membership
                    // changes move the cluster to its true center instead of freezing
                    // it at a stale cached position.
                    val center = GeoPoint.from(centroid ?: averagePosition(merged.members))
                    val (cx, cy) = projectToPixel(center, zoom, tileSize)
                    val cell =
                        ClusterCell(
                            x = floor(cx / effectiveRadiusPx).toInt(),
                            y = floor(cy / effectiveRadiusPx).toInt(),
                        )
                    val clusterId = buildClusterId(cell, zoom)
                    val radiusMeters = calculateClusterRadiusMeters(center, merged.members)
                    val cluster =
                        MarkerCluster(
                            count = merged.members.size,
                            markerIds = merged.members.map { it.id },
                        )
                    debugInfos.add(
                        MarkerClusterDebugInfo(
                            id = clusterId,
                            center = center,
                            radiusMeters = radiusMeters,
                            count = merged.members.size,
                            cellX = cell.x,
                            cellY = cell.y,
                            hullPoints =
                                if (hull.size >= 3) {
                                    hull.map { p ->
                                        geocell.projection.unproject(Offset(p.x.toFloat(), p.y.toFloat())).wrap()
                                    }
                                } else {
                                    emptyList()
                                },
                        ),
                    )
                    merged.members.forEach { member ->
                        clusterMemberCenters[member.id] = center
                        nextClusterAssignments[member.id] = clusterId
                    }
                    clusterPositions[clusterId] = center
                    extendCoverageBounds(coverageBounds, center, radiusMeters)
                    val clusterIcon =
                        clusterIconProviderWithTurn?.invoke(merged.members.size, turn)
                            ?: clusterIconProvider(merged.members.size)
                    // Cluster clicks first try spiderfy (when configured and
                    // zoomed in enough), then fall through to onClusterClick.
                    val clusterClickable = onClusterClick != null || spiderfyMinZoom != null
                    val clusterState =
                        MarkerState(
                            id = clusterId,
                            position = center,
                            extra = cluster,
                            icon = clusterIcon,
                            clickable = clusterClickable,
                            draggable = false,
                            onClick =
                                if (clusterClickable) {
                                    {
                                        if (!tryToggleSpiderfy(cluster)) {
                                            onClusterClick?.invoke(cluster)
                                        }
                                    }
                                } else {
                                    null
                                },
                        )
                    desiredMarkerStates.add(clusterState)
                } else {
                    merged.members.forEach { member ->
                        coverageBounds.extend(member.position)
                        nextClusterAssignments[member.id] = member.id
                    }
                    desiredMarkerStates.addAll(merged.members)
                }
            }

            if (token != cameraUpdateToken.get()) return@withPermit
            _debugInfoFlow.value = debugInfos

            // Keep the current (clustered) rendering on screen until the app
            // finishes preparing the newly appearing individual markers
            // (e.g. icon preloading). A newer camera update supersedes this
            // deferred apply via the token check below.
            val prepare = prepareExpand
            if (prepare != null) {
                val appearing =
                    desiredMarkerStates.filter { state ->
                        !state.id.startsWith("cluster_") && !renderedMarkerEntities.containsKey(state.id)
                    }
                if (appearing.isNotEmpty()) {
                    try {
                        prepare(appearing)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // A failed prepare must not block rendering.
                    }
                    if (token != cameraUpdateToken.get()) return@withPermit
                }
            }

            val previousClusterMemberCenters = lastClusterMemberCenters
            val previousClusterPositions = lastClusterPositions
            // Commit hull polygon updates synchronously before animation starts,
            // so polygon rendering and marker animation cannot race each other.
            onBeforeAnimation?.invoke(debugInfos)
            updateRenderedMarkers(
                desiredStates = desiredMarkerStates,
                renderer = renderer,
                token = token,
                animateTransitions = animateTransitions,
                previousClusterMemberCenters = previousClusterMemberCenters,
                nextClusterMemberCenters = clusterMemberCenters,
                previousClusterPositions = previousClusterPositions,
                nextClusterPositions = clusterPositions,
            )
            lastClusterMemberCenters = clusterMemberCenters
            lastClusterPositions = clusterPositions
            lastClusterAssignments = nextClusterAssignments
            lastRenderCameraPosition = cameraPosition
            lastClusterCoverageBounds = if (coverageBounds.isEmpty) null else coverageBounds
            lastSourceStateVersion = sourceStateVersionSnapshot
            lastSourceFingerprints = currentFingerprints
        }
    }

    private suspend fun updateRenderedMarkers(
        desiredStates: List<MarkerState>,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
        animateTransitions: Boolean,
        previousClusterMemberCenters: Map<String, GeoPoint>,
        nextClusterMemberCenters: Map<String, GeoPoint>,
        previousClusterPositions: Map<String, GeoPoint>,
        nextClusterPositions: Map<String, GeoPoint>,
    ) {
        val desiredById = desiredStates.associateBy { it.id }
        val animateZoom = animateTransitions && zoomAnimationDurationMillis > 0L
        val existing = markerManager.allEntities()
        val existingById = existing.associateBy { it.state.id }

        if (!animateZoom) {
            val orphanedIds = existingById.keys - desiredById.keys
            val orphanedEntitiesBeforeAnimation =
                orphanedIds.mapNotNull { id ->
                    renderedMarkerEntities[id]
                }
            if (orphanedEntitiesBeforeAnimation.isNotEmpty()) {
                renderer.onRemove(orphanedEntitiesBeforeAnimation)
                orphanedEntitiesBeforeAnimation.forEach { entity ->
                    renderedMarkerEntities.remove(entity.state.id)
                    markerManager.removeEntity(entity.state.id)
                }
                renderer.onPostProcess()
            }
        }

        val existingAfterCleanup = markerManager.allEntities()
        val existingByIdAfterCleanup = existingAfterCleanup.associateBy { it.state.id }

        val removeIds = existingByIdAfterCleanup.keys - desiredById.keys
        val addStates = desiredById.filterKeys { it !in existingByIdAfterCleanup }.values
        val updateStates = desiredById.filterKeys { it in existingByIdAfterCleanup }.values

        val animatedRemoveEntries =
            if (animateZoom) {
                removeIds.mapNotNull { id ->
                    val entity = existingByIdAfterCleanup[id] ?: return@mapNotNull null
                    val isCluster = id.startsWith("cluster_")

                    val target =
                        if (isCluster) {
                            val cluster = entity.state.extra as? MarkerCluster
                            val memberIds = cluster?.markerIds ?: emptyList()
                            if (memberIds.isEmpty()) return@mapNotNull null
                            val memberTargets =
                                memberIds.mapNotNull { memberId ->
                                    nextClusterMemberCenters[memberId]
                                }
                            if (memberTargets.isEmpty()) return@mapNotNull null
                            averageGeoPoints(memberTargets)
                        } else {
                            nextClusterMemberCenters[id] ?: return@mapNotNull null
                        }
                    AnimatedRemove(entity = entity, target = target)
                }
            } else {
                emptyList()
            }
        val animatedRemoveIds = animatedRemoveEntries.map { it.entity.state.id }.toSet()

        val animatedAddEntries =
            if (animateZoom) {
                addStates.mapNotNull { state ->
                    val isCluster = state.id.startsWith("cluster_")

                    val start =
                        if (isCluster) {
                            val cluster = state.extra as? MarkerCluster
                            val memberIds = cluster?.markerIds ?: emptyList()
                            if (memberIds.isEmpty()) return@mapNotNull null
                            val memberStarts =
                                memberIds.mapNotNull { memberId ->
                                    previousClusterMemberCenters[memberId]
                                }
                            if (memberStarts.isEmpty()) return@mapNotNull null
                            averageGeoPoints(memberStarts)
                        } else {
                            previousClusterMemberCenters[state.id] ?: return@mapNotNull null
                        }
                    AnimatedAdd(state = state, start = start)
                }
            } else {
                emptyList()
            }
        val animatedAddIds = animatedAddEntries.map { it.state.id }.toSet()

        val immediateRemoveIds = removeIds - animatedRemoveIds
        val immediateAddStates = addStates.filterNot { it.id in animatedAddIds }

        var didImmediateChange = false
        if (immediateRemoveIds.isNotEmpty()) {
            val removedEntities =
                immediateRemoveIds.mapNotNull { id ->
                    renderedMarkerEntities[id]
                }
            if (removedEntities.isNotEmpty()) {
                renderer.onRemove(removedEntities)
                removedEntities.forEach { entity ->
                    renderedMarkerEntities.remove(entity.state.id)
                    markerManager.removeEntity(entity.state.id)
                }
                didImmediateChange = true
            }
        }
        if (immediateAddStates.isNotEmpty()) {
            addStatesToRenderer(immediateAddStates, renderer)
            didImmediateChange = true
        }

        val changeParams = mutableListOf<MarkerOverlayRendererInterface.ChangeParamsInterface<Any>>()
        val changeEntities = mutableListOf<MarkerEntityInterface<Any>>()

        updateStates.forEach { state ->
            val prev = existingByIdAfterCleanup[state.id] ?: return@forEach
            val nextEntity: MarkerEntityInterface<Any> =
                MarkerEntity(
                    marker = prev.marker,
                    state = state,
                    isRendered = true,
                )
            markerManager.registerEntity(nextEntity)

            if (prev.fingerPrint == state.fingerPrint()) {
                return@forEach
            }

            val change =
                object : MarkerOverlayRendererInterface.ChangeParamsInterface<Any> {
                    override val current: MarkerEntityInterface<Any> = nextEntity
                    override val prev: MarkerEntityInterface<Any> = prev
                    override val bitmapIcon =
                        state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                }
            changeParams.add(change)
            changeEntities.add(nextEntity)
        }

        if (changeParams.isNotEmpty()) {
            val Anys = renderer.onChange(changeParams)
            Anys.forEachIndexed { index, Any ->
                Any?.let {
                    val entity: MarkerEntityInterface<Any> =
                        MarkerEntity(
                            marker = it as Any,
                            state = changeEntities[index].state,
                            isRendered = true,
                        )
                    markerManager.registerEntity(entity)
                    renderedMarkerEntities[entity.state.id] = entity
                }
            }
            didImmediateChange = true
        }

        if (didImmediateChange) {
            renderer.onPostProcess()
        }

        if (!animateZoom || (animatedRemoveEntries.isEmpty() && animatedAddEntries.isEmpty())) {
            return
        }
        if (token != cameraUpdateToken.get()) return

        val animatedStartEntities =
            if (animatedAddEntries.isNotEmpty()) {
                val animatedStartStates =
                    animatedAddEntries.map { entry ->
                        entry.state.copy(position = entry.start)
                    }
                val added = addStatesToRenderer(animatedStartStates, renderer)
                renderer.onPostProcess()
                added
            } else {
                emptyList()
            }

        val moves = mutableListOf<AnimatedMove<Any>>()
        animatedAddEntries.forEach { entry ->
            val entity = markerManager.getEntity(entry.state.id) ?: return@forEach
            moves.add(
                AnimatedMove(
                    id = entry.state.id,
                    start = entry.start,
                    end = entry.state.position,
                    baseState = entry.state,
                    entity = entity,
                ),
            )
        }
        animatedRemoveEntries.forEach { entry ->
            moves.add(
                AnimatedMove(
                    id = entry.entity.state.id,
                    start = entry.entity.state.position,
                    end = entry.target,
                    baseState = entry.entity.state,
                    entity = entry.entity,
                ),
            )
        }

        val completed = animateMarkerMoves(moves, renderer, zoomAnimationDurationMillis, token)

        if (animatedRemoveEntries.isNotEmpty()) {
            val entitiesToRemove =
                animatedRemoveEntries
                    .map { entry -> entry.entity }
                    .filter { entity -> renderedMarkerEntities.containsKey(entity.state.id) }
            if (entitiesToRemove.isNotEmpty()) {
                renderer.onRemove(entitiesToRemove)
                entitiesToRemove.forEach { entity ->
                    renderedMarkerEntities.remove(entity.state.id)
                    markerManager.removeEntity(entity.state.id)
                }
                renderer.onPostProcess()
            }
        }

        if (!completed) {
            if (animatedStartEntities.isNotEmpty()) {
                val entitiesToRemoveOnCancel =
                    animatedStartEntities.filter { entity ->
                        renderedMarkerEntities.containsKey(entity.state.id)
                    }
                if (entitiesToRemoveOnCancel.isNotEmpty()) {
                    renderer.onRemove(entitiesToRemoveOnCancel)
                    entitiesToRemoveOnCancel.forEach { entity ->
                        renderedMarkerEntities.remove(entity.state.id)
                        markerManager.removeEntity(entity.state.id)
                    }
                    renderer.onPostProcess()
                }
            }
        }
    }

    private suspend fun addStatesToRenderer(
        states: List<MarkerState>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): List<MarkerEntityInterface<Any>> {
        if (states.isEmpty()) return emptyList()
        val addedEntities = mutableListOf<MarkerEntityInterface<Any>>()
        val addParams =
            states.map { state ->
                object : MarkerOverlayRendererInterface.AddParamsInterface {
                    override val state: MarkerState = state
                    override val bitmapIcon =
                        state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                }
            }
        val Anys = renderer.onAdd(addParams)
        Anys.forEachIndexed { index, Any ->
            val marker = Any ?: return@forEachIndexed
            val entity: MarkerEntityInterface<Any> =
                MarkerEntity(
                    marker = marker as Any,
                    state = addParams[index].state,
                    isRendered = true,
                )
            markerManager.registerEntity(entity)
            renderedMarkerEntities[entity.state.id] = entity
            addedEntities.add(entity)
        }
        return addedEntities
    }

    private suspend fun animateMarkerMoves(
        moves: MutableList<AnimatedMove<Any>>,
        renderer: MarkerOverlayRendererInterface<Any>,
        durationMillis: Long,
        token: Long,
    ): Boolean {
        if (moves.isEmpty()) return true
        val frameMillis = animationFrameMillis(moves.size)
        val steps =
            max(
                1,
                ((durationMillis + frameMillis - 1L) / frameMillis).toInt(),
            )
        val stepDelayMillis =
            if (steps <= 1) {
                durationMillis
            } else {
                max(1L, durationMillis / steps.toLong())
            }

        val bitmapIcons =
            moves.map { move ->
                move.baseState.icon?.toBitmapIcon() ?: defaultMarkerIcon
            }
        val nextEntities = arrayOfNulls<MarkerEntityInterface<Any>>(moves.size)
        val changeParams =
            ArrayList<MutableChangeParams<Any>>(moves.size).apply {
                moves.forEachIndexed { index, move ->
                    add(
                        MutableChangeParams(
                            current = move.entity,
                            prev = move.entity,
                            bitmapIcon = bitmapIcons[index],
                        ),
                    )
                }
            }

        for (step in 1..steps) {
            if (token != cameraUpdateToken.get()) return false
            currentCoroutineContext().ensureActive()
            val t = step.toDouble() / steps.toDouble()
            moves.forEachIndexed { index, move ->
                val position = interpolatePosition(move.start, move.end, t)
                val prevEntity = move.entity
                val nextState = move.baseState.copy(position = position)
                val nextEntity =
                    MarkerEntity(
                        marker = prevEntity.marker,
                        state = nextState,
                        isRendered = true,
                    )
                nextEntities[index] = nextEntity
                changeParams[index].prev = prevEntity
                changeParams[index].current = nextEntity
            }

            val Anys = renderer.onChange(changeParams)
            Anys.forEachIndexed { index, Any ->
                val nextEntity = nextEntities[index] ?: return@forEachIndexed
                val fallbackMarker = nextEntity.marker
                nextEntity.marker = Any ?: fallbackMarker
                markerManager.updateEntity(nextEntity)
                renderedMarkerEntities[nextEntity.state.id] = nextEntity
                moves[index].entity = nextEntity
            }
            renderer.onPostProcess()

            if (step < steps) {
                delay(stepDelayMillis)
            }
        }
        return true
    }

    private fun interpolatePosition(
        start: GeoPointInterface,
        end: GeoPointInterface,
        t: Double,
    ): GeoPoint {
        val startAlt = start.altitude ?: 0.0
        val endAlt = end.altitude ?: 0.0
        return GeoPoint(
            latitude = start.latitude + (end.latitude - start.latitude) * t,
            longitude = start.longitude + (end.longitude - start.longitude) * t,
            altitude = startAlt + (endAlt - startAlt) * t,
        )
    }

    private fun averagePosition(states: List<MarkerState>): GeoPoint {
        var sumLat = 0.0
        var sumLon = 0.0
        states.forEach { state ->
            sumLat += state.position.latitude
            sumLon += state.position.longitude
        }
        val count = states.size.coerceAtLeast(1)
        return GeoPoint.fromLatLong(
            latitude = sumLat / count,
            longitude = sumLon / count,
        )
    }

    private fun averageGeoPoints(points: List<GeoPoint>): GeoPoint {
        if (points.isEmpty()) return GeoPoint.fromLatLong(0.0, 0.0)
        var sumLat = 0.0
        var sumLon = 0.0
        points.forEach { point ->
            sumLat += point.latitude
            sumLon += point.longitude
        }
        val count = points.size
        return GeoPoint.fromLatLong(
            latitude = sumLat / count,
            longitude = sumLon / count,
        )
    }

    private suspend fun cleanupStaleMarkers(
        currentZoom: Double,
        renderer: MarkerOverlayRendererInterface<Any>,
        skipClusterRemoval: Boolean,
    ) {
        val currentZoomKey = currentZoom.roundToInt()
        val staleEntities = mutableListOf<MarkerEntityInterface<Any>>()

        renderedMarkerEntities.values.forEach { entity ->
            val id = entity.state.id
            val isCluster = id.startsWith("cluster_")

            val isStale =
                if (isCluster) {
                    if (skipClusterRemoval) {
                        false
                    } else {
                        val parts = id.split("_")
                        if (parts.size >= 4) {
                            val markerZoomKey = parts[1].toIntOrNull() ?: -1
                            markerZoomKey != currentZoomKey
                        } else {
                            false
                        }
                    }
                } else {
                    !sourceStates.containsKey(id)
                }

            if (isStale) {
                staleEntities.add(entity)
            }
        }

        if (staleEntities.isNotEmpty()) {
            renderer.onRemove(staleEntities)
            staleEntities.forEach { entity ->
                renderedMarkerEntities.remove(entity.state.id)
                markerManager.removeEntity(entity.state.id)
            }
            renderer.onPostProcess()
        }
    }

    private suspend fun cleanupOrphanedMarkers(
        desiredStates: List<MarkerState>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ) {
        val desiredIds = desiredStates.map { it.id }.toSet()
        val orphanedEntities =
            renderedMarkerEntities.values.filter { entity ->
                entity.state.id !in desiredIds
            }

        if (orphanedEntities.isNotEmpty()) {
            renderer.onRemove(orphanedEntities)
            orphanedEntities.forEach { entity ->
                renderedMarkerEntities.remove(entity.state.id)
                markerManager.removeEntity(entity.state.id)
            }
            renderer.onPostProcess()
        }
    }

    // ── Spiderfy (click-to-fan-out) ───────────────────────────────────────

    /**
     * Handles a click on a cluster marker. Returns true when the click was
     * consumed by spiderfy (toggling the fan), false when it should fall
     * through to [onClusterClick]. Runs on the caller (main) thread; the
     * actual rendering is performed asynchronously.
     */
    private fun tryToggleSpiderfy(cluster: MarkerCluster): Boolean {
        val minZoom = spiderfyMinZoom ?: return false
        val camera = lastCameraPosition ?: return false
        if (camera.zoom < minZoom) return false
        val renderer = lastRenderer ?: return false
        val holder = renderer.holder

        val clusterKey = cluster.markerIds.sorted().joinToString(",")
        if (spiderfyClusterKey == clusterKey) {
            // Clicking the open cluster again collapses the fan.
            spiderfyToken.incrementAndGet()
            debounceScope.launch {
                spiderfyMutex.withLock {
                    collapseSpiderfyLocked(renderer)
                }
            }
            return true
        }

        val members = cluster.markerIds.mapNotNull { sourceStates[it] }
        if (members.isEmpty()) return false

        // Fan out around the cluster marker's actual rendered position (it can
        // deviate from the member average), so the legs meet the marker's base.
        var centerGeo = averagePosition(members)
        renderedMarkerEntities.values.firstOrNull { it.state.extra === cluster }?.let { entity ->
            centerGeo = GeoPoint.from(entity.state.position)
        }
        val centerPx = holder.toScreenOffset(centerGeo) ?: return false

        // Already rendered output markers (other clusters / individual markers)
        // near the fan act as fixed obstacles so the fanned members do not
        // overlap them. The clicked cluster itself (at the center) is excluded;
        // instead the head of a pin-shaped cluster icon is added as a pseudo
        // obstacle above the center.
        val obstacles = mutableListOf<Offset>()
        renderedMarkerEntities.values.forEach { entity ->
            val px = holder.toScreenOffset(entity.state.position) ?: return@forEach
            val relX = px.x - centerPx.x
            val relY = px.y - centerPx.y
            val distance = hypot(relX.toDouble(), relY.toDouble())
            // Ignore the clicked cluster itself and anything too far away.
            if (distance < 2.0 || distance > 300.0) return@forEach
            obstacles.add(Offset(relX, relY))
        }
        obstacles.add(Offset(0f, -(spiderfyMarkerSizePx / 2.0).roundToInt().toFloat()))

        val offsets = spiderfyLayout(members.size, spiderfyMarkerSizePx, spiderfyMarkerMarginPx, obstacles)
        val token = spiderfyToken.incrementAndGet()
        debounceScope.launch {
            spiderfyMutex.withLock {
                collapseSpiderfyLocked(renderer)
            }
            if (token != spiderfyToken.get()) return@launch
            openSpiderfy(
                clusterKey = clusterKey,
                members = members,
                centerGeo = centerGeo,
                centerPx = centerPx,
                offsets = offsets,
                renderer = renderer,
                token = token,
            )
        }
        return true
    }

    private suspend fun openSpiderfy(
        clusterKey: String,
        members: List<MarkerState>,
        centerGeo: GeoPoint,
        centerPx: Offset,
        offsets: List<Offset>,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        val holder = renderer.holder
        val clones = mutableListOf<MarkerState>()
        val legs = mutableListOf<SpiderfyLeg>()
        withContext(Dispatchers.Main) {
            members.forEachIndexed { index, member ->
                val screen =
                    Offset(
                        x = centerPx.x + offsets[index].x,
                        y = centerPx.y + offsets[index].y,
                    )
                val geo =
                    holder.fromScreenOffsetSync(screen)
                        ?: holder.fromScreenOffset(screen)
                        ?: return@forEachIndexed
                clones.add(member.copy(id = "spider_${member.id}", position = geo, zIndex = 2000))
                legs.add(SpiderfyLeg(id = "spiderleg_${member.id}", start = centerGeo, end = geo))
            }
        }
        if (clones.isEmpty()) return

        // Keep the cluster rendering unchanged until the app has prepared the
        // fanned-out markers (e.g. icon preloading). A newer toggle/recluster
        // supersedes this open via the token check below.
        val prepare = prepareExpand
        if (prepare != null) {
            try {
                prepare(clones)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed prepare must not block rendering.
            }
        }

        spiderfyMutex.withLock {
            if (token != spiderfyToken.get()) return
            val addParams =
                clones.map { state ->
                    object : MarkerOverlayRendererInterface.AddParamsInterface {
                        override val state: MarkerState = state
                        override val bitmapIcon =
                            state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                    }
                }
            val actualMarkers = renderer.onAdd(addParams)
            actualMarkers.forEachIndexed { index, actual ->
                val marker = actual ?: return@forEachIndexed
                val entity: MarkerEntityInterface<Any> =
                    MarkerEntity(
                        marker = marker,
                        state = addParams[index].state,
                        isRendered = true,
                    )
                markerManager.registerEntity(entity)
                spiderfyEntities.add(entity)
            }
            renderer.onPostProcess()
            _spiderfyLegsFlow.value = legs
            spiderfyClusterKey = clusterKey
        }
        withContext(Dispatchers.Main) {
            onSpiderfyChange?.invoke(true)
        }
    }

    /** Must be called while holding [spiderfyMutex]. */
    private suspend fun collapseSpiderfyLocked(renderer: MarkerOverlayRendererInterface<Any>) {
        if (spiderfyClusterKey == null && spiderfyEntities.isEmpty()) return
        spiderfyClusterKey = null
        _spiderfyLegsFlow.value = emptyList()
        if (spiderfyEntities.isNotEmpty()) {
            val entities = spiderfyEntities.toList()
            spiderfyEntities.clear()
            renderer.onRemove(entities)
            entities.forEach { entity ->
                markerManager.removeEntity(entity.state.id)
            }
            renderer.onPostProcess()
        }
        withContext(Dispatchers.Main) {
            onSpiderfyChange?.invoke(false)
        }
    }

    /**
     * Screen-space fan-out layout for spiderfy. Members start on an even circle
     * around the cluster and then iteratively repel each other (and the cluster
     * marker itself) until no pair is closer than markerSize + margin, while a
     * weak spring toward the center keeps the fan compact. Converges to a ring
     * for small counts and to packed shells for larger ones.
     */
    private fun spiderfyLayout(
        count: Int,
        markerSizePx: Double,
        marginPx: Double,
        obstacles: List<Offset>,
    ): List<Offset> {
        val desired = markerSizePx + marginPx
        // Base distance from the cluster center: far enough for the legs to be
        // visible, close enough for the fan to stay compact.
        val centerClearance = (markerSizePx * 1.3).roundToInt() + marginPx
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        for (i in 0 until count) {
            // Evenly spaced starting at 0 degrees (to the right); two members
            // end up side by side, avoiding the head of a pin-shaped cluster.
            val angle = 2.0 * PI * i / count
            xs[i] = cos(angle) * centerClearance
            ys[i] = sin(angle) * centerClearance
        }
        for (iteration in 0 until SPIDERFY_LAYOUT_MAX_ITERATIONS) {
            var maxMove = 0.0
            for (i in 0 until count) {
                var fx = 0.0
                var fy = 0.0
                // Repulsion between fanned-out members.
                for (j in 0 until count) {
                    if (i == j) continue
                    val dx = xs[i] - xs[j]
                    val dy = ys[i] - ys[j]
                    var d = hypot(dx, dy)
                    if (d == 0.0) d = 0.01
                    if (d < desired) {
                        val push = (desired - d) / 2.0
                        fx += dx / d * push
                        fy += dy / d * push
                    }
                }
                // Repulsion from already rendered markers nearby (fixed obstacles).
                for (obstacle in obstacles) {
                    val dx = xs[i] - obstacle.x
                    val dy = ys[i] - obstacle.y
                    var d = hypot(dx, dy)
                    if (d == 0.0) d = 0.01
                    if (d < desired) {
                        val push = desired - d
                        fx += dx / d * push
                        fy += dy / d * push
                    }
                }
                var dc = hypot(xs[i], ys[i])
                if (dc == 0.0) dc = 0.01
                if (dc < centerClearance) {
                    // Repulsion from the cluster marker itself.
                    val push = centerClearance - dc
                    fx += xs[i] / dc * push
                    fy += ys[i] / dc * push
                } else {
                    // Weak spring toward the center (prevents drifting too far).
                    val pull = (dc - centerClearance) * 0.15
                    fx -= xs[i] / dc * pull
                    fy -= ys[i] / dc * pull
                }
                xs[i] += fx * 0.6
                ys[i] += fy * 0.6
                maxMove = max(maxMove, max(abs(fx), abs(fy)))
            }
            if (maxMove < SPIDERFY_LAYOUT_CONVERGENCE_THRESHOLD) break
        }
        return List(count) { i -> Offset(xs[i].toFloat(), ys[i].toFloat()) }
    }

    private fun buildClusterId(
        cell: ClusterCell,
        zoom: Double,
    ): String = "cluster_${zoom.roundToInt()}_${cell.x}_${cell.y}"

    private fun containsBounds(
        container: GeoRectBounds,
        target: GeoRectBounds,
    ): Boolean {
        if (container.isEmpty || target.isEmpty) return false
        val sw = target.southWest ?: return false
        val ne = target.northEast ?: return false
        return container.contains(sw) && container.contains(ne)
    }

    private fun extendCoverageBounds(
        bounds: GeoRectBounds,
        center: GeoPoint,
        radiusMeters: Double,
    ) {
        val latPad = radiusMeters / Earth.RADIUS_METERS * (180.0 / Math.PI)
        val latRad = center.latitude * DEG_TO_RAD
        val cosLat = cos(latRad).coerceAtLeast(1e-6)
        val lonPad = (radiusMeters / (Earth.RADIUS_METERS * cosLat)) * (180.0 / Math.PI)
        bounds.extend(GeoPoint(center.latitude - latPad, center.longitude - lonPad))
        bounds.extend(GeoPoint(center.latitude + latPad, center.longitude + lonPad))
    }

    private fun projectToPixel(
        position: GeoPointInterface,
        zoom: Double,
        tileSize: Double,
    ): Pair<Double, Double> {
        val scale = tileSize * 2.0.pow(zoom)
        val sinLat = sin(position.latitude * DEG_TO_RAD).coerceIn(-MAX_SIN_LAT, MAX_SIN_LAT)
        val x = (position.longitude + 180.0) / 360.0 * scale
        val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * scale
        return Pair(x, y)
    }

    private fun updateClusteringTurn(zoom: Double): ZoomChange {
        val zoomKey = (zoom * 100).roundToInt()
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

    private fun hasCameraMoved(
        previous: MapCameraPosition,
        current: MapCameraPosition,
    ): Boolean {
        val distance = Spherical.computeDistanceBetween(previous.position, current.position)
        if (distance > PAN_ANIMATION_MIN_DISTANCE_METERS) return true
        if (abs(previous.bearing - current.bearing) > CAMERA_ANGLE_EPSILON) return true
        return abs(previous.tilt - current.tilt) > CAMERA_ANGLE_EPSILON
    }

    private data class ZoomChange(
        val turn: Int,
        val zoomChanged: Boolean,
    )

    private fun metersPerPixel(
        position: GeoPointInterface,
        zoom: Double,
        tileSize: Double,
    ): Double {
        val scale = tileSize * 2.0.pow(zoom)
        val latitudeRadians = position.latitude * DEG_TO_RAD
        return (Earth.CIRCUMFERENCE_METERS * cos(latitudeRadians)) / scale
    }

    private fun mergeClusters(
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

        // Greedy (seed-based) merge to avoid "chaining" merges:
        // only merge neighbors that are within the radius of the *seed* candidate.
        for (i in candidates.indices) {
            if (visited[i]) continue
            visited[i] = true

            val seed = candidates[i]
            val seedCenter = seed.center
            val seedMetersPerPixel = metersPerPixel(seedCenter, zoom, tileSize)

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
                    val neighborMetersPerPixel = metersPerPixel(neighborCenter, zoom, tileSize)
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

    private data class ClusterCandidate(
        val cell: ClusterCell,
        val center: GeoPoint,
        val members: MutableList<MarkerState>,
    )

    private data class MergedCluster(
        val center: GeoPoint,
        val members: List<MarkerState>,
    )

    private data class AnimatedAdd(
        val state: MarkerState,
        val start: GeoPoint,
    )

    private data class AnimatedRemove<Any>(
        val entity: MarkerEntityInterface<Any>,
        val target: GeoPoint,
    )

    private data class AnimatedMove<Any>(
        val id: String,
        val start: GeoPointInterface,
        val end: GeoPointInterface,
        val baseState: MarkerState,
        var entity: MarkerEntityInterface<Any>,
    )

    private data class RenderRequest<Any>(
        val cameraPosition: MapCameraPosition,
        val viewport: GeoRectBounds,
        val renderer: MarkerOverlayRendererInterface<Any>,
        val token: Long,
    )

    private class MutableChangeParams<Any>(
        override var current: MarkerEntityInterface<Any>,
        override var prev: MarkerEntityInterface<Any>,
        override val bitmapIcon: BitmapIcon,
    ) : MarkerOverlayRendererInterface.ChangeParamsInterface<Any>

    private fun animationFrameMillis(moveCount: Int): Long =
        when {
            moveCount < 50 -> ANIMATION_FRAME_MILLIS_60_FPS
            moveCount < 100 -> ANIMATION_FRAME_MILLIS_30_FPS
            moveCount < 300 -> ANIMATION_FRAME_MILLIS_8_FPS
            else -> ANIMATION_FRAME_MILLIS_4_FPS
        }

    private fun selectDenseCenter(
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
                val (x, y) = projectToPixel(member.position, zoom, tileSize)
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

    // Returns a viewport estimate for the given zoom level when the actual visibleRegion is
    // unavailable. Scales the last known viewport span by 2^(zoomDelta), but centers it on the
    // current camera position so ArcGIS null-visibleRegion updates still render the viewed area.
    private fun estimateViewport(
        zoom: Double,
        center: GeoPointInterface,
    ): GeoRectBounds? {
        val base = lastKnownViewport ?: return null
        val baseZoom = lastKnownViewportZoom ?: return null
        val sw = base.southWest ?: return base
        val ne = base.northEast ?: return base
        val zoomDelta = baseZoom - zoom
        val scale = 2.0.pow(zoomDelta)
        val centerPoint = GeoPoint.from(center).wrap()
        val halfLat = kotlin.math.abs(ne.latitude - sw.latitude) / 2.0 * scale
        val lonSpan = if (sw.longitude <= ne.longitude) {
            ne.longitude - sw.longitude
        } else {
            ne.longitude + 360.0 - sw.longitude
        }
        val halfLon = lonSpan.coerceIn(0.0, 360.0) / 2.0 * scale
        val result = GeoRectBounds()
        result.extend(
            GeoPoint(
                (centerPoint.latitude - halfLat).coerceIn(-90.0, 90.0),
                wrapLongitude(centerPoint.longitude - halfLon),
            ),
        )
        result.extend(
            GeoPoint(
                (centerPoint.latitude + halfLat).coerceIn(-90.0, 90.0),
                wrapLongitude(centerPoint.longitude + halfLon),
            ),
        )
        return result
    }

    private fun wrapLongitude(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0

    private fun effectiveClusterRadiusPx(zoom: Double): Double {
        // At low zoom levels, a fixed screen-space radius can represent hundreds of kilometers.
        // Reduce the effective radius so clusters don't look "too aggressive" when zoomed out.
        val referenceZoom = 10.0
        val minScale = 0.35
        val minRadiusPx = 18.0

        val scale = (zoom / referenceZoom).coerceIn(minScale, 1.0)
        return max(minRadiusPx, clusterRadiusPx * scale)
    }

    private fun calculateClusterRadiusMeters(
        center: GeoPoint,
        members: List<MarkerState>,
    ): Double {
        var maxDistance = 0.0
        members.forEach { state ->
            val distance = Spherical.computeDistanceBetween(center, state.position)
            if (distance > maxDistance) {
                maxDistance = distance
            }
        }
        return maxDistance
    }

    private data class HullPoint(
        val x: Double,
        val y: Double,
    )

    private fun convexHullProjected(members: List<MarkerState>): List<HullPoint> {
        if (members.size < 3) return emptyList()

        val points =
            members
                .map { state ->
                    val projected = geocell.projection.project(state.position)
                    HullPoint(projected.x.toDouble(), projected.y.toDouble())
                }.distinctBy { p ->
                    // Avoid degenerate duplicates due to float precision.
                    val rx = (p.x * 1e3).toLong()
                    val ry = (p.y * 1e3).toLong()
                    (rx shl 32) xor ry
                }.sortedWith(compareBy<HullPoint> { it.x }.thenBy { it.y })

        if (points.size < 3) return emptyList()

        fun cross(
            o: HullPoint,
            a: HullPoint,
            b: HullPoint,
        ): Double = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = mutableListOf<HullPoint>()
        for (p in points) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0.0) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(p)
        }

        val upper = mutableListOf<HullPoint>()
        for (p in points.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0.0) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(p)
        }

        // Remove the last point of each list (it's the starting point of the other list).
        val hull = (lower.dropLast(1) + upper.dropLast(1))
        return if (hull.size >= 3) hull else emptyList()
    }

    private fun polygonCentroidProjected(hull: List<HullPoint>): HullPoint? {
        if (hull.size < 3) return null

        // Shoelace formula centroid for a (non-self-intersecting) polygon.
        var twiceArea = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val cross = a.x * b.y - b.x * a.y
            twiceArea += cross
            cx += (a.x + b.x) * cross
            cy += (a.y + b.y) * cross
        }

        if (kotlin.math.abs(twiceArea) < 1e-6) {
            // Degenerate polygon: fallback to average.
            val ax = hull.sumOf { it.x } / hull.size
            val ay = hull.sumOf { it.y } / hull.size
            return HullPoint(ax, ay)
        }

        cx /= (3.0 * twiceArea)
        cy /= (3.0 * twiceArea)
        return HullPoint(cx, cy)
    }

    private data class ClusterCell(
        val x: Int,
        val y: Int,
    )

    private data class PixelPoint(
        val member: MarkerState,
        val x: Double,
        val y: Double,
    )

    private data class CellKey(
        val x: Int,
        val y: Int,
    )

    /**
     * Position-only fingerprint used to decide whether a marker moved since the
     * last render. Distinct from [MarkerFingerPrint], which covers the whole
     * marker and drives [sourceStateVersion].
     */
    private data class PositionFingerPrint(
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

    companion object {
        const val DEFAULT_CLUSTER_RADIUS_PX: Double = 90.0
        const val DEFAULT_MIN_CLUSTER_SIZE: Int = 3
        const val DEFAULT_EXPAND_MARGIN: Double = 0.2
        const val DEFAULT_TILE_SIZE: Double = 256.0
        const val DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS: Long = 300L
        const val DEFAULT_CAMERA_DEBOUNCE_MILLIS: Long = 100L
        const val DEFAULT_SPIDERFY_MARKER_SIZE_PX: Double = 52.0
        const val DEFAULT_SPIDERFY_MARKER_MARGIN_PX: Double = 8.0
        private const val SPIDERFY_LAYOUT_MAX_ITERATIONS: Int = 150
        private const val SPIDERFY_LAYOUT_CONVERGENCE_THRESHOLD: Double = 0.15
        private const val MAX_DENSE_CELLS: Int = 4
        private const val MAX_DENSE_CANDIDATES: Int = 50
        private const val PAN_ANIMATION_MIN_DISTANCE_METERS: Double = 1.0
        private const val CAMERA_ANGLE_EPSILON: Double = 1e-2
        private const val ANIMATION_FRAME_MILLIS_60_FPS: Long = 16L
        private const val ANIMATION_FRAME_MILLIS_30_FPS: Long = 33L
        private const val ANIMATION_FRAME_MILLIS_8_FPS: Long = 125L
        private const val ANIMATION_FRAME_MILLIS_4_FPS: Long = 250L
        val DEFAULT_ICON_PROVIDER: (Int) -> MarkerIconInterface =
            { count -> ColorDefaultIcon(label = count.toString()) }
        private const val DEG_TO_RAD: Double = Math.PI / 180.0
        private const val MAX_SIN_LAT: Double = 0.9999
    }
}
