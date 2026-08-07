package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「いつ再クラスタするか」だけを持つ部分。何をどう描くかは知らない。
 *
 * カメラは 1 回の操作で何十回もイベントを出すので、そのたびに数千件の
 * クラスタリングを走らせるわけにいかない。ここでは 2 段構えで抑えている:
 *
 * 1. **デバウンス** — 最後のカメライベントから [debounceMillis] 静まるまで待つ。
 * 2. **CONFLATED なチャネル** — 待っている間に新しい要求が来たら古い方を捨てる。
 *    途中の状態を描いても一瞬で上書きされるだけなので、最新だけ処理すれば足りる。
 *
 * 発行したトークンより新しいものが出ていれば、途中の処理はいつでも打ち切ってよい
 * （[isCurrent] を各所で確認している）。
 *
 * ios-sdk の `ClusterRenderScheduler.swift` /
 * react-sdk の `ClusterRenderScheduler.ts` と同じ throttle 方針。
 */
internal class ClusterRenderScheduler(
    private val scope: CoroutineScope,
    private val geometry: ClusterGeometry,
    private val debounceMillis: Long,
    /** 実際のクラスタリングと描画。この中で [isCurrent] を見て打ち切ってよい。 */
    private val onRender: suspend (
        cameraPosition: MapCameraPosition,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) -> Unit,
) {
    private val updateToken = AtomicLong(0)
    private val requests = Channel<RenderRequest>(Channel.CONFLATED)
    private var worker: Job? = null
    private var debounceJob: Job? = null

    @Volatile private var isRendering = false

    var lastCameraPosition: MapCameraPosition? = null
        private set
    var lastRenderer: MarkerOverlayRendererInterface<Any>? = null
        private set
    var lastKnownViewport: GeoRectBounds? = null
        private set
    private var lastKnownViewportZoom: Double? = null

    /** 直近に実際に使ったビューポート。カメラ更新が来ていないときの再描画に使う。 */
    var lastUsedViewport: GeoRectBounds? = null

    val currentToken: Long get() = updateToken.get()

    fun nextToken(): Long = updateToken.incrementAndGet()

    fun isCurrent(token: Long): Boolean = token == updateToken.get()

    fun onCameraChanged(
        cameraPosition: MapCameraPosition,
        renderer: MarkerOverlayRendererInterface<Any>,
    ) {
        lastCameraPosition = cameraPosition
        cameraPosition.visibleRegion?.bounds?.let {
            lastKnownViewport = it
            lastKnownViewportZoom = cameraPosition.zoom
        }
        lastRenderer = renderer
        val token = updateToken.incrementAndGet()
        // 描画中のジョブは切らない。切ると描きかけのまま残る。
        if (debounceJob?.isActive == true && !isRendering) {
            debounceJob?.cancel()
        }
        debounceJob =
            scope.launch {
                if (debounceMillis > 0) {
                    delay(debounceMillis)
                }
                if (token != updateToken.get()) return@launch
                val currentCamera = lastCameraPosition ?: return@launch
                // visibleRegion が取れないプロバイダ（ArcGIS のアニメーション中など）
                // では直前のビューポートから推定する。詳細は
                // [ClusterGeometry.estimateViewport]。
                val viewport =
                    currentCamera.visibleRegion?.bounds
                        ?: geometry.estimateViewport(
                            zoom = currentCamera.zoom,
                            center = currentCamera.position,
                            lastKnownViewport = lastKnownViewport,
                            lastKnownViewportZoom = lastKnownViewportZoom,
                        )
                        ?: return@launch
                val currentRenderer = lastRenderer ?: return@launch
                enqueue(currentCamera, viewport, currentRenderer, token)
            }
    }

    fun enqueue(
        cameraPosition: MapCameraPosition,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        startWorkerIfNeeded()
        requests.trySend(
            RenderRequest(
                cameraPosition = cameraPosition,
                viewport = viewport,
                renderer = renderer,
                token = token,
            ),
        )
    }

    private fun startWorkerIfNeeded() {
        if (worker != null) return
        worker =
            scope.launch {
                for (request in requests) {
                    isRendering = true
                    try {
                        onRender(
                            request.cameraPosition,
                            request.viewport,
                            request.renderer,
                            request.token,
                        )
                    } finally {
                        isRendering = false
                    }
                }
            }
    }

    /** カメラの記憶だけを捨てる。動いているワーカーは残す（次の要求で再利用する）。 */
    fun reset() {
        lastCameraPosition = null
        lastKnownViewport = null
        lastKnownViewportZoom = null
        lastUsedViewport = null
    }

    private data class RenderRequest(
        val cameraPosition: MapCameraPosition,
        val viewport: GeoRectBounds,
        val renderer: MarkerOverlayRendererInterface<Any>,
        val token: Long,
    )
}
