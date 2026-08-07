package com.mapconductor.marker.clustering

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.geocell.HexGeocellInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.projection.Earth
import com.mapconductor.core.spherical.Spherical
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * クラスタリングが使う投影・境界・平均・凸包の計算。
 *
 * ここにあるのは**すべて副作用のない計算**で、状態を持たない
 * （[geocell] は投影関数を借りるためだけに受け取る）。
 * [MarkerClusterStrategy] / [ClusterPlanner] / [ClusterMarkerAnimator] から
 * 共有され、単体で差し替え・検証ができる。
 *
 * ios-sdk の `ClusterGeometry.swift` / react-sdk の `ClusterGeometry.ts` と
 * 同じ関数を同じ名前で持つ。片方だけ直すと 3 者の描画結果がずれるので、
 * 式を変えるときは必ず 3 つとも直すこと。
 */
internal class ClusterGeometry(
    private val geocell: HexGeocellInterface,
) {
    /** Web メルカトルのピクセル座標へ投影する。 */
    fun projectToPixel(
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

    fun metersPerPixel(
        position: GeoPointInterface,
        zoom: Double,
        tileSize: Double,
    ): Double {
        val scale = tileSize * 2.0.pow(zoom)
        val latitudeRadians = position.latitude * DEG_TO_RAD
        return (Earth.CIRCUMFERENCE_METERS * cos(latitudeRadians)) / scale
    }

    fun wrapLongitude(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0

    fun containsBounds(
        container: GeoRectBounds,
        target: GeoRectBounds,
    ): Boolean {
        if (container.isEmpty || target.isEmpty) return false
        val sw = target.southWest ?: return false
        val ne = target.northEast ?: return false
        return container.contains(sw) && container.contains(ne)
    }

    /**
     * 日付変更線をまたぐ表現に対応した内外判定。
     *
     * [GeoRectBounds] は経度方向の最小の弧を選ぶ。日付変更線近くの小さなビューポートでは
     * それが正しいが、大きく引いた（地球儀のような）表示では実際の可視範囲が 180 度を超え、
     * 最小の弧が補集合になってしまう（西日本が消える等）。
     * そのため低ズームでは「またいでいる境界＝広い範囲」とみなして補集合側を採る。
     */
    fun containsInViewport(
        bounds: GeoRectBounds?,
        point: GeoPointInterface,
        zoom: Double,
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

        val lowZoom = zoom <= LOW_ZOOM_THRESHOLD
        return if (lowZoom) {
            wrappedPoint.longitude in east..west
        } else {
            wrappedPoint.longitude >= west || wrappedPoint.longitude <= east
        }
    }

    fun extendCoverageBounds(
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

    /**
     * 実際の visibleRegion が取れないときのビューポート推定。
     *
     * 直前のビューポートの広さを 2^(zoomDelta) で伸縮し、現在のカメラ位置を中心に置き直す。
     * ArcGIS はアニメーション中に visibleRegion が null のカメラ更新を出すため、
     * これが無いとズームアウト後に見えるようになったマーカーがクラスタリングに入らない。
     */
    fun estimateViewport(
        zoom: Double,
        center: GeoPointInterface,
        lastKnownViewport: GeoRectBounds?,
        lastKnownViewportZoom: Double?,
    ): GeoRectBounds? {
        val base = lastKnownViewport ?: return null
        val baseZoom = lastKnownViewportZoom ?: return null
        val sw = base.southWest ?: return base
        val ne = base.northEast ?: return base
        val zoomDelta = baseZoom - zoom
        val scale = 2.0.pow(zoomDelta)
        val centerPoint = GeoPoint.from(center).wrap()
        val halfLat = abs(ne.latitude - sw.latitude) / 2.0 * scale
        val lonSpan =
            if (sw.longitude <= ne.longitude) {
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

    fun hasCameraMoved(
        previous: MapCameraPosition,
        current: MapCameraPosition,
    ): Boolean {
        val distance = Spherical.computeDistanceBetween(previous.position, current.position)
        if (distance > PAN_ANIMATION_MIN_DISTANCE_METERS) return true
        if (abs(previous.bearing - current.bearing) > CAMERA_ANGLE_EPSILON) return true
        return abs(previous.tilt - current.tilt) > CAMERA_ANGLE_EPSILON
    }

    fun interpolatePosition(
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

    fun averagePosition(states: List<MarkerState>): GeoPoint {
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

    fun averageGeoPoints(points: List<GeoPoint>): GeoPoint {
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

    fun calculateClusterRadiusMeters(
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

    /** 投影座標の凸包（Andrew's monotone chain）。3 点未満に潰れる場合は空を返す。 */
    fun convexHullProjected(members: List<MarkerState>): List<HullPoint> {
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

    /** 靴ひも公式による多角形重心。面積が潰れている場合は頂点平均へ落とす。 */
    fun polygonCentroidProjected(hull: List<HullPoint>): HullPoint? {
        if (hull.size < 3) return null

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

        if (abs(twiceArea) < 1e-6) {
            // Degenerate polygon: fallback to average.
            val ax = hull.sumOf { it.x } / hull.size
            val ay = hull.sumOf { it.y } / hull.size
            return HullPoint(ax, ay)
        }

        cx /= (3.0 * twiceArea)
        cy /= (3.0 * twiceArea)
        return HullPoint(cx, cy)
    }

    /** 投影座標を地理座標へ戻す。凸包・重心の結果を地図上へ載せるときに使う。 */
    fun unproject(point: HullPoint): GeoPoint =
        GeoPoint.from(geocell.projection.unproject(Offset(point.x.toFloat(), point.y.toFloat())).wrap())

    internal data class HullPoint(
        val x: Double,
        val y: Double,
    )

    companion object {
        private const val DEG_TO_RAD: Double = Math.PI / 180.0
        private const val MAX_SIN_LAT: Double = 0.9999
        private const val LOW_ZOOM_THRESHOLD: Double = 4.0
        private const val PAN_ANIMATION_MIN_DISTANCE_METERS: Double = 1.0
        private const val CAMERA_ANGLE_EPSILON: Double = 1e-2
    }
}
