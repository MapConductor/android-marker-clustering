package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.geocell.HexGeocell
import com.mapconductor.core.geocell.HexGeocellInterface
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.AbstractMarkerRenderingStrategy
import com.mapconductor.core.marker.ColorDefaultIcon
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerFingerPrint
import com.mapconductor.core.marker.MarkerIconInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import com.mapconductor.core.spherical.expandBounds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 近くのマーカーを 1 つにまとめて描くマーカーレンダリングストラテジ。
 *
 * このクラスが持つのは**元データの保持と段取り**だけで、実際の仕事は
 * [ClusterComponents] が組み立てた責務ごとの部品へ渡す:
 *
 * | 部品                      | 担当                                        |
 * |---------------------------|---------------------------------------------|
 * | [ClusterRenderScheduler]  | いつ再クラスタするか（デバウンス・打ち切り）|
 * | [ClusterPlanner]          | 何をどこにまとめるか（前回結果の再利用）    |
 * | [ClusterBuilder]          | 近い候補の併合と中心の選び方                |
 * | [ClusterGeometry]         | 投影・境界・平均・凸包                      |
 * | [ClusterMarkerRenderer]   | 計画と現状の差を描画へ反映                  |
 * | [ClusterMarkerAnimator]   | クラスタとメンバーの間の移動アニメーション  |
 * | [SpiderfyController]      | クリックでメンバーを扇状に開く              |
 *
 * @param clusterIconProvider クラスタマーカーのアイコン。引数はメンバー数。
 * @param clusterIconProviderWithTurn ズームの周回数も受け取る版。指定するとこちらが優先。
 * @param prepareExpand クラスタが開いて個別マーカーが現れる直前に呼ばれる。これが
 *   返るまで新しい状態の反映を待つので、アイコン画像の先読みや読み込み表示に使える。
 *   より新しい再クラスタが来たら、待っている反映は捨てられる。
 * @param spiderfyMinZoom このズーム以上でクラスタをクリックすると、メンバーを扇状に
 *   開いて脚のポリラインでつなぐ。同じ場所に複数マーカーがあり、ズームでは分離
 *   できない場合に使う。もう一度クリックするか再クラスタで閉じる。これ未満の
 *   ズームでは [onClusterClick] へ落ちる。null で無効。
 * @param onSpiderfyChange 扇が開いた(true)／閉じた(false)ときに呼ばれる。別の
 *   クラスタをクリックしたときに吹き出しを閉じる、といった用途。メインスレッド。
 */
