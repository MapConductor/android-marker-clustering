# MarkerClusterGroup

`MarkerClusterGroup` is a Jetpack Compose composable that groups nearby markers into cluster icons
on the map. It uses `MarkerClusterStrategy` internally to compute clusters based on screen-space
proximity whenever the camera moves.

There are four overloads:

- **State-based + markers list** — supply a `MarkerClusterGroupState` and a `List<MarkerState>`.
- **State-based + content lambda** — supply a `MarkerClusterGroupState` and declare markers inside
  a composable `content` block.
- **Stateless + markers list** — pass all clustering options inline along with a
  `List<MarkerState>`. A `MarkerClusterGroupState` is created and remembered internally.
- **Stateless + content lambda** — pass all clustering options inline and declare markers inside a
  composable `content` block.

All overloads are extension functions on `MapViewScope` and must be called from within a map view
composable such as `GoogleMapView`.

---

## Overload 1 — State-based with markers list

### Signature

```kotlin
@Composable
fun <ActualMarker> MapViewScope.MarkerClusterGroup(
    state: MarkerClusterGroupState<ActualMarker>,
    markers: List<MarkerState>,
    content: @Composable () -> Unit = {},
)
```

### Description

Renders the given list of markers with clustering applied, using the provided `state` for
configuration. An optional `content` lambda can declare additional map overlays inside the cluster
group scope.

### Parameters

- `state`
    - Type: `MarkerClusterGroupState<ActualMarker>`
    - Description: **Required.** The state object that controls clustering behavior. Use
      `MarkerClusterGroupState` to configure and modify clustering at runtime.
- `markers`
    - Type: `List<MarkerState>`
    - Description: **Required.** The list of markers to cluster.
- `content`
    - Type: `@Composable () -> Unit`
    - Default: `{}`
    - Description: An optional composable block for declaring additional map overlays inside the
      cluster group.

---

## Overload 2 — State-based with content lambda

### Signature

```kotlin
@Composable
fun <ActualMarker> MapViewScope.MarkerClusterGroup(
    state: MarkerClusterGroupState<ActualMarker>,
    content: @Composable () -> Unit,
)
```

### Description

Renders all markers declared inside `content` with clustering applied, using the provided `state`
for configuration. Use `Markers(list)` inside the `content` block to add markers declaratively.

### Parameters

- `state`
    - Type: `MarkerClusterGroupState<ActualMarker>`
    - Description: **Required.** The state object that controls clustering behavior.
- `content`
    - Type: `@Composable () -> Unit`
    - Description: **Required.** A composable block in which markers and other overlays are declared.

---

## Overload 3 — Stateless with markers list

### Signature

```kotlin
@Composable
fun <ActualMarker> MapViewScope.MarkerClusterGroup(
    clusterRadiusPx: Double = MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX,
    minClusterSize: Int = MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE,
    expandMargin: Double = MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN,
    clusterIconProvider: (Int) -> MarkerIconInterface = MarkerClusterStrategy.DEFAULT_ICON_PROVIDER,
    onClusterClick: ((MarkerCluster) -> Unit)? = null,
    clusterRadiusStrokeColor: Color = Color.Red,
    clusterRadiusStrokeWidth: Dp = 1.dp,
    clusterRadiusFillColor: Color = Color.Transparent,
    enableZoomAnimation: Boolean = false,
    enablePanAnimation: Boolean = false,
    zoomAnimationDurationMillis: Long = MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    debugIncludeRenderCount: Boolean = false,
    debugHullPolygons: Boolean = false,
    debugHullStrokeWidth: Dp = 2.dp,
    debugHullStrokeAlpha: Float = 0.8f,
    debugHullFillAlpha: Float = 0.18f,
    cameraIdleDebounceMillis: Long = MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    tileSize: Double = MarkerClusterStrategy.DEFAULT_TILE_SIZE,
    markers: List<MarkerState>,
    content: @Composable () -> Unit = {},
)
```

### Description

A convenience overload that creates and remembers a `MarkerClusterGroupState` internally from the
provided parameters. Use this when you don't need to hold or mutate the state externally.

### Parameters

- `clusterRadiusPx`
    - Type: `Double`
    - Default: `MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX` (`90.0`)
    - Description: The clustering radius in screen pixels.
- `minClusterSize`
    - Type: `Int`
    - Default: `MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE` (`5`)
    - Description: The minimum number of markers required to form a cluster.
- `expandMargin`
    - Type: `Double`
    - Default: `MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN` (`0.2`)
    - Description: Fractional margin applied to the viewport when querying markers to cluster.
- `clusterIconProvider`
    - Type: `(Int) -> MarkerIconInterface`
    - Default: `MarkerClusterStrategy.DEFAULT_ICON_PROVIDER`
    - Description: Factory function that returns the icon for a cluster of the given count.
- `onClusterClick`
    - Type: `((MarkerCluster) -> Unit)?`
    - Default: `null`
    - Description: Callback invoked when the user taps a cluster marker.
