package com.mapconductor.marker.clustering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mapconductor.compose.marker.LocalMarkerCollector
import com.mapconductor.core.map.LocalMapServiceRegistry
import com.mapconductor.core.map.LocalMapViewController
import com.mapconductor.core.marker.MarkerCollector
import com.mapconductor.core.marker.MarkerRenderingSupport
import com.mapconductor.core.marker.MarkerRenderingSupportKey
import com.mapconductor.core.marker.StrategyMarkerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * クラスタリングストラテジを地図へつなぐ配線。
 *
 * プロバイダごとの違いは [MarkerRenderingSupport] に閉じているので、ここは
 * どのプロバイダ上でも同じコードで動く。サービスレジストリから
 * [MarkerRenderingSupportKey] を解決できないプロバイダでは何も描かずに返る。
 */
@OptIn(FlowPreview::class)
@Composable
internal fun MarkerRenderingGroup(
    strategy: MarkerClusterStrategy,
    trackMarkerUpdates: Boolean,
    content: @Composable () -> Unit,
) {
    val mapController = LocalMapViewController.current

    val services = LocalMapServiceRegistry.current

    @Suppress("UNCHECKED_CAST")
    val renderingSupport =
        services.get(MarkerRenderingSupportKey) as? MarkerRenderingSupport<Any> ?: return
    val markerCollector = remember { MarkerCollector() }
    val renderer =
        remember(mapController) {
            renderingSupport.createMarkerRenderer(strategy)
        }
    val markerController =
        remember(strategy, renderer) {
            StrategyMarkerController(
                strategy = strategy,
                renderer = renderer,
            )
        }
    val eventController =
        remember(markerController, renderer) {
            renderingSupport.createMarkerEventController(markerController, renderer)
        }

    var isRegistered by remember { mutableStateOf(false) }

    LaunchedEffect(mapController, markerController, eventController) {
        mapController.registerOverlayController(markerController)
        renderingSupport.registerMarkerEventController(eventController)
        isRegistered = true
    }

    DisposableEffect(markerCollector, markerController, strategy, trackMarkerUpdates) {
        if (trackMarkerUpdates) {
            // 判定はストラテジの元データ集合で行い、`getEntity(id) != null` では行わない。
            // クラスタに飲み込まれたメンバーは自分の entity を持たないので、entity で
            // 判定すると転送が必要なマーカーだけを落としてしまう（1 件動かしたら
            // 再クラスタしなければならない）。ios-marker-cluster の
            // `statesById[state.id] != nil` と同じ条件。
            markerCollector.setUpdateHandler { markerState ->
                if (strategy.hasSourceMarker(markerState.id)) {
                    withContext(Dispatchers.Default) {
                        markerController.update(markerState)
                    }
                }
            }
        } else {
            markerCollector.setUpdateHandler(null)
        }
        onDispose {
            markerCollector.setUpdateHandler(null)
        }
    }

    val mapLoaded = renderingSupport.mapLoadedState?.collectAsState()?.value ?: true
    var requestedInitialCameraUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(mapLoaded, isRegistered) {
        if (!mapLoaded || !isRegistered || requestedInitialCameraUpdate) return@LaunchedEffect
        requestedInitialCameraUpdate = true
        renderingSupport.onMarkerRenderingReady()
    }

    LaunchedEffect(mapLoaded) {
        if (!mapLoaded) return@LaunchedEffect
        markerCollector.flow.collectLatest { markerMap ->
            // 大量のマーカーで UI スレッドに O(n) の仕事をさせない。
            val snapshot =
                withContext(Dispatchers.Default) {
                    markerMap.values.toList()
                }
            withContext(Dispatchers.Default) {
                markerController.add(snapshot)
            }
        }
    }

    CompositionLocalProvider(LocalMarkerCollector provides markerCollector) {
        content()
    }
}