class MarkerClusterStrategy(
    clusterRadiusPx: Double = DEFAULT_CLUSTER_RADIUS_PX,
    minClusterSize: Int = DEFAULT_MIN_CLUSTER_SIZE,
    private val expandMargin: Double = DEFAULT_EXPAND_MARGIN,
    private val clusterIconProvider: (Int) -> MarkerIconInterface = DEFAULT_ICON_PROVIDER,
    private val clusterIconProviderWithTurn: ((Int, Int) -> MarkerIconInterface)? = null,
    private val onClusterClick: ((MarkerCluster) -> Unit)? = null,
    prepareExpand: (suspend (List<MarkerState>) -> Unit)? = null,
    spiderfyMinZoom: Double? = null,
    spiderfyMarkerSizePx: Double = DEFAULT_SPIDERFY_MARKER_SIZE_PX,
    spiderfyMarkerMarginPx: Double = DEFAULT_SPIDERFY_MARKER_MARGIN_PX,
    onSpiderfyChange: ((Boolean) -> Unit)? = null,
    private val enableZoomAnimation: Boolean = false,
    private val enablePanAnimation: Boolean = false,
    zoomAnimationDurationMillis: Long = DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS,
    @Suppress("UNUSED_PARAMETER")
    debugHullPolygons: Boolean = false,
    cameraIdleDebounceMillis: Long = DEFAULT_CAMERA_DEBOUNCE_MILLIS,
    private val tileSize: Double = DEFAULT_TILE_SIZE,
    semaphore: Semaphore = Semaphore(DEFAULT_SEMAPHORE_PERMITS),
    geocell: HexGeocellInterface = HexGeocell.defaultGeocell(),
) : AbstractMarkerRenderingStrategy<Any>(semaphore) {
    override val markerManager: MarkerManager<Any> = MarkerManager(geocell, 0)

    private val sourceStates = ConcurrentHashMap<String, MarkerState>()

    // マーカー全体の指紋。MarkerState.equals() は値比較なので、その場で書き換えた
    // 状態を自分自身と比べると必ず「変化なし」になり、`markerState.position = …`
    // では再クラスタが起きない。指紋の比較ならそれを捕まえられる。
    private val sourceFingerprints = ConcurrentHashMap<String, MarkerFingerPrint>()
    private val sourceStateVersion = AtomicLong(0)

    // ConcurrentHashMap: 描画ワーカーが書き換え、spiderfy の障害物集めのために
    // クリック（メイン）スレッドからも読む。
    private val renderedMarkerEntities = ConcurrentHashMap<String, MarkerEntityInterface<Any>>()

    private val _debugInfoFlow = MutableStateFlow<List<MarkerClusterDebugInfo>>(emptyList())
    val debugInfoFlow: StateFlow<List<MarkerClusterDebugInfo>> = _debugInfoFlow

    private val forceNextRender = AtomicBoolean(false)
    private val renderState = ClusterRenderState()
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * 内部部品の差し替え口。**生成直後・利用開始前にだけ**設定してよい
     * （[components] は最初に触れた時点で確定する）。検証用。
     */
    internal var componentOverrides: ClusterComponents.Overrides? = null

    // 部品はストラテジ自身のメソッド（onRender）と状態（sourceStates）を参照するため、
    // 全フィールドが揃ってから作る必要がある。lazy にしているのはそのため。
    private val components: ClusterComponents by lazy {
        ClusterComponents(
            geocell = geocell,
            clusterRadiusPx = clusterRadiusPx,
            minClusterSize = minClusterSize,
            tileSize = tileSize,
            cameraIdleDebounceMillis = cameraIdleDebounceMillis,
            zoomAnimationDurationMillis = zoomAnimationDurationMillis,
            spiderfyMinZoom = spiderfyMinZoom,
            spiderfyMarkerSizePx = spiderfyMarkerSizePx,
            spiderfyMarkerMarginPx = spiderfyMarkerMarginPx,
            prepareExpand = prepareExpand,
            onSpiderfyChange = onSpiderfyChange,
            markerManager = markerManager,
            renderedMarkerEntities = renderedMarkerEntities,
            defaultMarkerIcon = defaultMarkerIcon,
            scope = scope,
            onRender = ::renderClusters,
            sourceStateProvider = { sourceStates[it] },
            overrides = componentOverrides,
        )
    }

    private val prepareExpandCallback = prepareExpand
    private val scheduler get() = components.scheduler
    private val spiderfy get() = components.spiderfy

    /**
     * クラスタ計算のあと、マーカーのアニメーションが始まる前に同期的に呼ばれる。
     * [MarkerClusterGroup] が凸包ポリゴンの更新を確定させるために使う
     * （ポリゴンの描画とマーカーのアニメーションが競合しないようにするため）。
     */
    @Volatile
    var onBeforeAnimation: (suspend (List<MarkerClusterDebugInfo>) -> Unit)? = null

    /**
     * 開いている spiderfy の脚。閉じているときは空。
     * [MarkerClusterGroup] がこれを見て脚のポリラインを描く。
     */
    val spiderfyLegsFlow: StateFlow<List<SpiderfyLeg>> get() = spiderfy.legsFlow

    override fun clear() {
        sourceStates.clear()
        sourceFingerprints.clear()
        sourceStateVersion.set(0)
        markerManager.clear()
        _debugInfoFlow.value = emptyList()
        renderedMarkerEntities.clear()
        renderState.reset()
        scheduler.reset()
        spiderfy.reset()
        forceNextRender.set(false)
    }

    /**
     * このマーカーを既にストラテジへ渡してあるか。
     * [MarkerClusterGroup] は更新ハンドラの呼び出しをこれで絞り、
     * 管理していないマーカーを転送しないようにする。
     */
    fun hasSourceMarker(id: String): Boolean = sourceStates.containsKey(id)

    /**
     * 次の描画でクラスタを必ず計算し直す（カバー範囲による早期終了を飛ばす）。
     * [MarkerClusterGroupState.debugHullPolygons] が有効なとき、デバッグ用の
     * 凸包ポリゴンを現在のカメラ位置に即座に追従させるために使う。
     */
    fun forceRender() {
        forceNextRender.set(true)
        val cameraPosition = scheduler.lastCameraPosition ?: return
        val viewport = scheduler.lastKnownViewport ?: scheduler.lastUsedViewport ?: return
        val renderer = scheduler.lastRenderer ?: return
        scheduler.enqueue(cameraPosition, viewport, renderer, scheduler.nextToken())
    }

    override suspend fun onAdd(
        data: List<MarkerState>,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): Boolean {
        // renderClusters() は背景ワーカーで sourceStates を走査する。
        // ConcurrentModificationException を避けるため、書き換えも同じ semaphore で守る。
        semaphore.withPermit {
            updateSourceStates(data)
        }
        val cameraPosition = scheduler.lastCameraPosition ?: return true
        scheduler.enqueue(cameraPosition, viewport, renderer, scheduler.currentToken)
        return true
    }

    override suspend fun onUpdate(
        state: MarkerState,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): Boolean {
        semaphore.withPermit {
            val nextFingerPrint = state.fingerPrint()
            val prevFingerPrint = sourceFingerprints[state.id]
            sourceStates[state.id] = state
            sourceFingerprints[state.id] = nextFingerPrint
            if (prevFingerPrint != nextFingerPrint) {
                sourceStateVersion.incrementAndGet()
            }
        }
        val cameraPosition = scheduler.lastCameraPosition ?: return true
        scheduler.enqueue(cameraPosition, viewport, renderer, scheduler.currentToken)
        return true
    }

    override suspend fun onCameraChanged(
        cameraPosition: MapCameraPosition,
        renderer: MarkerOverlayRendererInterface<Any>,
    ) {
        scheduler.onCameraChanged(cameraPosition, renderer)
    }

    private fun updateSourceStates(data: List<MarkerState>) {
        val nextIds = data.map { it.id }.toSet()
        val removedIds = sourceStates.keys - nextIds
        var changed = false
        removedIds.forEach {
            sourceStates.remove(it)
            sourceFingerprints.remove(it)
            changed = true
        }
        data.forEach { state ->
            val nextFingerPrint = state.fingerPrint()
            if (sourceFingerprints[state.id] != nextFingerPrint) {
                changed = true
            }
            sourceStates[state.id] = state
            sourceFingerprints[state.id] = nextFingerPrint
        }
        if (changed) {
            sourceStateVersion.incrementAndGet()
        }
    }

    private suspend fun renderClusters(
        cameraPosition: MapCameraPosition,
        viewport: GeoRectBounds,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        semaphore.withPermit {
            if (!scheduler.isCurrent(token)) return@withPermit
            currentCoroutineContext().ensureActive()

            val zoom = cameraPosition.zoom
            val expandedBounds = expandBounds(viewport, expandMargin)
            val zoomChange = renderState.updateClusteringTurn(zoom)
            val sourceStateVersionSnapshot = sourceStateVersion.get()
            val stableSource = sourceStateVersionSnapshot == renderState.sourceStateVersion
            val cameraMoved =
                renderState.renderCameraPosition?.let {
                    components.geometry.hasCameraMoved(it, cameraPosition)
                } ?: false
            val animateTransitions =
                (enableZoomAnimation && zoomChange.zoomChanged) || (enablePanAnimation && cameraMoved)
            val forced = forceNextRender.getAndSet(false)
            scheduler.lastUsedViewport = viewport

            // 再クラスタ（カメラ移動・データ変更）は開いている扇を必ず閉じ、
            // 開きかけの処理も無効にする。
            spiderfy.invalidateAndCollapse(renderer)

            val covered =
                renderState.coverageBounds?.let {
                    components.geometry.containsBounds(it, expandedBounds)
                } ?: false
            if (!forced && !zoomChange.zoomChanged && covered && stableSource) {
                renderState.renderCameraPosition = cameraPosition
                return@withPermit
            }

            components.markerRenderer.cleanupStaleMarkers(
                currentZoom = zoom,
                renderer = renderer,
                skipClusterRemoval = animateTransitions,
                isKnownSourceMarker = { sourceStates.containsKey(it) },
            )

            // クラスタ ID にはズームが埋まっているので、ズームが変われば前回の
            // 割り当ては使えない。計画が途中で打ち切られてもここで捨ててある。
            if (zoomChange.zoomChanged) {
                renderState.assignments = emptyMap()
            }

            val plan =
                components.planner.plan(
                    sourceStates = sourceStates.values,
                    expandedBounds = expandedBounds,
                    zoom = zoom,
                    effectiveRadiusPx = components.builder.effectiveClusterRadiusPx(zoom),
                    zoomChanged = zoomChange.zoomChanged,
                    tileSize = tileSize,
                    cache = renderState.toPlanCache(),
                )

            val debugInfos =
                plan.entries.filterIsInstance<PlannedEntry.Cluster>().map { it.cluster.toDebugInfo() }
            val desiredMarkerStates =
                plan.entries.flatMap { entry ->
                    when (entry) {
                        is PlannedEntry.Cluster -> listOf(toClusterMarkerState(entry.cluster, zoomChange.turn))
                        is PlannedEntry.Singles -> entry.states
                    }
                }

            if (!scheduler.isCurrent(token)) return@withPermit
            _debugInfoFlow.value = debugInfos

            if (!awaitPrepareExpand(desiredMarkerStates, token)) return@withPermit

            val previousClusterMemberCenters = renderState.clusterMemberCenters
            // 凸包ポリゴンの更新をアニメーション開始前に確定させる
            // （ポリゴンの描画とマーカーのアニメーションを競合させないため）。
            onBeforeAnimation?.invoke(debugInfos)
            components.markerRenderer.updateRenderedMarkers(
                desiredStates = desiredMarkerStates,
                renderer = renderer,
                token = token,
                animateTransitions = animateTransitions,
                previousClusterMemberCenters = previousClusterMemberCenters,
                nextClusterMemberCenters = plan.clusterMemberCenters,
            )
            renderState.commit(plan, cameraPosition, sourceStateVersionSnapshot)
        }
    }

    /**
     * 新しく現れる個別マーカーの準備をアプリ側に任せ、終わるまで待つ。
     *
     * @return 反映を続けてよいとき true。待っている間に新しいカメラ更新に
     *   追い越されたら false。
     */
    private suspend fun awaitPrepareExpand(
        desiredStates: List<MarkerState>,
        token: Long,
    ): Boolean {
        val prepare = prepareExpandCallback ?: return true
        val appearing =
            desiredStates.filter { state ->
                !state.id.startsWith(ClusterPlanner.CLUSTER_ID_PREFIX) &&
                    !renderedMarkerEntities.containsKey(state.id)
            }
        if (appearing.isEmpty()) return true
        try {
            prepare(appearing)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A failed prepare must not block rendering.
        }
        return scheduler.isCurrent(token)
    }

    private fun toClusterMarkerState(
        cluster: PlannedCluster,
        turn: Int,
    ): MarkerState {
        val markerCluster =
            MarkerCluster(
                count = cluster.members.size,
                markerIds = cluster.members.map { it.id },
            )
        val clusterIcon =
            clusterIconProviderWithTurn?.invoke(cluster.members.size, turn)
                ?: clusterIconProvider(cluster.members.size)
        // クラスタのクリックは、条件を満たせばまず spiderfy が受け取り、
        // 受け取らなかったときだけ onClusterClick へ落ちる。
        val clickable = onClusterClick != null || spiderfy.isEnabled
        return MarkerState(
            id = cluster.id,
            position = cluster.center,
            extra = markerCluster,
            icon = clusterIcon,
            clickable = clickable,
            draggable = false,
            onClick =
                if (clickable) {
                    {
                        if (!spiderfy.tryToggle(markerCluster)) {
                            onClusterClick?.invoke(markerCluster)
                        }
                    }
                } else {
                    null
                },
        )
    }

    private fun PlannedCluster.toDebugInfo(): MarkerClusterDebugInfo =
        MarkerClusterDebugInfo(
            id = id,
            center = center,
            radiusMeters = radiusMeters,
            count = members.size,
            cellX = cell.x,
            cellY = cell.y,
            hullPoints = hullPoints,
        )

    companion object {
        const val DEFAULT_CLUSTER_RADIUS_PX: Double = 90.0
        const val DEFAULT_MIN_CLUSTER_SIZE: Int = 3
        const val DEFAULT_EXPAND_MARGIN: Double = 0.2
        const val DEFAULT_TILE_SIZE: Double = 256.0
        const val DEFAULT_ZOOM_ANIMATION_DURATION_MILLIS: Long = 300L
        const val DEFAULT_CAMERA_DEBOUNCE_MILLIS: Long = 100L
        const val DEFAULT_SPIDERFY_MARKER_SIZE_PX: Double = 52.0
        const val DEFAULT_SPIDERFY_MARKER_MARGIN_PX: Double = 8.0
        private const val DEFAULT_SEMAPHORE_PERMITS: Int = 3
        val DEFAULT_ICON_PROVIDER: (Int) -> MarkerIconInterface =
            { count -> ColorDefaultIcon(label = count.toString()) }
    }
}
