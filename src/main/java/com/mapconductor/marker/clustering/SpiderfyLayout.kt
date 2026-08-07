package com.mapconductor.marker.clustering

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * spiderfy で開いたメンバーを画面上のどこへ置くかを決める、力学モデルの配置計算。
 *
 * クラスタの周りの等間隔な円から始めて、メンバーどうし・既に出ている他のマーカー
 * （固定の障害物）・クラスタ自身を押しのけ合わせる。同時に中心へ向かう弱いばねを
 * かけて広がりすぎを抑える。少数なら円、多いと同心の層に収束する。
 *
 * 純粋な計算で、地図にもマーカーにも触らない。座標はクラスタ中心からの相対 px。
 *
 * ios-sdk の `SpiderfyLayout.swift` / react-sdk の `SpiderfyLayout.ts` と同じ式。
 */
internal class SpiderfyLayout {
    /**
     * @param count 開くメンバー数
     * @param markerSizePx マーカーの直径
     * @param marginPx マーカー間に空ける余白
     * @param obstacles 既に描かれているマーカーのクラスタ中心からの相対座標
     * @return クラスタ中心からの相対オフセット（[count] 件）
     */
    fun compute(
        count: Int,
        markerSizePx: Double,
        marginPx: Double,
        obstacles: List<Offset>,
    ): List<Offset> {
        val desired = markerSizePx + marginPx
        // クラスタ中心からの基準距離。脚が見える程度に離し、広がりすぎない程度に近く。
        val centerClearance = (markerSizePx * CENTER_CLEARANCE_RATIO).roundToInt() + marginPx
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        for (i in 0 until count) {
            // 0 度（右）から等間隔に置く。2 件のときに左右へ並ぶので、
            // ピン型クラスタアイコンの頭を避けられる。
            val angle = 2.0 * PI * i / count
            xs[i] = cos(angle) * centerClearance
            ys[i] = sin(angle) * centerClearance
        }
        for (iteration in 0 until MAX_ITERATIONS) {
            var maxMove = 0.0
            for (i in 0 until count) {
                var fx = 0.0
                var fy = 0.0
                // 開いたメンバーどうしの反発。
                for (j in 0 until count) {
                    if (i == j) continue
                    val dx = xs[i] - xs[j]
                    val dy = ys[i] - ys[j]
                    var d = hypot(dx, dy)
                    if (d == 0.0) d = MIN_DISTANCE
                    if (d < desired) {
                        val push = (desired - d) / 2.0
                        fx += dx / d * push
                        fy += dy / d * push
                    }
                }
                // 既に描かれている近くのマーカー（固定の障害物）からの反発。
                for (obstacle in obstacles) {
                    val dx = xs[i] - obstacle.x
                    val dy = ys[i] - obstacle.y
                    var d = hypot(dx, dy)
                    if (d == 0.0) d = MIN_DISTANCE
                    if (d < desired) {
                        val push = desired - d
                        fx += dx / d * push
                        fy += dy / d * push
                    }
                }
                var dc = hypot(xs[i], ys[i])
                if (dc == 0.0) dc = MIN_DISTANCE
                if (dc < centerClearance) {
                    // クラスタマーカー自身からの反発。
                    val push = centerClearance - dc
                    fx += xs[i] / dc * push
                    fy += ys[i] / dc * push
                } else {
                    // 中心へ向かう弱いばね（離れすぎを防ぐ）。
                    val pull = (dc - centerClearance) * CENTER_SPRING
                    fx -= xs[i] / dc * pull
                    fy -= ys[i] / dc * pull
                }
                xs[i] += fx * STEP_RATIO
                ys[i] += fy * STEP_RATIO
                maxMove = max(maxMove, max(abs(fx), abs(fy)))
            }
            if (maxMove < CONVERGENCE_THRESHOLD) break
        }
        return List(count) { i -> Offset(xs[i].toFloat(), ys[i].toFloat()) }
    }

    companion object {
        private const val MAX_ITERATIONS: Int = 150
        private const val CONVERGENCE_THRESHOLD: Double = 0.15
        private const val CENTER_CLEARANCE_RATIO: Double = 1.3
        private const val CENTER_SPRING: Double = 0.15
        private const val STEP_RATIO: Double = 0.6
        private const val MIN_DISTANCE: Double = 0.01
    }
}
