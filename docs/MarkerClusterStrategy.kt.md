# MarkerClusterStrategy

`MarkerClusterStrategy` is the internal clustering engine used by `MarkerClusterGroup`. It extends
`AbstractMarkerRenderingStrategy` and implements the logic for grouping markers into clusters based
on their screen-space proximity at the current zoom level.

The strategy runs on a background coroutine and debounces camera events to avoid excessive
re-clustering during continuous camera movement. It also supports optional zoom and pan animations
for smooth marker transitions.

In most cases you do not instantiate `MarkerClusterStrategy` directly — it is created internally by
`MarkerClusterGroup`. Interact with it through `MarkerClusterGroupState` or the `debugInfoFlow`
property when you need diagnostic access.

## Signature

```kotlin
class MarkerClusterStrategy<ActualMarker>(
    private val clusterRadiusPx: Double = DEFAULT_CLUSTER_RADIUS_PX,
    private val minClusterSize: Int = DEFAULT_MIN_CLUSTER_SIZE,
    private val expandMargin: Double = DEFAULT_EXPAND_MARGIN,
    private val clusterIconProvider: (Int) -> MarkerIconInterface = DEFAULT_ICON_PROVIDER,
    private val onClusterClick: ((MarkerCluster) -> Unit)? = null,
    private val enableZoomAnimation: Boolean = false,
    private val enablePanAnimation: Boolean = false,
    private val zoomAnimationDurationMillis: Long = DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    private val debugHullPolygons: Boolean = false,
    private val cameraIdleDebounceMillis: Long = DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    private val tileSize: Double = DEFAULT_TILE_SIZE,
) : AbstractMarkerRenderingStrategy<ActualMarker>()
```

## Properties

- `debugInfoFlow`
    - Type: `StateFlow<List<MarkerClusterDebugInfo>>`
    - Description: A read-only flow that emits the list of `MarkerClusterDebugInfo` objects after
      each clustering pass. Useful for observing cluster positions, sizes, and hull points at
      runtime. Only emits non-empty hull data when `debugHullPolygons` is `true`.

## Companion Object — Default Constants

- `DEFAULT_CLUSTER_RADIUS_PX`
    - Type: `Double`
    - Value: `90.0`
    - Description: The default clustering radius in screen pixels.
- `DEFAULT_MIN_CLUSTER_SIZE`
    - Type: `Int`
    - Value: `5`
    - Description: The default minimum number of markers required to form a cluster.
- `DEFAULT_EXPAND_MARGIN`
    - Type: `Double`
    - Value: `0.2`
    - Description: The default fractional margin applied to the viewport when querying markers.
- `DEFAULT_TILE_SIZE`
    - Type: `Double`
    - Value: `256.0`
    - Description: The default tile size in pixels used for the internal Mercator projection.
- `DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS`
    - Type: `Long`
    - Value: `300`
    - Description: The default duration in milliseconds for zoom transition animations.
- `DEFAULT_CAMERA_DEBOUNCE_MILLIS`
    - Type: `Long`
    - Value: `100`
    - Description: The default debounce delay in milliseconds before re-clustering after a camera
      change.
- `DEFAULT_ICON_PROVIDER`
    - Type: `(Int) -> MarkerIconInterface`
    - Value: `{ count -> ColorDefaultIcon(label = count.toString()) }`
    - Description: The default cluster icon factory. Renders a colored label icon showing the
      marker count.

## Example

```kotlin
// Observing debug cluster info from a strategy exposed via MarkerClusterGroup state
val clusterState = remember {
    MarkerClusterGroupState<GoogleMapActualMarker>(
        debugHullPolygons = true,
    )
}

// Collect debugInfoFlow from the strategy after it is wired up internally
LaunchedEffect(Unit) {
    // debugInfoFlow is accessible if you hold a reference to the strategy directly;
    // for most use cases, debug visualization is handled automatically by
    // MarkerClusterGroup when debugHullPolygons = true on the state.
}

// Replace "MapView" with semantic SDK mapview such as "GoogleMapView"
MapView(state = mapViewState) {
    MarkerClusterGroup(state = clusterState, markers = markerStates)
}
```
