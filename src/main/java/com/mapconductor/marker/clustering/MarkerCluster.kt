package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPointInterface
import java.io.Serializable

data class MarkerCluster(
    val count: Int,
    val markerIds: List<String>,
) : Serializable

data class MarkerClusterDebugInfo(
    val id: String,
    val center: GeoPointInterface,
    val radiusMeters: Double,
    val count: Int,
    val cellX: Int,
    val cellY: Int,
    val hullPoints: List<GeoPointInterface> = emptyList(),
)

/**
 * A leg polyline of an open spiderfy fan, connecting the cluster marker
 * ([start]) to one fanned-out member marker ([end]).
 */
data class SpiderfyLeg(
    val id: String,
    val start: GeoPointInterface,
    val end: GeoPointInterface,
)
