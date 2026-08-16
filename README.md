# MapConductor Marker Clustering

## Description

MapConductor Marker Clustering provides a marker clustering strategy for the MapConductor SDK.
It automatically groups nearby markers into clusters as you zoom out, and expands them back into individual markers as you zoom in.
It works with any map implementation (Google Maps, MapLibre, Mapbox, ArcGIS, HERE, etc.).

## Setup

https://mapconductor.com/setup/

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
        clusterRadiusPx = 90.0,
        minClusterSize = 5,
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

## Wasm Acceleration

For datasets with a large number of markers (roughly 5,000+), the grid-assignment and greedy-merge steps can be offloaded to a pre-compiled WebAssembly module.
The Wasm module is written in Rust, compiled to `clustering_wasm.wasm`, and bundled in the library's assets.
It runs via [Chicory](https://github.com/dylibso/chicory) — a pure-Java Wasm interpreter — so **no NDK or JNI is required**.

### Enabling Wasm acceleration

Create a `ClusteringWasmEngine` once (e.g. in your `Application` or `ViewModel`) and pass it to `MarkerClusterStrategy`:

```kotlin
// Create once — loading and JIT-parsing the Wasm module takes ~tens of ms
val wasmEngine = ClusteringWasmEngine.create(context)

val strategy = MarkerClusterStrategy<ActualMarker>(
    wasmEngine = wasmEngine,
    // All other parameters work exactly as before
)
```

When `wasmEngine` is `null` (the default), the pure-Kotlin path is used and behaviour is unchanged.

### How it works

The Wasm module implements the computationally intensive parts of the clustering algorithm:

| Step | Kotlin (default) | Wasm (accelerated) |
|---|---|---|
| Lat/lon → pixel projection | ✓ | ✓ |
| Grid-cell assignment | ✓ | ✓ |
| Greedy neighbour merge | ✓ | ✓ |
| Dense-centre selection | ✓ | ✓ |
| Convex hull / centroid | ✓ | (Kotlin) |
| Camera debounce / cache | ✓ | ✓ |
| Zoom / pan animation | ✓ | ✓ |

The grid-assignment and merge loop run entirely inside the Wasm module.
Results are read back as flat `f64`/`i32` arrays via Chicory's memory API.

### Rebuilding the Wasm module

The compiled `.wasm` binary is committed to the repository so a Rust toolchain is **not** required for normal builds.
If you modify `clustering-wasm/src/lib.rs`, rebuild with:

```bash
./gradlew :android-marker-clustering:buildClusteringWasm
```

This requires [Rust](https://rustup.rs) with the `wasm32-unknown-unknown` target:

```bash
rustup target add wasm32-unknown-unknown
```

After the task completes, commit the updated `src/main/assets/clustering_wasm.wasm`.

------------------------------------------------------------------------

## API Reference

### MarkerClusterGroupState

| Property | Type | Default | Description |
|---|---|---|---|
| `clusterRadiusPx` | `Double` | `90.0` | Pixel radius within which markers are clustered |
| `minClusterSize` | `Int` | `5` | Minimum number of markers to form a cluster |
| `expandMargin` | `Double` | `0.2` | Extra viewport margin when determining which markers to (re-)cluster |
| `clusterIconProvider` | `(Int) -> MarkerIconInterface` | Default circle badge | Returns an icon for the given cluster size |
| `onClusterClick` | `((MarkerCluster) -> Unit)?` | `null` | Called when a cluster marker is tapped |
| `enableZoomAnimation` | `Boolean` | `false` | Animate markers when zoom level changes |
| `enablePanAnimation` | `Boolean` | `false` | Animate markers when the camera pans |
| `zoomAnimationDurationMillis` | `Long` | `300` | Duration of the zoom transition animation |
| `cameraIdleDebounceMillis` | `Long` | `100` | Delay before re-clustering after the camera stops moving |

### ClusteringWasmEngine

| Member | Description |
|---|---|
| `ClusteringWasmEngine.create(context)` | Loads `clustering_wasm.wasm` from assets and instantiates the Wasm module. Call once and reuse. |
| `computeClusters(markers, zoom, clusterRadiusPx, tileSize)` | Internal — called by `MarkerClusterStrategy` when a `wasmEngine` is provided. |

### MarkerClusterStrategy (advanced)

`MarkerClusterStrategy` accepts an optional `wasmEngine` parameter in addition to all parameters exposed by `MarkerClusterGroupState`:

```kotlin
MarkerClusterStrategy<ActualMarker>(
    clusterRadiusPx = 90.0,
    minClusterSize = 5,
    wasmEngine = ClusteringWasmEngine.create(context), // optional
)
```
