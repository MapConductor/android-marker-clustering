package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** アニメーションで動かす 1 件分。[entity] は 1 フレームごとに差し替わる。 */
internal data class AnimatedMove(
    val id: String,
    val start: GeoPointInterface,
    val end: GeoPointInterface,
    val baseState: MarkerState,
    var entity: MarkerEntityInterface<Any>,
)

/**
 * ズーム／パン時にクラスタとメンバーの間をマーカーが移動するアニメーション。
 *
 * フレームごとに `onChange` + `onPostProcess` を呼ぶだけで、どのマーカーを
 * どこへ動かすかは [ClusterMarkerRenderer] が決める。
 *
 * **件数でフレームレートを落とす**のが要点。数百件を 60fps で動かすと
 * `onChange` が間に合わずカクつくので、件数に応じて 60/30/8/4fps へ落とす。
 * 動きは粗くなるが、止まって見えるよりは良い。
 *
 * ios-sdk の `ClusterMarkerAnimator.swift` /
 * react-sdk の `ClusterMarkerAnimator.ts` と同じ段階分け。
 */
internal class ClusterMarkerAnimator(
    private val geometry: ClusterGeometry,
    private val markerManager: MarkerManager<Any>,
    private val renderedMarkerEntities: ConcurrentHashMap<String, MarkerEntityInterface<Any>>,
    private val defaultMarkerIcon: BitmapIcon,
    /** 呼び出し時のトークンがまだ最新か。古くなったらアニメーションを打ち切る。 */
    private val isCurrent: (Long) -> Boolean,
) {
    /**
     * [moves] を [durationMillis] かけて動かす。
     *
     * @return 最後まで再生できたとき true。新しいカメラ更新に追い越されて
     *   途中で止めたときは false（呼び出し側が後始末する）。
     */
    suspend fun animate(
        moves: MutableList<AnimatedMove>,
        renderer: MarkerOverlayRendererInterface<Any>,
        durationMillis: Long,
        token: Long,
    ): Boolean {
        if (moves.isEmpty()) return true
        val frameMillis = animationFrameMillis(moves.size)
        val steps =
            max(
                1,
                ((durationMillis + frameMillis - 1L) / frameMillis).toInt(),
            )
        val stepDelayMillis =
            if (steps <= 1) {
                durationMillis
            } else {
                max(1L, durationMillis / steps.toLong())
            }

        val bitmapIcons =
            moves.map { move ->
                move.baseState.icon?.toBitmapIcon() ?: defaultMarkerIcon
            }
        val nextEntities = arrayOfNulls<MarkerEntityInterface<Any>>(moves.size)
        val changeParams =
            ArrayList<MutableChangeParams>(moves.size).apply {
                moves.forEachIndexed { index, move ->
                    add(
                        MutableChangeParams(
                            current = move.entity,
                            prev = move.entity,
                            bitmapIcon = bitmapIcons[index],
                        ),
                    )
                }
            }

        for (step in 1..steps) {
            if (!isCurrent(token)) return false
            currentCoroutineContext().ensureActive()
            val t = step.toDouble() / steps.toDouble()
            moves.forEachIndexed { index, move ->
                val position = geometry.interpolatePosition(move.start, move.end, t)
                val prevEntity = move.entity
                val nextState = move.baseState.copy(position = position)
                val nextEntity =
                    MarkerEntity(
                        marker = prevEntity.marker,
                        state = nextState,
                        isRendered = true,
                    )
                nextEntities[index] = nextEntity
                changeParams[index].prev = prevEntity
                changeParams[index].current = nextEntity
            }

            val actualMarkers = renderer.onChange(changeParams)
            actualMarkers.forEachIndexed { index, actual ->
                val nextEntity = nextEntities[index] ?: return@forEachIndexed
                val fallbackMarker = nextEntity.marker
                nextEntity.marker = actual ?: fallbackMarker
                markerManager.updateEntity(nextEntity)
                renderedMarkerEntities[nextEntity.state.id] = nextEntity
                moves[index].entity = nextEntity
            }
            renderer.onPostProcess()

            if (step < steps) {
                delay(stepDelayMillis)
            }
        }
        return true
    }

    private fun animationFrameMillis(moveCount: Int): Long =
        when {
            moveCount < 50 -> ANIMATION_FRAME_MILLIS_60_FPS
            moveCount < 100 -> ANIMATION_FRAME_MILLIS_30_FPS
            moveCount < 300 -> ANIMATION_FRAME_MILLIS_8_FPS
            else -> ANIMATION_FRAME_MILLIS_4_FPS
        }

    /**
     * フレームごとに中身を差し替えて使い回す変更パラメータ。
     *
     * 毎フレーム作り直すと件数×ステップ数だけ確保が走るので、
     * 1 件につき 1 つを使い回す。
     */
    private class MutableChangeParams(
        override var current: MarkerEntityInterface<Any>,
        override var prev: MarkerEntityInterface<Any>,
        override val bitmapIcon: BitmapIcon,
    ) : MarkerOverlayRendererInterface.ChangeParamsInterface<Any>

    companion object {
        private const val ANIMATION_FRAME_MILLIS_60_FPS: Long = 16L
        private const val ANIMATION_FRAME_MILLIS_30_FPS: Long = 33L
        private const val ANIMATION_FRAME_MILLIS_8_FPS: Long = 125L
        private const val ANIMATION_FRAME_MILLIS_4_FPS: Long = 250L
    }
}