- `clusterRadiusStrokeColor`
    - Type: `Color`
    - Default: `Color.Red`
    - Description: Stroke color for the cluster radius debug circle.
- `clusterRadiusStrokeWidth`
    - Type: `Dp`
    - Default: `1.dp`
    - Description: Stroke width for the cluster radius debug circle.
- `clusterRadiusFillColor`
    - Type: `Color`
    - Default: `Color.Transparent`
    - Description: Fill color for the cluster radius debug circle.
- `enableZoomAnimation`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, markers animate to/from their cluster center on zoom changes.
- `enablePanAnimation`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, markers animate when the camera pans.
- `zoomAnimationDurationMillis`
    - Type: `Long`
    - Default: `MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS` (`300`)
    - Description: Duration in milliseconds for zoom animations.
- `debugIncludeRenderCount`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, appends the internal render count to cluster labels for debugging.
- `debugHullPolygons`
    - Type: `Boolean`
    - Default: `false`
    - Description: If `true`, draws convex hull polygons around each cluster on the map.
- `debugHullStrokeWidth`
    - Type: `Dp`
    - Default: `2.dp`
    - Description: Stroke width of the debug hull polygon outlines.
- `debugHullStrokeAlpha`
    - Type: `Float`
    - Default: `0.8f`
    - Description: Opacity of the debug hull polygon stroke.
- `debugHullFillAlpha`
    - Type: `Float`
    - Default: `0.18f`
    - Description: Opacity of the debug hull polygon fill.
- `cameraIdleDebounceMillis`
    - Type: `Long`
    - Default: `MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS` (`100`)
    - Description: Debounce delay in milliseconds before re-clustering after a camera change.
- `tileSize`
    - Type: `Double`
    - Default: `MarkerClusterStrategy.DEFAULT_TILE_SIZE` (`256.0`)
    - Description: Tile size in pixels used for the internal Mercator projection.
- `markers`
    - Type: `List<MarkerState>`
    - Description: **Required.** The list of markers to cluster.
- `content`
    - Type: `@Composable () -> Unit`
    - Default: `{}`
    - Description: An optional composable block for additional map overlays.

---

## Overload 4 — Stateless with content lambda

### Signature

```kotlin
@Composable
fun <ActualMarker> MapViewScope.MarkerClusterGroup(
    clusterRadiusPx: Double = MarkerClusterStrategy.DEFAULT_CLUSTER_RADIUS_PX,
    minClusterSize: Int = MarkerClusterStrategy.DEFAULT_MIN_CLUSTER_SIZE,
    expandMargin: Double = MarkerClusterStrategy.DEFAULT_EXPAND_MARGIN,
    clusterIconProvider: (Int) -> MarkerIconInterface = MarkerClusterStrategy.DEFAULT_ICON_PROVIDER,
    onClusterClick: ((MarkerCluster) -> Unit)? = null,
    clusterRadiusStrokeColor: Color = Color.Red,
    clusterRadiusStrokeWidth: Dp = 1.dp,
    clusterRadiusFillColor: Color = Color.Transparent,
    enableZoomAnimation: Boolean = false,
    enablePanAnimation: Boolean = false,
    zoomAnimationDurationMillis: Long = MarkerClusterStrategy.DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    debugIncludeRenderCount: Boolean = false,
    debugHullPolygons: Boolean = false,
    debugHullStrokeWidth: Dp = 2.dp,
    debugHullStrokeAlpha: Float = 0.8f,
    debugHullFillAlpha: Float = 0.18f,
    cameraIdleDebounceMillis: Long = MarkerClusterStrategy.DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    tileSize: Double = MarkerClusterStrategy.DEFAULT_TILE_SIZE,
    content: @Composable () -> Unit,
)
```

### Description

A convenience overload that creates and remembers a `MarkerClusterGroupState` internally. Markers
and other overlays are declared inside the `content` lambda.

### Parameters

Parameters are identical to [Overload 3](#overload-3--stateless-with-markers-list), except:

- `content`
    - Type: `@Composable () -> Unit`
    - Description: **Required.** A composable block in which markers and other overlays are declared.

---

## Example

### Using the state-based overload

```kotlin
val clusterState = remember {
    MarkerClusterGroupState<GoogleMapActualMarker>(
        clusterRadiusPx = 100.0,
        minClusterSize = 3,
        onClusterClick = { cluster ->
            println("Cluster tapped: ${cluster.count} markers")
        },
        enableZoomAnimation = true,
    )
}

// Replace "MapView" with semantic SDK mapview such as "GoogleMapView"
MapView(state = mapViewState) {
    MarkerClusterGroup(state = clusterState, markers = markerStates)
}
```

### Using the stateless overload

```kotlin
// Replace "MapView" with semantic SDK mapview such as "GoogleMapView"
MapView(state = mapViewState) {
    MarkerClusterGroup<GoogleMapActualMarker>(
        clusterRadiusPx = 80.0,
        minClusterSize = 5,
        onClusterClick = { cluster ->
            println("${cluster.count} markers in cluster")
        },
        markers = markerStates,
    )
}
```
