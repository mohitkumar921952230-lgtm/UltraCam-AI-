package com.mohit.ultracam

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class LutType(val displayName: String) {
    IPHONE_NATURAL("iPhone Natural"),
    GOLDEN_SUNSET("Golden Glow"),
    MOODY_NATURE("Moody Dark"),
    ULTRA_VIVID("Ultra Vivid"),
    CINEMA_TEAL("Cinema Teal"),
    NEUTRAL("Pure Raw")
}

object LutPresets {
    const val LUT_SIZE = 32

    private fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float = max(lo, min(hi, v))

    private fun sCurve(x: Float, strength: Float): Float {
        val c = clamp(x)
        val s = c * c * (3f - 2f * c)
        return c * (1f - strength) + s * strength
    }

    fun transformRgb(rIn: Float, gIn: Float, bIn: Float, type: LutType): FloatArray {
        var r = clamp(rIn)
        var g = clamp(gIn)
        var b = clamp(bIn)
        val luma = 0.299f * r + 0.587f * g + 0.114f * b

        when (type) {
            LutType.IPHONE_NATURAL -> {
                // Balanced warm midtones, soft highlight roll-off, rich natural contrast
                r = sCurve(r, 0.22f)
                g = sCurve(g, 0.20f)
                b = sCurve(b, 0.18f)
                // Gentle highlight roll-off to avoid blown-out sky/skin
                if (luma > 0.72f) {
                    val roll = (luma - 0.72f) * 0.14f
                    r -= roll * 0.5f
                    g -= roll * 0.5f
                    b -= roll * 0.6f
                }
                // Warm midtone balance
                val midWeight = 4f * luma * (1f - luma)
                r += 0.032f * midWeight
                g += 0.015f * midWeight
                b -= 0.018f * midWeight
                // Subtle natural saturation (+12%)
                val newLuma = 0.299f * r + 0.587f * g + 0.114f * b
                r = newLuma + (r - newLuma) * 1.12f
                g = newLuma + (g - newLuma) * 1.12f
                b = newLuma + (b - newLuma) * 1.12f
            }

            LutType.GOLDEN_SUNSET -> {
                // Dreamy golden sunset & lush greens (inspired by sample #2 & #4)
                r = sCurve(r, 0.28f)
                g = sCurve(g, 0.24f)
                b = sCurve(b, 0.20f)
                // Warm golden highlights + soft misty shadows
                val hiWeight = luma.pow(1.3f)
                r += 0.085f * hiWeight + 0.02f
                g += 0.045f * hiWeight + 0.01f
                b -= 0.055f * hiWeight
                // Slight shadow lift for atmospheric mist
                val shWeight = (1f - luma).pow(2.2f)
                r += 0.025f * shWeight
                g += 0.030f * shWeight
                b += 0.010f * shWeight
                val newLuma = 0.299f * r + 0.587f * g + 0.114f * b
                r = newLuma + (r - newLuma) * 1.24f
                g = newLuma + (g - newLuma) * 1.20f
                b = newLuma + (b - newLuma) * 1.15f
            }

            LutType.MOODY_NATURE -> {
                // Deep crushed blacks, vibrant flowers & crisp greens (inspired by sample #1 & #3)
                r = sCurve(r, 0.45f)
                g = sCurve(g, 0.42f)
                b = sCurve(b, 0.40f)
                // Deepen shadows cleanly
                if (luma < 0.25f) {
                    val darken = (0.25f - luma) * 0.25f
                    r -= darken
                    g -= darken
                    b -= darken * 0.8f
                }
                // Rich crimson/pink & emerald boost
                val newLuma = 0.299f * r + 0.587f * g + 0.114f * b
                r = newLuma + (r - newLuma) * 1.32f
                g = newLuma + (g - newLuma) * 1.28f
                b = newLuma + (b - newLuma) * 1.18f
            }

            LutType.ULTRA_VIVID -> {
                // Samsung Flagship punchy vivid colors & crisp sky
                r = sCurve(r, 0.32f)
                g = sCurve(g, 0.32f)
                b = sCurve(b, 0.32f)
                val newLuma = 0.299f * r + 0.587f * g + 0.114f * b
                r = newLuma + (r - newLuma) * 1.35f
                g = newLuma + (g - newLuma) * 1.35f
                b = newLuma + (b - newLuma) * 1.38f
            }

            LutType.CINEMA_TEAL -> {
                // Blockbuster Teal shadows & Warm Orange highlights
                r = sCurve(r, 0.38f)
                g = sCurve(g, 0.34f)
                b = sCurve(b, 0.34f)
                val sh = (1f - luma).pow(1.6f)
                val hi = luma.pow(1.4f)
                r = r - 0.055f * sh + 0.065f * hi
                g = g + 0.025f * sh + 0.020f * hi
                b = b + 0.070f * sh - 0.060f * hi
                val newLuma = 0.299f * r + 0.587f * g + 0.114f * b
                r = newLuma + (r - newLuma) * 1.18f
                g = newLuma + (g - newLuma) * 1.15f
                b = newLuma + (b - newLuma) * 1.18f
            }

            LutType.NEUTRAL -> {
                // Minimal clean tone
            }
        }

        return floatArrayOf(clamp(r), clamp(g), clamp(b))
    }

    fun generateLutBuffer(type: LutType): ByteBuffer {
        val size = LUT_SIZE
        val buffer = ByteBuffer.allocateDirect(size * size * size * 4)
            .order(ByteOrder.nativeOrder())
        val denom = (size - 1).toFloat()

        for (bIdx in 0 until size) {
            val b = bIdx / denom
            for (gIdx in 0 until size) {
                val g = gIdx / denom
                for (rIdx in 0 until size) {
                    val r = rIdx / denom
                    val out = transformRgb(r, g, b, type)
                    buffer.put((out[0] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    buffer.put((out[1] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    buffer.put((out[2] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    buffer.put(255.toByte())
                }
            }
        }
        buffer.rewind()
        return buffer
    }
}
