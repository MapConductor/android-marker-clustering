package com.mapconductor.marker.clustering

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * クラスタをクリックしたときに、メンバーを扇状に開く機能（spiderfy）。
 *
 * 同じ場所に複数のマーカーがあると、いくらズームしても分離できない。
 * そこで [minZoom] 以上でクラスタをクリックしたら、メンバーの複製を
 * 画面上で開いて脚のポリラインでつなぐ。もう一度クリックするか、
 * 再クラスタ（カメラ移動・データ変更）が起きると閉じる。
 *
 * **複製で描く**のが要点。元のマーカーを動かすと、閉じたときに位置を戻す責任が
 * 発生し、途中で再クラスタが挟まると戻し損ねる。`spider_` 接頭辞の別マーカーを
 * 出し入れするだけなら、閉じる処理は「消す」だけで済む。
 *
 * ios-sdk の `SpiderfyController.swift` /
 * react-sdk の `SpiderfyController.ts` と同じ状態遷移。
 */
internal class SpiderfyController(
    private val geometry: ClusterGeometry,
    private val layout: SpiderfyLayout,
    private val markerManager: MarkerManager<Any>,
    private val renderedMarkerEntities: ConcurrentHashMap<String, MarkerEntityInterface<Any>>,
    private val defaultMarkerIcon: BitmapIcon,
    private val scope: CoroutineScope,
    /** これ以上のズームでのみ有効。null なら機能そのものを使わない。 */
    private val minZoom: Double?,
    private val markerSizePx: Double,
    private val markerMarginPx: Double,
    /** 開く前に呼ばれ、これが返るまで描画を待つ（アイコンの先読み用）。 */
    private val prepareExpand: (suspend (List<MarkerState>) -> Unit)?,
    /** 開いた(true)／閉じた(false)を知らせる。メインスレッドで呼ぶ。 */
    private val onChange: ((Boolean) -> Unit)?,
    private val cameraProvider: () -> MapCameraPosition?,
    private val rendererProvider: () -> MarkerOverlayRendererInterface<Any>?,
    private val sourceStateProvider: (String) -> MarkerState?,
) {
    private val _legsFlow = MutableStateFlow<List<SpiderfyLeg>>(emptyList())

    /** 開いている扇の脚。閉じているときは空。[MarkerClusterGroup] がこれを描く。 */
    val legsFlow: StateFlow<List<SpiderfyLeg>> = _legsFlow

    private val mutex = Mutex()
    private val token = AtomicLong(0)

    @Volatile private var openClusterKey: String? = null
    private val entities = mutableListOf<MarkerEntityInterface<Any>>()

    /** 設定として spiderfy が有効か（ズーム条件は見ない）。 */
    val isEnabled: Boolean get() = minZoom != null

    /**
     * クラスタマーカーのクリックを処理する。
     *
     * @return spiderfy が受け取ったとき true。false なら呼び出し側の
     *   `onClusterClick` へ落とす。呼び出し元（メインスレッド）では判定だけ行い、
     *   描画は非同期に進む。
     */
    fun tryToggle(cluster: MarkerCluster): Boolean {
        val minZoom = this.minZoom ?: return false
        val camera = cameraProvider() ?: return false
        if (camera.zoom < minZoom) return false
        val renderer = rendererProvider() ?: return false
        val holder = renderer.holder

        val clusterKey = cluster.markerIds.sorted().joinToString(",")
        if (openClusterKey == clusterKey) {
            // 開いているクラスタをもう一度クリックしたら閉じる。
            token.incrementAndGet()
            scope.launch {
                mutex.withLock { collapseLocked(renderer) }
            }
            return true
        }

        val members = cluster.markerIds.mapNotNull { sourceStateProvider(it) }
        if (members.isEmpty()) return false

        // 実際に描かれているクラスタマーカーの位置を中心にする（メンバー平均とは
        // ずれることがあり、ずれたままだと脚がマーカーの根元に集まらない）。
        var centerGeo = geometry.averagePosition(members)
        renderedMarkerEntities.values.firstOrNull { it.state.extra === cluster }?.let { entity ->
            centerGeo = GeoPoint.from(entity.state.position)
        }
        val centerPx = holder.toScreenOffset(centerGeo) ?: return false

        val offsets =
            layout.compute(
                count = members.size,
                markerSizePx = markerSizePx,
                marginPx = markerMarginPx,
                obstacles = collectObstacles(renderer, centerPx),
            )
        val currentToken = token.incrementAndGet()
        scope.launch {
            mutex.withLock { collapseLocked(renderer) }
            if (currentToken != token.get()) return@launch
            open(
                clusterKey = clusterKey,
                members = members,
                centerGeo = centerGeo,
                centerPx = centerPx,
                offsets = offsets,
                renderer = renderer,
                token = currentToken,
            )
        }
        return true
    }

    /**
     * 扇の周りに既に描かれているマーカーを、動かせない障害物として集める。
     *
     * クリックしたクラスタ自身は除く。代わりに、ピン型アイコンの頭に相当する
     * 疑似障害物を中心の真上に置く。
     */
    private fun collectObstacles(
        renderer: MarkerOverlayRendererInterface<Any>,
        centerPx: Offset,
    ): List<Offset> {
        val holder = renderer.holder
        val obstacles = mutableListOf<Offset>()
        renderedMarkerEntities.values.forEach { entity ->
            val px = holder.toScreenOffset(entity.state.position) ?: return@forEach
            val relX = px.x - centerPx.x
            val relY = px.y - centerPx.y
            val distance = hypot(relX.toDouble(), relY.toDouble())
            // クリックしたクラスタ自身と、遠すぎるものを無視する。
            if (distance < SELF_DISTANCE_PX || distance > OBSTACLE_MAX_DISTANCE_PX) return@forEach
            obstacles.add(Offset(relX, relY))
        }
        obstacles.add(Offset(0f, -(markerSizePx / 2.0).roundToInt().toFloat()))
        return obstacles
    }

    private suspend fun open(
        clusterKey: String,
        members: List<MarkerState>,
        centerGeo: GeoPoint,
        centerPx: Offset,
        offsets: List<Offset>,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        val holder = renderer.holder
        val clones = mutableListOf<MarkerState>()
        val legs = mutableListOf<SpiderfyLeg>()
        withContext(Dispatchers.Main) {
            members.forEachIndexed { index, member ->
                val screen =
                    Offset(
                        x = centerPx.x + offsets[index].x,
                        y = centerPx.y + offsets[index].y,
                    )
                val geo =
                    holder.fromScreenOffsetSync(screen)
                        ?: holder.fromScreenOffset(screen)
                        ?: return@forEachIndexed
                clones.add(member.copy(id = "$CLONE_ID_PREFIX${member.id}", position = geo, zIndex = CLONE_Z_INDEX))
                legs.add(SpiderfyLeg(id = "$LEG_ID_PREFIX${member.id}", start = centerGeo, end = geo))
            }
        }
        if (clones.isEmpty()) return

        // アプリ側が開くマーカーの準備（アイコンの先読みなど）を終えるまで、
        // クラスタの表示は変えない。新しい操作や再クラスタが来たら
        // 下のトークン確認で捨てられる。
        prepareExpand?.let { prepare ->
            try {
                prepare(clones)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed prepare must not block rendering.
            }
        }

        mutex.withLock {
            if (token != this.token.get()) return
            val addParams =
                clones.map { state ->
                    object : MarkerOverlayRendererInterface.AddParamsInterface {
                        override val state: MarkerState = state
                        override val bitmapIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                    }
                }
            val actualMarkers = renderer.onAdd(addParams)
            actualMarkers.forEachIndexed { index, actual ->
                val marker = actual ?: return@forEachIndexed
                val entity: MarkerEntityInterface<Any> =
                    MarkerEntity(
                        marker = marker,
                        state = addParams[index].state,
                        isRendered = true,
                    )
                markerManager.registerEntity(entity)
                entities.add(entity)
            }
            renderer.onPostProcess()
            _legsFlow.value = legs
            openClusterKey = clusterKey
        }
        withContext(Dispatchers.Main) {
            onChange?.invoke(true)
        }
    }

    /**
     * 開いている扇を閉じ、進行中の開く処理を無効にする。
     *
     * 再クラスタのたびに [MarkerClusterStrategy] が呼ぶ。
     */
    suspend fun invalidateAndCollapse(renderer: MarkerOverlayRendererInterface<Any>) {
        token.incrementAndGet()
        mutex.withLock { collapseLocked(renderer) }
    }

    /** [mutex] を保持した状態で呼ぶこと。 */
    private suspend fun collapseLocked(renderer: MarkerOverlayRendererInterface<Any>) {
        if (openClusterKey == null && entities.isEmpty()) return
        openClusterKey = null
        _legsFlow.value = emptyList()
        if (entities.isNotEmpty()) {
            val current = entities.toList()
            entities.clear()
            renderer.onRemove(current)
            current.forEach { entity ->
                markerManager.removeEntity(entity.state.id)
            }
            renderer.onPostProcess()
        }
        withContext(Dispatchers.Main) {
            onChange?.invoke(false)
        }
    }

    /**
     * 描画に触れずに状態だけ捨てる。
     *
     * [MarkerClusterStrategy.clear] から呼ばれる。あちらは `markerManager` ごと
     * 空にするので、ここでレンダラへ削除を投げる必要はない。
     */
    fun reset() {
        token.incrementAndGet()
        openClusterKey = null
        entities.clear()
        _legsFlow.value = emptyList()
    }

    companion object {
        private const val CLONE_ID_PREFIX: String = "spider_"
        private const val LEG_ID_PREFIX: String = "spiderleg_"
        private const val CLONE_Z_INDEX: Int = 2000
        private const val SELF_DISTANCE_PX: Double = 2.0
        private const val OBSTACLE_MAX_DISTANCE_PX: Double = 300.0
    }
}
