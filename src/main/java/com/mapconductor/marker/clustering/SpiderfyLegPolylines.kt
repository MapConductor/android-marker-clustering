package com.mapconductor.marker.clustering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.mapconductor.compose.polyline.LocalPolylineCollector
import com.mapconductor.core.polyline.PolylineState

/**
 * 開いている spiderfy の扇の脚を、地図のポリラインとして描く。
 *
 * ストラテジ（[SpiderfyController]）は脚の線分を流すだけで、地図には触らない。
 * それをここでポリラインコレクタへ写す。閉じたときは空のリストが流れてくるので、
 * 差分を取って消す。
 */
@Composable
internal fun SpiderfyLegEffects(
    strategy: MarkerClusterStrategy,
    legColor: Color,
    legWidth: Dp,
) {
    val polylineCollector = LocalPolylineCollector.current
    val spiderfyLegs by strategy.spiderfyLegsFlow.collectAsState()
    var activeLegIds by remember(strategy, polylineCollector) { mutableStateOf<Set<String>>(emptySet()) }
    val latestActiveLegIds by rememberUpdatedState(activeLegIds)

    LaunchedEffect(
        strategy,
        polylineCollector,
        spiderfyLegs,
        legColor,
        legWidth,
    ) {
        val nextIds = spiderfyLegs.map { it.id }.toSet()
        (activeLegIds - nextIds).forEach { polylineCollector.remove(it) }
        spiderfyLegs.forEach { leg ->
            polylineCollector.add(
                PolylineState(
                    points = listOf(leg.start, leg.end),
                    id = leg.id,
                    strokeColor = legColor,
                    strokeWidth = legWidth,
                    geodesic = false,
                ),
            )
        }
        activeLegIds = nextIds
    }

    DisposableEffect(strategy, polylineCollector) {
        onDispose {
            latestActiveLegIds.forEach { polylineCollector.remove(it) }
        }
    }
}
