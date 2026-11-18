package jumpscan.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Offline, centered temporal convolutions; the whole clip is required. */
internal object LearnedFlight {
    private val points = intArrayOf(11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32)
    private class Layer(val outputs: Int, val inputs: Int, val kernel: Int,
        val dilation: Int, val padding: Int, val relu: Boolean, val weights: DoubleArray, val bias: DoubleArray)
    private val channels: Int
    private val threshold: Double
    private val layers: List<Layer>

    init {
        val tokens = requireNotNull(javaClass.getResourceAsStream("/jumpscan/core/airborne-v1.txt"))
            .bufferedReader().use { reader -> reader.readLines().flatMap {
                it.substringBefore('#').trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            } }.iterator()
        require(tokens.next() == "jumpscan-temporal-conv-v1")
        channels = tokens.next().toInt()
        threshold = tokens.next().toDouble()
        require(tokens.next().toDouble() == 33.0)
        require(channels == 37 && threshold == 0.5)
        layers = buildList {
            var inputs = channels
            while (tokens.hasNext()) {
                val outputs = tokens.next().toInt()
                val count = tokens.next().toInt()
                val kernel = tokens.next().toInt()
                val dilation = tokens.next().toInt()
                val padding = tokens.next().toInt()
                val relu = tokens.next().toInt() == 1
                require(count == inputs && outputs > 0 && kernel > 0)
                add(Layer(outputs, count, kernel, dilation, padding, relu,
                    DoubleArray(outputs * count * kernel) { tokens.next().toDouble() },
                    DoubleArray(outputs) { tokens.next().toDouble() }))
                inputs = outputs
            }
            require(size == 4 && last().outputs == 1)
        }
    }

    internal data class Input(val times: DoubleArray, val features: Array<DoubleArray>)
    internal data class Edges(val takeoffMs: Double, val contactMs: Double)
    // Null input means the learned path cannot run; a valid input with no edges is a negative prediction.
    internal data class Prediction(val edges: Edges?)

    fun predict(frames: List<Frame>, aspect: Float): Prediction? {
        val input = features(frames, aspect) ?: return null
        val edges = crossings(probabilities(input.features), input.times) ?: return Prediction(null)
        return Prediction(if (edges.second - edges.first in 100.0..1000.0)
            Edges(edges.first, edges.second) else null)
    }

    /** Same 37 features as reference_forward.mediapipe_features.
     * Select one nearest observed pose within half a step; higher capture rates are downsampled.
     * Missing positions are linearly interpolated (endpoints held), with zero visibility/presence.
     */
    fun features(frames: List<Frame>, aspect: Float,
        fps: Double = if (frames.size < 2) 0.0 else
            (frames.size - 1) * 1000.0 / (frames.last().tMs - frames.first().tMs)): Input? {
        if (frames.size < 2) return null
        if (!fps.isFinite() || fps < 24) return null
        val stepMs = 1000 / if (fps <= 36) fps else 30.0
        val first = Math.rint(frames.first().tMs / stepMs).toLong()
        val last = Math.rint(frames.last().tMs / stepMs).toLong()
        val size = (last - first + 1).toInt()
        if (size < 2) return null
        val times = DoubleArray(size) { (first + it) * stepMs }
        val observed = arrayOfNulls<Frame>(size)
        for (frame in frames) {
            // Ties to even match the reference and keep a half-step observation in only one cell.
            val i = (Math.rint(frame.tMs / stepMs).toLong() - first).toInt()
            if (i !in times.indices || abs(frame.tMs - times[i]) > stepMs / 2 + 1e-9) continue
            val previous = observed[i]
            if (previous == null || abs(frame.tMs - times[i]) < abs(previous.tMs - times[i])) observed[i] = frame
        }
        val indices = observed.indices.filter { observed[it] != null }
        if (indices.size < 2) return null
        val scale = median(indices.map { index ->
            val p = observed[index]!!.landmarks
            hypot((p[23].x.toDouble() - p[27].x) * aspect, p[23].y.toDouble() - p[27].y)
        })
        if (!scale.isFinite() || scale <= 1e-6) return null
        val x = Array(size) { DoubleArray(channels) }
        for ((j, point) in points.withIndex()) {
            val center = median(indices.map { observed[it]!!.landmarks[point].y.toDouble() })
            var previous = 0
            for (i in x.indices) {
                while (previous < indices.lastIndex && indices[previous + 1] <= i) previous++
                val left = indices[previous]
                val right = indices[minOf(previous + 1, indices.lastIndex)]
                val a = observed[left]!!.landmarks[point].y.toDouble()
                val b = observed[right]!!.landmarks[point].y.toDouble()
                val y = if (i <= left || left == right) a else a + (b - a) * (i - left) / (right - left)
                x[i][j] = -(y - center) / scale
                x[i][j + 24] = observed[i]?.landmarks?.get(point)?.visibility?.toDouble() ?: 0.0
            }
        }
        for (i in x.indices) {
            for (j in points.indices) x[i][j + 12] = when (i) {
                0 -> x[1][j] - x[0][j]
                x.lastIndex -> x[i][j] - x[i - 1][j]
                else -> (x[i + 1][j] - x[i - 1][j]) / 2
            }
            x[i][36] = if (observed[i] != null) 1.0 else 0.0
        }
        if (x.any { row -> row.any { !it.isFinite() } }) return null
        return Input(times, x)
    }

    fun probabilities(features: Array<DoubleArray>): DoubleArray {
        var x = features
        for (layer in layers) {
            val input = x
            x = Array(input.size) { time -> DoubleArray(layer.outputs) { output ->
                var value = layer.bias[output]
                for (channel in 0 until layer.inputs) {
                    val offset = (output * layer.inputs + channel) * layer.kernel
                    for (k in 0 until layer.kernel) {
                        val position = time + k * layer.dilation - layer.padding
                        if (position in input.indices) value += layer.weights[offset + k] * input[position][channel]
                    }
                }
                if (layer.relu && value < 0) 0.0 else value
            } }
        }
        return DoubleArray(x.size) { i ->
            val value = x[i][0]
            if (value >= 0) 1 / (1 + exp(-value)) else exp(value) / (1 + exp(value))
        }
    }

    fun crossings(p: DoubleArray, times: DoubleArray): Pair<Double, Double>? {
        if (p.size < 2) return null
        val stepMs = times[1] - times[0]
        var start = 0
        var bestStart = 0
        var bestEnd = -1
        while (start < p.size) {
            if (p[start] <= threshold) { start++; continue }
            var end = start
            while (end + 1 < p.size && p[end + 1] > threshold) end++
            if (end - start > bestEnd - bestStart) { bestStart = start; bestEnd = end }
            start = end + 1
        }
        if (bestEnd - bestStart < 1) return null
        val a = if (bestStart == 0) times[bestStart] else times[bestStart] -
            stepMs * (p[bestStart] - threshold) / maxOf(p[bestStart] - p[bestStart - 1], 1e-6)
        val b = if (bestEnd == p.lastIndex) times[bestEnd] else times[bestEnd] +
            stepMs * (p[bestEnd] - threshold) / maxOf(p[bestEnd] - p[bestEnd + 1], 1e-6)
        return a to b
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
}
