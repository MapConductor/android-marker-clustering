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
import androidx.compose.ui.unit.dp
import com.mapconductor.compose.polygon.LocalPolygonCollector
import com.mapconductor.core.OverlayCollectorInterface
import com.mapconductor.core.polygon.PolygonState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 凸包デバッグポリゴンの見た目。設定可能にしていないのは、3 プラットフォームの
// 公開 API に出しているデバッグ用のつまみが `debugHullPolygons` だけのため。
private val DEBUG_HULL_STROKE_WIDTH: Dp = 2.dp
private const val DEBUG_HULL_STROKE_ALPHA: Float = 0.8f
private const val DEBUG_HULL_FILL_ALPHA: Float = 0.18f

/**
 * `debugHullPolygons` が有効なとき、各クラスタの凸包をポリゴンとして描く。
 *
 * どのマーカーがどのクラスタに入ったかを目で確かめるための開発用表示で、
 * クラスタリングの動作そのものには影響しない。
 */
@Composable
internal fun DebugHullPolygonEffects(
    strategy: MarkerClusterStrategy,
    enabled: Boolean,
) {
    val polygonCollector = LocalPolygonCollector.current
    val debugInfos by strategy.debugInfoFlow.collectAsState()
    var activeHullIds by remember(strategy, polygonCollector) { mutableStateOf<Set<String>>(emptySet()) }
    val latestActiveHullIds by rememberUpdatedState(activeHullIds)

    // 有効にした直後は、カバー範囲のキャッシュではなく現在のカメラ位置に
    // 対応したポリゴンを出したいので、再計算を強制する。
    LaunchedEffect(strategy, enabled) {
        if (enabled) {
            strategy.forceRender()
        }
    }

    LaunchedEffect(strategy, polygonCollector, enabled, debugInfos) {
        activeHullIds =
            if (enabled) {
                syncDebugHullPolygons(
                    polygonCollector = polygonCollector,
                    debugInfos = debugInfos,
                    strokeWidth = DEBUG_HULL_STROKE_WIDTH,
                    strokeAlpha = DEBUG_HULL_STROKE_ALPHA,
                    fillAlpha = DEBUG_HULL_FILL_ALPHA,
                    activeHullIds = activeHullIds,
                )
            } else {
                removeDebugHullPolygons(polygonCollector, activeHullIds)
            }
    }

    // 見た目の値はメインスレッドで取り出しておく（背景から Compose の状態を
    // 読まないため）。DisposableEffect は設定が変わるたびに張り直す。
    DisposableEffect(strategy, polygonCollector, enabled) {
        if (enabled) {
            val strokeWidth = DEBUG_HULL_STROKE_WIDTH
            val strokeAlpha = DEBUG_HULL_STROKE_ALPHA
            val fillAlpha = DEBUG_HULL_FILL_ALPHA

            strategy.onBeforeAnimation = { nextDebugInfos ->
                // ポリゴンの状態は背景スレッドで作り、反映はメインスレッドで行う。
                // どちらも返る前に完了するので、マーカーのアニメーション
                // （updateRenderedMarkers）はポリゴンの出し入れが終わるまで始まらない。
                activeHullIds =
                    syncDebugHullPolygons(
                        polygonCollector = polygonCollector,
                        debugInfos = nextDebugInfos,
                        strokeWidth = strokeWidth,
                        strokeAlpha = strokeAlpha,
                        fillAlpha = fillAlpha,
                        activeHullIds = activeHullIds,
                    )
            }

            onDispose {
                strategy.onBeforeAnimation = null
            }
        } else {
            strategy.onBeforeAnimation = null

            onDispose {
                strategy.onBeforeAnimation = null
            }
        }
    }

    DisposableEffect(strategy, polygonCollector) {
        onDispose {
            strategy.onBeforeAnimation = null
            latestActiveHullIds.forEach { polygonCollector.remove(it) }
        }
    }
}

private data class DebugCellKey(
    val x: Int,
    val y: Int,
)

private suspend fun syncDebugHullPolygons(
    polygonCollector: OverlayCollectorInterface<PolygonState>,
    debugInfos: List<MarkerClusterDebugInfo>,
    strokeWidth: Dp,
    strokeAlpha: Float,
    fillAlpha: Float,
    activeHullIds: Set<String>,
): Set<String> {
    val nextStates =
        withContext(Dispatchers.Default) {
            val colorsByCell = assignDistinctDebugColors(debugInfos)
            debugInfos
                .filter { it.hullPoints.size >= 3 }
                .map { info ->
                    val base = colorsByCell[DebugCellKey(info.cellX, info.cellY)] ?: Color.Magenta
                    PolygonState(
                        id = "cluster-hull-${info.id}",
                        points = info.hullPoints,
                        strokeColor = base.copy(alpha = strokeAlpha),
                        strokeWidth = strokeWidth,
                        fillColor = base.copy(alpha = fillAlpha),
                        geodesic = false,
                        zIndex = 9,
                        extra = null,
                        onClick = null,
                    )
                }
        }

    return withContext(Dispatchers.Main) {
        val nextIds = nextStates.map { it.id }.toSet()
        (activeHullIds - nextIds).forEach { polygonCollector.remove(it) }
        nextStates.forEach { polygonCollector.add(it) }
        nextIds
    }
}

private fun removeDebugHullPolygons(
    polygonCollector: OverlayCollectorInterface<PolygonState>,
    activeHullIds: Set<String>,
): Set<String> {
    activeHullIds.forEach { polygonCollector.remove(it) }
    return emptySet()
}

/**
 * 隣り合うクラスタが同じ色にならないように色を割り当てる。
 *
 * 単純にハッシュで選ぶと隣同士が同色になり、どこが境目か分からなくなる。
 * セル座標順に見て、8 近傍で使われていない色を選ぶ。
 */
private fun assignDistinctDebugColors(infos: List<MarkerClusterDebugInfo>): Map<DebugCellKey, Color> {
    if (infos.isEmpty()) return emptyMap()

    val palette =
        listOf(
            Color(0xFFE53935), // red
            Color(0xFFD81B60), // pink
            Color(0xFF8E24AA), // purple
            Color(0xFF5E35B1), // deep purple
            Color(0xFF3949AB), // indigo
            Color(0xFF1E88E5), // blue
            Color(0xFF039BE5), // light blue
            Color(0xFF00ACC1), // cyan
            Color(0xFF00897B), // teal
            Color(0xFF43A047), // green
            Color(0xFF7CB342), // light green
            Color(0xFFFDD835), // yellow
            Color(0xFFFFB300), // amber
            Color(0xFFFB8C00), // orange
        )

    val result = LinkedHashMap<DebugCellKey, Color>(infos.size * 2)
    val sorted = infos.sortedWith(compareBy<MarkerClusterDebugInfo> { it.cellX }.thenBy { it.cellY })

    fun neighborColors(key: DebugCellKey): Set<Color> {
        val used = mutableSetOf<Color>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val c = result[DebugCellKey(key.x + dx, key.y + dy)] ?: continue
                used.add(c)
            }
        }
        return used
    }

    sorted.forEach { info ->
        val key = DebugCellKey(info.cellX, info.cellY)
        val used = neighborColors(key)
        val start = (info.id.hashCode() and 0x7fffffff) % palette.size
        var chosen: Color? = null
        for (i in palette.indices) {
            val candidate = palette[(start + i) % palette.size]
            if (candidate !in used) {
                chosen = candidate
                break
            }
        }
        result[key] = chosen ?: palette[start]
    }

    return result
}
