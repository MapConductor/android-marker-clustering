# MarkerCluster

`MarkerCluster` is a data class that represents a group of markers that have been collapsed into a
single cluster point on the map. It holds the count of grouped markers and their IDs.

## Signature

```kotlin
data class MarkerCluster(
    val count: Int,
    val markerIds: List<String>,
) : Serializable
```

## Properties

- `count`
    - Type: `Int`
    - Description: The total number of markers contained in this cluster.
- `markerIds`
    - Type: `List<String>`
    - Description: The list of IDs of the individual markers that belong to this cluster.

## Example

```kotlin
// A MarkerCluster is typically received via the onClusterClick callback.
MarkerClusterGroup(
    onClusterClick = { cluster: MarkerCluster ->
        println("Tapped a cluster with ${cluster.count} markers")
        println("Marker IDs: ${cluster.markerIds}")
    },
    markers = markerStates,
)
```

---

# MarkerClusterDebugInfo

`MarkerClusterDebugInfo` is a data class that holds diagnostic information about a computed cluster.
It is used to inspect the internal state of the clustering algorithm and is typically exposed via
`MarkerClusterStrategy.debugInfoFlow`.

## Signature

```kotlin
data class MarkerClusterDebugInfo(
    val id: String,
    val center: GeoPointInterface,
    val radiusMeters: Double,
    val count: Int,
    val cellX: Int,
    val cellY: Int,
    val hullPoints: List<GeoPointInterface> = emptyList(),
)
```

## Properties

- `id`
    - Type: `String`
    - Description: A unique identifier for this cluster, derived from its grid cell and zoom level.
- `center`
    - Type: `GeoPointInterface`
    - Description: The geographic center point of the cluster.
- `radiusMeters`
    - Type: `Double`
    - Description: The radius of the cluster in meters, calculated as the maximum distance from the
      center to any member marker.
- `count`
    - Type: `Int`
    - Description: The number of markers in this cluster.
- `cellX`
    - Type: `Int`
    - Description: The X coordinate of the grid cell that this cluster occupies.
- `cellY`
    - Type: `Int`
    - Description: The Y coordinate of the grid cell that this cluster occupies.
- `hullPoints`
    - Type: `List<GeoPointInterface>`
    - Default: `emptyList()`
    - Description: The vertices of the convex hull polygon surrounding the cluster members.
      Only populated when `debugHullPolygons` is enabled on `MarkerClusterGroupState`.
