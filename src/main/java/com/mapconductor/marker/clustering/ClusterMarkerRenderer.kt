package com.mapconductor.marker.clustering

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.marker.BitmapIcon
import com.mapconductor.core.marker.MarkerEntity
import com.mapconductor.core.marker.MarkerEntityInterface
import com.mapconductor.core.marker.MarkerManager
import com.mapconductor.core.marker.MarkerOverlayRendererInterface
import com.mapconductor.core.marker.MarkerState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * 計画された最終形（[ClusterPlan] から作られたマーカー列）に、実際の描画を合わせる部分。
 *
 * 追加・更新・削除の差分を取り、アニメーションが要るものは
 * [ClusterMarkerAnimator] に渡す。**どれをクラスタにするかは決めない** —
 * それは [ClusterPlanner] の担当で、ここは「今出ているもの」と
 * 「出したいもの」の差を埋めるだけ。
 *
 * ios-sdk の `ClusterMarkerRenderer.swift` /
 * react-sdk の `ClusterMarkerRenderer.ts` と同じ差分の取り方。
 */
internal class ClusterMarkerRenderer(
    private val geometry: ClusterGeometry,
    private val animator: ClusterMarkerAnimator,
    private val markerManager: MarkerManager<Any>,
    private val renderedMarkerEntities: ConcurrentHashMap<String, MarkerEntityInterface<Any>>,
    private val defaultMarkerIcon: BitmapIcon,
    private val zoomAnimationDurationMillis: Long,
    /** 呼び出し時のトークンがまだ最新か。古くなったら描画を打ち切る。 */
    private val isCurrent: (Long) -> Boolean,
) {
    suspend fun updateRenderedMarkers(
        desiredStates: List<MarkerState>,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
        animateTransitions: Boolean,
        previousClusterMemberCenters: Map<String, GeoPoint>,
        nextClusterMemberCenters: Map<String, GeoPoint>,
    ) {
        val desiredById = desiredStates.associateBy { it.id }
        val animateZoom = animateTransitions && zoomAnimationDurationMillis > 0L

        if (!animateZoom) {
            removeOrphansBeforeDiff(desiredById.keys, renderer)
        }

        val existingById = markerManager.allEntities().associateBy { it.state.id }
        val removeIds = existingById.keys - desiredById.keys
        val addStates = desiredById.filterKeys { it !in existingById }.values
        val updateStates = desiredById.filterKeys { it in existingById }.values

        val animatedRemoveEntries =
            if (animateZoom) {
                planAnimatedRemoves(removeIds, existingById, nextClusterMemberCenters)
            } else {
                emptyList()
            }
        val animatedRemoveIds = animatedRemoveEntries.map { it.entity.state.id }.toSet()

        val animatedAddEntries =
            if (animateZoom) {
                planAnimatedAdds(addStates, previousClusterMemberCenters)
            } else {
                emptyList()
            }
        val animatedAddIds = animatedAddEntries.map { it.state.id }.toSet()

        var didImmediateChange = false
        if (applyImmediateRemoves(removeIds - animatedRemoveIds, renderer)) {
            didImmediateChange = true
        }
        val immediateAddStates = addStates.filterNot { it.id in animatedAddIds }
        if (immediateAddStates.isNotEmpty()) {
            addStatesToRenderer(immediateAddStates, renderer)
            didImmediateChange = true
        }
        if (applyUpdates(updateStates, existingById, renderer)) {
            didImmediateChange = true
        }

        if (didImmediateChange) {
            renderer.onPostProcess()
        }

        if (!animateZoom || (animatedRemoveEntries.isEmpty() && animatedAddEntries.isEmpty())) {
            return
        }
        if (!isCurrent(token)) return

        runTransitionAnimation(animatedAddEntries, animatedRemoveEntries, renderer, token)
    }

    /**
     * アニメーションしない回は、差分を取る前に「消えるもの」を先に消してしまう。
     *
     * 先に消しておかないと、同じ ID が再登場したときに前の実マーカーが残り、
     * 二重に出たまま参照だけ差し替わって回収できなくなる。
     */
    private suspend fun removeOrphansBeforeDiff(
        desiredIds: Set<String>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ) {
        val existingById = markerManager.allEntities().associateBy { it.state.id }
        val orphanedIds = existingById.keys - desiredIds
        val orphanedEntities = orphanedIds.mapNotNull { renderedMarkerEntities[it] }
        if (orphanedEntities.isEmpty()) return
        renderer.onRemove(orphanedEntities)
        orphanedEntities.forEach { forgetEntity(it) }
        renderer.onPostProcess()
    }

    /**
     * 消えるマーカーの行き先を決める。クラスタが消える場合は、
     * そのメンバーたちの新しい行き先の平均へ吸い込ませる。
     */
    private fun planAnimatedRemoves(
        removeIds: Set<String>,
        existingById: Map<String, MarkerEntityInterface<Any>>,
        nextClusterMemberCenters: Map<String, GeoPoint>,
    ): List<AnimatedRemove> =
        removeIds.mapNotNull { id ->
            val entity = existingById[id] ?: return@mapNotNull null
            val target =
                if (id.startsWith(ClusterPlanner.CLUSTER_ID_PREFIX)) {
                    val memberIds = (entity.state.extra as? MarkerCluster)?.markerIds ?: emptyList()
                    if (memberIds.isEmpty()) return@mapNotNull null
                    val memberTargets = memberIds.mapNotNull { nextClusterMemberCenters[it] }
                    if (memberTargets.isEmpty()) return@mapNotNull null
                    geometry.averageGeoPoints(memberTargets)
                } else {
                    nextClusterMemberCenters[id] ?: return@mapNotNull null
                }
            AnimatedRemove(entity = entity, target = target)
        }

    /** 出てくるマーカーの出発点を決める。クラスタなら、前回のメンバー位置の平均から広がる。 */
    private fun planAnimatedAdds(
        addStates: Collection<MarkerState>,
        previousClusterMemberCenters: Map<String, GeoPoint>,
    ): List<AnimatedAdd> =
        addStates.mapNotNull { state ->
            val start =
                if (state.id.startsWith(ClusterPlanner.CLUSTER_ID_PREFIX)) {
                    val memberIds = (state.extra as? MarkerCluster)?.markerIds ?: emptyList()
                    if (memberIds.isEmpty()) return@mapNotNull null
                    val memberStarts = memberIds.mapNotNull { previousClusterMemberCenters[it] }
                    if (memberStarts.isEmpty()) return@mapNotNull null
                    geometry.averageGeoPoints(memberStarts)
                } else {
                    previousClusterMemberCenters[state.id] ?: return@mapNotNull null
                }
            AnimatedAdd(state = state, start = start)
        }

    private suspend fun applyImmediateRemoves(
        ids: Set<String>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): Boolean {
        if (ids.isEmpty()) return false
        val removedEntities = ids.mapNotNull { renderedMarkerEntities[it] }
        if (removedEntities.isEmpty()) return false
        renderer.onRemove(removedEntities)
        removedEntities.forEach { forgetEntity(it) }
        return true
    }

    /** 位置や見た目が変わったものだけを `onChange` に載せる（指紋が同じものは飛ばす）。 */
    private suspend fun applyUpdates(
        updateStates: Collection<MarkerState>,
        existingById: Map<String, MarkerEntityInterface<Any>>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): Boolean {
        val changeParams = mutableListOf<MarkerOverlayRendererInterface.ChangeParamsInterface<Any>>()
        val changeEntities = mutableListOf<MarkerEntityInterface<Any>>()

        updateStates.forEach { state ->
            val prev = existingById[state.id] ?: return@forEach
            val nextEntity: MarkerEntityInterface<Any> =
                MarkerEntity(
                    marker = prev.marker,
                    state = state,
                    isRendered = true,
                )
            markerManager.registerEntity(nextEntity)

            if (prev.fingerPrint == state.fingerPrint()) {
                return@forEach
            }

            val change =
                object : MarkerOverlayRendererInterface.ChangeParamsInterface<Any> {
                    override val current: MarkerEntityInterface<Any> = nextEntity
                    override val prev: MarkerEntityInterface<Any> = prev
                    override val bitmapIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                }
            changeParams.add(change)
            changeEntities.add(nextEntity)
        }

        if (changeParams.isEmpty()) return false

        val actualMarkers = renderer.onChange(changeParams)
        actualMarkers.forEachIndexed { index, actual ->
            actual?.let {
                val entity: MarkerEntityInterface<Any> =
                    MarkerEntity(
                        marker = it,
                        state = changeEntities[index].state,
                        isRendered = true,
                    )
                markerManager.registerEntity(entity)
                renderedMarkerEntities[entity.state.id] = entity
            }
        }
        return true
    }

    /**
     * 出発点に置いてから目的地へ動かし、消えるものは動かし終えてから消す。
     *
     * 追い越されて途中で止まった場合は、出発点に置いたばかりのマーカーを
     * 取り下げる（放置すると出発点に取り残される）。
     */
    private suspend fun runTransitionAnimation(
        animatedAddEntries: List<AnimatedAdd>,
        animatedRemoveEntries: List<AnimatedRemove>,
        renderer: MarkerOverlayRendererInterface<Any>,
        token: Long,
    ) {
        val animatedStartEntities =
            if (animatedAddEntries.isNotEmpty()) {
                val animatedStartStates =
                    animatedAddEntries.map { entry -> entry.state.copy(position = entry.start) }
                val added = addStatesToRenderer(animatedStartStates, renderer)
                renderer.onPostProcess()
                added
            } else {
                emptyList()
            }

        val moves = mutableListOf<AnimatedMove>()
        animatedAddEntries.forEach { entry ->
            val entity = markerManager.getEntity(entry.state.id) ?: return@forEach
            moves.add(
                AnimatedMove(
                    id = entry.state.id,
                    start = entry.start,
                    end = entry.state.position,
                    baseState = entry.state,
                    entity = entity,
                ),
            )
        }
        animatedRemoveEntries.forEach { entry ->
            moves.add(
                AnimatedMove(
                    id = entry.entity.state.id,
                    start = entry.entity.state.position,
                    end = entry.target,
                    baseState = entry.entity.state,
                    entity = entry.entity,
                ),
            )
        }

        val completed = animator.animate(moves, renderer, zoomAnimationDurationMillis, token)

        if (animatedRemoveEntries.isNotEmpty()) {
            removeIfStillRendered(animatedRemoveEntries.map { it.entity }, renderer)
        }
        if (!completed && animatedStartEntities.isNotEmpty()) {
            removeIfStillRendered(animatedStartEntities, renderer)
        }
    }

    private suspend fun removeIfStillRendered(
        entities: List<MarkerEntityInterface<Any>>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ) {
        val target = entities.filter { renderedMarkerEntities.containsKey(it.state.id) }
        if (target.isEmpty()) return
        renderer.onRemove(target)
        target.forEach { forgetEntity(it) }
        renderer.onPostProcess()
    }

    suspend fun addStatesToRenderer(
        states: List<MarkerState>,
        renderer: MarkerOverlayRendererInterface<Any>,
    ): List<MarkerEntityInterface<Any>> {
        if (states.isEmpty()) return emptyList()
        val addedEntities = mutableListOf<MarkerEntityInterface<Any>>()
        val addParams =
            states.map { state ->
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
            renderedMarkerEntities[entity.state.id] = entity
            addedEntities.add(entity)
        }
        return addedEntities
    }

    /**
     * 前のズームで作られたクラスタと、元データから消えたマーカーを取り下げる。
     *
     * クラスタ ID にはズームが埋まっているので、ズームが変われば
     * 前のクラスタは必ず作り直しになる。
     */
    suspend fun cleanupStaleMarkers(
        currentZoom: Double,
        renderer: MarkerOverlayRendererInterface<Any>,
        skipClusterRemoval: Boolean,
        isKnownSourceMarker: (String) -> Boolean,
    ) {
        val currentZoomKey = currentZoom.roundToInt()
        val staleEntities =
            renderedMarkerEntities.values.filter { entity ->
                val id = entity.state.id
                if (!id.startsWith(ClusterPlanner.CLUSTER_ID_PREFIX)) {
                    return@filter !isKnownSourceMarker(id)
                }
                if (skipClusterRemoval) return@filter false
                val parts = id.split("_")
                if (parts.size < CLUSTER_ID_PART_COUNT) return@filter false
                (parts[1].toIntOrNull() ?: -1) != currentZoomKey
            }

        if (staleEntities.isEmpty()) return
        renderer.onRemove(staleEntities)
        staleEntities.forEach { forgetEntity(it) }
        renderer.onPostProcess()
    }

    private fun forgetEntity(entity: MarkerEntityInterface<Any>) {
        renderedMarkerEntities.remove(entity.state.id)
        markerManager.removeEntity(entity.state.id)
    }

    private data class AnimatedAdd(
        val state: MarkerState,
        val start: GeoPoint,
    )

    private data class AnimatedRemove(
        val entity: MarkerEntityInterface<Any>,
        val target: GeoPoint,
    )

    companion object {
        /** `cluster_{zoom}_{x}_{y}` を `_` で割った要素数。 */
        private const val CLUSTER_ID_PART_COUNT: Int = 4
    }
}
