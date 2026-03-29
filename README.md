# MapConductor Marker Clustering

## Description

MapConductor Marker Clustering provides a marker clustering strategy for the MapConductor SDK.
It automatically groups nearby markers into clusters as you zoom out, and expands them back into individual markers as you zoom in.
It works with any map implementation (Google Maps, MapLibre, Mapbox, ArcGIS, HERE, etc.).

## Setup

https://docs-android.mapconductor.com/setup/

------------------------------------------------------------------------

## Usage

### Basic MarkerClusterGroup

Wrap your markers with `MarkerClusterGroup` inside any `XxxMapView` content block:

```kotlin
val markerStates: List<MarkerState> = remember { buildMarkerList() }

XxxMapView(...) {
    MarkerClusterGroup<ActualMarker>(
        state = remember { MarkerClusterGroupState() },
        markers = markerStates,
    )
}
```

> Replace `ActualMarker` with the map-specific marker type of the implementation you are using
> (e.g. `Marker` for Google Maps, `Symbol` for MapLibre).

### MarkerClusterGroupState

Use `MarkerClusterGroupState` to configure clustering behaviour:

```kotlin
val clusterState = remember {
    MarkerClusterGroupState<ActualMarker>(
        clusterRadiusPx = 50.0,
        minClusterSize = 2,
        onClusterClick = { cluster ->
            // handle cluster tap
        },
    )
}

XxxMapView(...) {
    MarkerClusterGroup(
        state = clusterState,
        markers = markerStates,
    )
}
```

### Custom Cluster Icon

Provide a lambda to `clusterIconProvider` to render a custom icon for each cluster:

```kotlin
val clusterState = remember {
    MarkerClusterGroupState<ActualMarker>(
        clusterIconProvider = { count ->
            DefaultMarkerIcon(
                fillColor = Color.Blue,
                label = count.toString(),
                labelTextColor = Color.White,
            )
        },
    )
}
```

### Composable content inside a cluster group

Add extra overlays alongside the clustered markers using the `content` lambda:

```kotlin
XxxMapView(...) {
    MarkerClusterGroup(
        state = clusterState,
        markers = markerStates,
    ) {
        // additional composables rendered alongside the cluster group
        Circle(circleState)
    }
}
```

------------------------------------------------------------------------

## API Reference

### MarkerClusterGroupState

| Property | Type | Default | Description |
|---|---|---|---|
| `clusterRadiusPx` | `Double` | `50.0` | Pixel radius within which markers are clustered |
| `minClusterSize` | `Int` | `2` | Minimum number of markers to form a cluster |
| `expandMargin` | `Double` | — | Extra margin when expanding clusters |
| `clusterIconProvider` | `(Int) -> MarkerIconInterface` | Default circle badge | Returns an icon for the given cluster size |
| `onClusterClick` | `((MarkerCluster) -> Unit)?` | `null` | Called when a cluster marker is tapped |
| `enableZoomAnimation` | `Boolean` | `false` | Animate markers on zoom change |
| `enablePanAnimation` | `Boolean` | `false` | Animate markers on pan |
| `zoomAnimationDurationMillis` | `Long` | — | Duration of zoom animation |
| `cameraIdleDebounceMillis` | `Long` | — | Debounce delay before re-clustering after camera stops |
