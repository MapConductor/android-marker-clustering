package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.geocell.HexGeocell
import com.mapconductor.core.marker.MarkerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * クラスタ中心（凸包の面積重心）が、ごく近いマーカー同士でもずれないことを確かめる。
 * 投影座標の絶対値のまま外積を取っていた頃は、数 m 間隔の 4 点で重心が数十 km 飛んでいた。
 * react-sdk の ClusterGeometry.test.mjs と同じ配置で見る。
 */
class ClusterGeometryCentroidTest {
    private val geometry = ClusterGeometry(HexGeocell.defaultGeocell())

    @Test
    fun centroidOfMarkersAFewMetersApartStaysAtTheirCenter() {
        val lat = 35.681236
        val lng = 139.767125
        val d = 0.00002
        val members =
            listOf(
                MarkerState(GeoPoint.fromLatLong(lat, lng), id = "a"),
                MarkerState(GeoPoint.fromLatLong(lat + d, lng), id = "b"),
                MarkerState(GeoPoint.fromLatLong(lat, lng + d), id = "c"),
                MarkerState(GeoPoint.fromLatLong(lat + d, lng + d), id = "d"),
            )
        val hull = geometry.convexHullProjected(members)
        assertEquals(4, hull.size)
        val centroid = geometry.polygonCentroidProjected(hull)
        assertNotNull(centroid)
        val center = geometry.unproject(centroid!!)
        assertEquals(lat + d / 2, center.latitude, 1e-8)
        assertEquals(lng + d / 2, center.longitude, 1e-8)
    }
}
