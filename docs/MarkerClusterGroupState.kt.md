# MarkerClusterGroupState

`MarkerClusterGroupState` is a state holder for the `MarkerClusterGroup` composable. It holds all
configuration for the clustering behavior and debug visualization. Because all properties are backed
by `mutableStateOf`, changing any property at runtime will automatically trigger a re-cluster.

Use `MarkerClusterGroupState` when you need to read or update clustering parameters programmatically
after composition. For simpler use cases, you can pass parameters directly to the stateless
`MarkerClusterGroup` overload.

## Signature

```kotlin
class MarkerClusterGroupState<ActualMarker>(
    clusterRadiusPx: Double = MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX,
    minClusterSize: Int = MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE,
    expandMargin: Double = MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN,
    clusterIconProvider: (Int) -> MarkerIconInterface = MarkerClusterStrategy.DEFAULT_ICON_PROVIDER,
    onClusterClick: ((MarkerCluster) -> Unit)? = null,
    debugClusterTurnLabel: Boolean = false,
    enableZoomAnimation: Boolean = false,
    enablePanAnimation: Boolean = false,
    zoomAnimationDurationMillis: Long = MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    debugHullPolygons: Boolean = false,
    debugHullStrokeWidth: Dp = 2.dp,
    debugHullStrokeAlpha: Float = 0.8f,
    debugHullFillAlpha: Float = 0.18f,
    cameraIdleDebounceMillis: Long = MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    tileSize: Double = MarkerClusterStrategy.DEFAULT_TILE_SIZE,
)
```

## Constructor Parameters

- `clusterRadiusPx`
    - Type: `Double`
    - Default: `MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX` (`90.0`)
    - Description: The clustering radius in screen pixels. Markers within this distance are grouped
      into a single cluster.
- `minClusterSize`
    - Type: `Int`
    - Default: `MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE` (`5`)
    - Description: The minimum number of markers required to form a cluster. Groups smaller than
      this are rendered as individual markers.
- `expandMargin`
    - Type: `Double`
    - Default: `MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN` (`0.2`)
    - Description: A fractional margin applied to the visible viewport when querying which markers
      to cluster. A value of `0.2` expands the viewport by 20% on each side.
- `clusterIconProvider`
    - Type: `(Int) -> MarkerIconInterface`
    - Default: `MarkerClusterStrategy.DEFAULT_ICON_PROVIDER`
    - Description: A factory function that receives the cluster count and returns the icon to display
      for the cluster marker. Defaults to a colored label icon showing the count.
- `onClusterClick`
    - Type: `((MarkerCluster) -> Unit)?`
    - Default: `null`
    - Description: An optional callback invoked when the user taps a cluster marker. Receives the
      `MarkerCluster` data for the tapped cluster.
- `debugClusterTurnLabel`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, appends the internal clustering turn number to the cluster icon label.
      Useful for diagnosing re-clustering behavior.
- `enableZoomAnimation`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, markers animate to/from their cluster center when the zoom level changes.
- `enablePanAnimation`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, markers animate when the camera pans.
- `zoomAnimationDurationMillis`
    - Type: `Long`
    - Default: `MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS` (`300`)
    - Description: Duration in milliseconds for the zoom animation.
- `debugHullPolygons`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, draws convex hull polygons around each cluster on the map for
      visualization. Each cell is assigned a distinct color.
- `debugHullStrokeWidth`
    - Type: `Dp`
    - Default: `2.dp`
    - Description: The stroke width of the debug hull polygon outlines.
- `debugHullStrokeAlpha`
    - Type: `Float`
    - Default: `0.8f`
    - Description: The opacity of the debug hull polygon stroke.
- `debugHullFillAlpha`
    - Type: `Float`
    - Default: `0.18f`
    - Description: The opacity of the debug hull polygon fill.
- `cameraIdleDebounceMillis`
    - Type: `Long`
    - Default: `MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS` (`100`)
    - Description: The debounce delay in milliseconds after a camera change before re-clustering is
      triggered. Reduces unnecessary clustering work during fast camera movements.
- `tileSize`
    - Type: `Double`
    - Default: `MarkerClusterStrategy.DEFAULT_TILE_SIZE` (`256.0`)
    - Description: The tile size in pixels used for the internal Mercator projection. Should
      generally match the tile size of the underlying map provider.

## Properties

All constructor parameters are exposed as mutable `var` properties backed by `mutableStateOf`.
Assigning a new value to any property causes `MarkerClusterGroup` to automatically re-cluster.

- `clusterRadiusPx` — Type: `Double`
- `minClusterSize` — Type: `Int`
- `expandMargin` — Type: `Double`
- `clusterIconProvider` — Type: `(Int) -> MarkerIconInterface`
- `onClusterClick` — Type: `((MarkerCluster) -> Unit)?`
- `debugClusterTurnLabel` — Type: `Boolean`
- `enableZoomAnimation` — Type: `Boolean`
- `enablePanAnimation` — Type: `Boolean`
- `zoomAnimationDurationMillis` — Type: `Long`
- `debugHullPolygons` — Type: `Boolean`
- `debugHullStrokeWidth` — Type: `Dp`
- `debugHullStrokeAlpha` — Type: `Float`
- `debugHullFillAlpha` — Type: `Float`
- `cameraIdleDebounceMillis` — Type: `Long`
- `tileSize` — Type: `Double`

## Example

```kotlin
// Create state to allow runtime modification of clustering behavior
val clusterState = remember {
    MarkerClusterGroupState<GoogleMapActualMarker>(
        clusterRadiusPx = 120.0,
        minClusterSize = 3,
        onClusterClick = { cluster ->
            println("Cluster tapped: ${cluster.count} markers")
        },
        enableZoomAnimation = true,
    )
}

// Use in a composable map view
// Replace "MapView" with semantic SDK mapview such as "GoogleMapView"
MapView(state = mapState) {
    MarkerClusterGroup(state = clusterState, markers = markerStates)
}

// Modify clustering behavior at runtime — triggers automatic re-cluster
Button(onClick = { clusterState.clusterRadiusPx = 60.0 }) {
    Text("Tighten clusters")
}
```
