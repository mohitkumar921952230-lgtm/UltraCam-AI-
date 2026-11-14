package com.mohit.ultracam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object AiPhotoEnhancer {

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    private val selfieSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    private var cachedLutType: LutType? = null
    private var cachedLutTable: IntArray? = null

    private fun getLutTable(type: LutType): IntArray {
        val existing = cachedLutTable
        if (cachedLutType == type && existing != null) return existing
        val size = LutPresets.LUT_SIZE
        val table = IntArray(size * size * size * 3)
        val denom = (size - 1).toFloat()
        var idx = 0
        for (bIdx in 0 until size) {
            val b = bIdx / denom
            for (gIdx in 0 until size) {
                val g = gIdx / denom
                for (rIdx in 0 until size) {
                    val r = rIdx / denom
                    val rgb = LutPresets.transformRgb(r, g, b, type)
                    table[idx++] = (rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    table[idx++] = (rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    table[idx++] = (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
            }
        }
        cachedLutType = type
        cachedLutTable = table
        return table
    }

    suspend fun processAndSave(
        context: Context,
        frames: List<Bitmap>,
        lutType: LutType,
        isFrontCamera: Boolean,
        isPortraitMode: Boolean,
        isNightBoost: Boolean,
        extraZoom: Float,
        skinProtect: Float,
        goldenGlow: Float,
        sharpness: Float
    ): Uri? = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext null

        // 1. Multi-Exposure Anti-Glare Night HDR Fusion (if multiple bracketed frames captured)
        var work = if (frames.size >= 3) {
            fuseBracketedFrames(frames[0], frames[1], frames[2])
        } else {
            frames[0].copy(Bitmap.Config.ARGB_8888, true)
        }

        // Recycle extra source frames early to keep memory clean
        frames.forEach { if (it !== work && !it.isRecycled) it.recycle() }

        // 2. Apply Front/Back AI Zoom Crop & High-Res Bicubic Upscale
        if (extraZoom > 1.03f) {
            val cropped = applyZoomCropAndScale(work, extraZoom)
            if (cropped !== work) work.recycle()
            work = cropped
        }

        // Cap working resolution safely at 3840px long edge for fast offline AI on all phones
        val maxDim = max(work.width, work.height)
        if (maxDim > 3840) {
            val scale = 3840f / maxDim
            val scaled = Bitmap.createScaledBitmap(
                work,
                (work.width * scale).toInt().coerceAtLeast(1),
                (work.height * scale).toInt().coerceAtLeast(1),
                true
            )
            work.recycle()
            work = scaled
        }

        // 3. Offline AI Face Detection (iPhone skin-tone priority & protection)
        val hasFaces = detectFaces(work)

        // 4. Offline AI Portrait Bokeh (Person segmentation + Flower/Subject radial fallback)
        if (isPortraitMode) {
            val bokehBitmap = applyAiBokeh(work)
            if (bokehBitmap !== work) {
                work.recycle()
                work = bokehBitmap
            }
        }

        // 5. Offline AI Low-Light Recovery + 3D LUT + Skin Protection + Golden Bloom + HD Sharpening
        val finalBitmap = applyAiGradingAndSharpen(
            src = work,
            lutType = lutType,
            isNightBoost = isNightBoost,
            skinProtect = if (hasFaces) max(skinProtect, 0.80f) else skinProtect,
            goldenGlow = goldenGlow,
            sharpness = if (extraZoom > 1.2f) max(sharpness, 0.72f) else sharpness,
            isFrontCamera = isFrontCamera
        )
        if (finalBitmap !== work) work.recycle()

        // 6. Save Ultra-HD JPEG (98% quality) directly to Gallery (DCIM/UltraCam-AI)
        val savedUri = withContext(Dispatchers.IO) {
            saveBitmapToGallery(context, finalBitmap)
        }
        finalBitmap.recycle()
        savedUri
    }

    private fun fuseBracketedFrames(darkBmp: Bitmap, normalBmp: Bitmap, brightBmp: Bitmap): Bitmap {
        val w = normalBmp.width
        val h = normalBmp.height
        val dResized = if (darkBmp.width == w && darkBmp.height == h) darkBmp else Bitmap.createScaledBitmap(darkBmp, w, h, true)
        val bResized = if (brightBmp.width == w && brightBmp.height == h) brightBmp else Bitmap.createScaledBitmap(brightBmp, w, h, true)

        val nPx = IntArray(w * h)
        val dPx = IntArray(w * h)
        val bPx = IntArray(w * h)

        normalBmp.getPixels(nPx, 0, w, 0, 0, w, h)
        dResized.getPixels(dPx, 0, w, 0, 0, w, h)
        bResized.getPixels(bPx, 0, w, 0, 0, w, h)

        if (dResized !== darkBmp) dResized.recycle()
        if (bResized !== brightBmp) bResized.recycle()

        val out = IntArray(w * h)
        for (i in nPx.indices) {
            val np = nPx[i]
            val dp = dPx[i]
            val bp = bPx[i]

            val nr = (np ushr 16) and 0xFF
            val ng = (np ushr 8) and 0xFF
            val nb = np and 0xFF
            val nl = (nr * 77 + ng * 150 + nb * 29) ushr 8

            // Preserve highlights (streetlights/headlights/moon) using dark frame; lift shadows using bright frame
            val wDark = if (nl > 185) ((nl - 185) / 70f).coerceIn(0f, 0.78f) else 0f
            val wBright = if (nl < 75) ((75 - nl) / 75f).coerceIn(0f, 0.58f) else 0f
            val wNorm = (1f - wDark - wBright).coerceAtLeast(0.15f)
            val wSum = wDark + wBright + wNorm

            val dr = (dp ushr 16) and 0xFF
            val dg = (dp ushr 8) and 0xFF
            val db = dp and 0xFF

            val br = (bp ushr 16) and 0xFF
            val bg = (bp ushr 8) and 0xFF
            val bb = bp and 0xFF

            val r = ((nr * wNorm + dr * wDark + br * wBright) / wSum).toInt().coerceIn(0, 255)
            val g = ((ng * wNorm + dg * wDark + bg * wBright) / wSum).toInt().coerceIn(0, 255)
            val b = ((nb * wNorm + db * wDark + bb * wBright) / wSum).toInt().coerceIn(0, 255)

            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    private fun applyZoomCropAndScale(src: Bitmap, zoom: Float): Bitmap {
        val w = src.width
        val h = src.height
        val cropW = (w / zoom).toInt().coerceIn(64, w)
        val cropH = (h / zoom).toInt().coerceIn(64, h)
        val x = (w - cropW) / 2
        val y = (h - cropH) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        cropped.recycle()
        return scaled
    }

    private suspend fun detectFaces(bitmap: Bitmap): Boolean = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                if (cont.isActive) cont.resume(faces.isNotEmpty())
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(false)
            }
    }

    private suspend fun applyAiBokeh(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height

        // Fast 1/4-res blurred copy for creamy DSLR background bokeh
        val bw = (w / 4).coerceAtLeast(32)
        val bh = (h / 4).coerceAtLeast(32)
        val small = Bitmap.createScaledBitmap(src, bw, bh, true)
        val blurredSmall = fastStackBlur(small, 7)
        small.recycle()
        val blurredFull = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
        blurredSmall.recycle()

        val maskData = runSelfieSegmentation(src)

        val srcPx = IntArray(w * h)
        val blurPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        blurredFull.getPixels(blurPx, 0, w, 0, 0, w, h)
        blurredFull.recycle()

        val outPx = IntArray(w * h)
        if (maskData != null) {
            val (mw, mh, conf) = maskData
            for (y in 0 until h) {
                val my = ((y.toFloat() / h) * mh).toInt().coerceIn(0, mh - 1)
                val rowOff = y * w
                val mRowOff = my * mw
                for (x in 0 until w) {
                    val mx = ((x.toFloat() / w) * mw).toInt().coerceIn(0, mw - 1)
                    val fg = ((conf[mRowOff + mx] - 0.30f) / 0.45f).coerceIn(0f, 1f)
                    val sp = srcPx[rowOff + x]
                    val bp = blurPx[rowOff + x]
                    outPx[rowOff + x] = blendArgb(bp, sp, fg)
                }
            }
        } else {
            // Radial subject-focus fallback (great for macro flowers & objects!)
            val cx = w * 0.5f
            val cy = h * 0.48f
            val maxR = min(w, h) * 0.48f
            for (y in 0 until h) {
                val dy = y - cy
                val rowOff = y * w
                for (x in 0 until w) {
                    val dx = x - cx
                    val dist = sqrt(dx * dx + dy * dy) / maxR
                    val fg = (1f - ((dist - 0.55f) / 0.55f)).coerceIn(0f, 1f)
                    val sp = srcPx[rowOff + x]
                    val bp = blurPx[rowOff + x]
                    outPx[rowOff + x] = blendArgb(bp, sp, fg)
                }
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPx, 0, w, 0, 0, w, h)
        }
    }

    private data class MaskResult(val w: Int, val h: Int, val confidences: FloatArray)

    private suspend fun runSelfieSegmentation(bitmap: Bitmap): MaskResult? =
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            selfieSegmenter.process(input)
                .addOnSuccessListener { mask ->
                    val mw = mask.width
                    val mh = mask.height
                    val buf = mask.buffer
                    buf.rewind()
                    val arr = FloatArray(mw * mh)
                    var fgCount = 0
                    for (i in arr.indices) {
                        val v = buf.float
                        arr[i] = v
                        if (v > 0.6f) fgCount++
                    }
                    val ratio = fgCount.toFloat() / arr.size.coerceAtLeast(1)
                    if (cont.isActive) {
                        // Use person mask only if a clear subject is detected
                        if (ratio in 0.05f..0.90f) cont.resume(MaskResult(mw, mh, arr))
                        else cont.resume(null)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun fastStackBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val div = radius * 2 + 1

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -radius..radius) {
                val p = pix[row + k.coerceIn(0, w - 1)]
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addP = pix[row + (x + radius + 1).coerceIn(0, w - 1)]
                val remP = pix[row + (x - radius).coerceIn(0, w - 1)]
                rSum += ((addP ushr 16) and 0xFF) - ((remP ushr 16) and 0xFF)
                gSum += ((addP ushr 8) and 0xFF) - ((remP ushr 8) and 0xFF)
                bSum += (addP and 0xFF) - (remP and 0xFF)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -radius..radius) {
                val p = tmp[k.coerceIn(0, h - 1) * w + x]
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addP = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                val remP = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                rSum += ((addP ushr 16) and 0xFF) - ((remP ushr 16) and 0xFF)
                gSum += ((addP ushr 8) and 0xFF) - ((remP ushr 8) and 0xFF)
                bSum += (addP and 0xFF) - (remP and 0xFF)
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pix, 0, w, 0, 0, w, h)
        }
    }

    private fun blendArgb(bg: Int, fg: Int, alpha: Float): Int {
        val inv = 1f - alpha
        val r = (((bg ushr 16) and 0xFF) * inv + ((fg ushr 16) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        val g = (((bg ushr 8) and 0xFF) * inv + ((fg ushr 8) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        val b = ((bg and 0xFF) * inv + (fg and 0xFF) * alpha).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun applyAiGradingAndSharpen(
        src: Bitmap,
        lutType: LutType,
        isNightBoost: Boolean,
        skinProtect: Float,
        goldenGlow: Float,
        sharpness: Float,
        isFrontCamera: Boolean
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        val lut = getLutTable(lutType)
        val sSize = LutPresets.LUT_SIZE
        val sMax = sSize - 1

        // Build fast 256-entry Zero-DCE-style low-light curve when Night AI is active
        val nightLiftLut = IntArray(256) { v ->
            if (!isNightBoost) v
            else {
                val x = v / 255f
                // Keep deep black sky clean (<0.05) while lifting dark shadows naturally
                val lifted = if (x < 0.04f) x * 0.9f else x.pow(0.76f)
                (lifted * 255f).toInt().coerceIn(0, 255)
            }
        }

        for (y in 0 until h) {
            val yUp = max(0, y - 1) * w
            val yCur = y * w
            val yDn = min(h - 1, y + 1) * w

            for (x in 0 until w) {
                val c = px[yCur + x]
                var r = (c ushr 16) and 0xFF
                var g = (c ushr 8) and 0xFF
                var b = c and 0xFF

                // 1. Edge-Adaptive HD Super-Res Sharpening (with anti-noise gate)
                if (sharpness > 0.05f && x in 1 until (w - 1) && y in 1 until (h - 1)) {
                    val up = px[yUp + x]
                    val dn = px[yDn + x]
                    val lf = px[yCur + x - 1]
                    val rt = px[yCur + x + 1]

                    val avgR = (((up ushr 16) and 0xFF) + ((dn ushr 16) and 0xFF) + ((lf ushr 16) and 0xFF) + ((rt ushr 16) and 0xFF)) ushr 2
                    val avgG = (((up ushr 8) and 0xFF) + ((dn ushr 8) and 0xFF) + ((lf ushr 8) and 0xFF) + ((rt ushr 8) and 0xFF)) ushr 2
                    val avgB = ((up and 0xFF) + (dn and 0xFF) + (lf and 0xFF) + (rt and 0xFF)) ushr 2

                    val dR = r - avgR
                    val dG = g - avgG
                    val dB = b - avgB
                    val edgeMag = (kotlin.math.abs(dR) + kotlin.math.abs(dG) + kotlin.math.abs(dB)) / 3
                    // Gate small sensor noise (< 5), boost crisp details cleanly
                    if (edgeMag > 5) {
                        val gain = min(1.5f, ((edgeMag - 5) / 20f) * sharpness * 1.6f)
                        r = (r + (dR * gain).toInt()).coerceIn(0, 255)
                        g = (g + (dG * gain).toInt()).coerceIn(0, 255)
                        b = (b + (dB * gain).toInt()).coerceIn(0, 255)
                    }
                }

                // 2. Offline Low-Light AI Curve
                if (isNightBoost) {
                    r = nightLiftLut[r]
                    g = nightLiftLut[g]
                    b = nightLiftLut[b]
                }

                // 3. Soft Golden Highlight Bloom
                if (goldenGlow > 0.05f) {
                    val luma = (r * 77 + g * 150 + b * 29) ushr 8
                    if (luma > 185) {
                        val hi = (luma - 185) / 70f * goldenGlow * 28f
                        r = (r + hi * 1.15f).toInt().coerceIn(0, 255)
                        g = (g + hi * 0.90f).toInt().coerceIn(0, 255)
                        b = (b + hi * 0.45f).toInt().coerceIn(0, 255)
                    }
                }

                // 4. 3D LUT Color Grading
                val ri = (r * sMax + 127) / 255
                val gi = (g * sMax + 127) / 255
                val bi = (b * sMax + 127) / 255
                val lutIdx = ((bi * sSize + gi) * sSize + ri) * 3
                val lr = lut[lutIdx]
                val lg = lut[lutIdx + 1]
                val lb = lut[lutIdx + 2]

                // 5. iPhone-style Skin-Tone Protection (keeps selfie & portrait faces natural)
                val rf = r / 255f
                val gf = g / 255f
                val bf = b / 255f
                val cb = -0.168736f * rf - 0.331264f * gf + 0.5f * bf
                val cr = 0.5f * rf - 0.418688f * gf - 0.081312f * bf
                val dCb = (cb + 0.095f) / 0.085f
                val dCr = (cr - 0.125f) / 0.095f
                val skinW = exp(-(dCb * dCb + dCr * dCr) * 1.65f) * skinProtect

                val warmR = (r * 1.03f).toInt().coerceIn(0, 255)
                val warmG = (g * 1.005f).toInt().coerceIn(0, 255)
                val warmB = (b * 0.975f).toInt().coerceIn(0, 255)

                val blendSkin = skinW * 0.70f
                val invSkin = 1f - blendSkin
                val fr = (lr * invSkin + warmR * blendSkin).toInt().coerceIn(0, 255)
                val fg = (lg * invSkin + warmG * blendSkin).toInt().coerceIn(0, 255)
                val fb = (lb * invSkin + warmB * blendSkin).toInt().coerceIn(0, 255)

                out[yCur + x] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
            }
        }

        val graded = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }

        // Mirror front camera photo naturally like preview
        return if (isFrontCamera) {
            val matrix = Matrix().apply { postScale(-1f, 1f, w / 2f, h / 2f) }
            val mirrored = Bitmap.createBitmap(graded, 0, 0, w, h, matrix, true)
            graded.recycle()
            mirrored
        } else {
            graded
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "UltraCam_AI_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/UltraCam-AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        var stream: OutputStream? = null
        try {
            stream = resolver.openOutputStream(uri)
            if (stream != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, stream)
            }
        } finally {
            stream?.close()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
