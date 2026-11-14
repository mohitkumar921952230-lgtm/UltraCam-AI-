package com.mohit.ultracam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

class UltraGlSurfaceProcessor(
    private val gyroStabilizer: GyroStabilizer
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val glThread = HandlerThread("UltraCam-GL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    val glExecutor: Executor = Executor { runnable ->
        if (!isReleased.get()) glHandler.post(runnable)
    }

    private val isReleased = AtomicBoolean(false)

    // Real-time adjustable parameters
    @Volatile var targetLut: LutType = LutType.IPHONE_NATURAL
    @Volatile var skinToneProtection: Float = 0.72f
    @Volatile var goldenGlowAmount: Float = 0.25f
    @Volatile var sharpnessAmount: Float = 0.45f
    @Volatile var gpuZoomFactor: Float = 1.0f
    @Volatile var stabilizationEnabled: Boolean = true

    private var activeLut: LutType? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId = 0
    private var externalTexId = 0
    private var lutTexId = 0

    // Shader locations
    private var aPosLoc = -1
    private var aTexLoc = -1
    private var uTexMatrixLoc = -1
    private var uExternalTexLoc = -1
    private var uLutTexLoc = -1
    private var uTexelSizeLoc = -1
    private var uSkinProtectLoc = -1
    private var uGlowLoc = -1
    private var uSharpnessLoc = -1
    private var uZoomScaleLoc = -1
    private var uStabOffsetLoc = -1
    private var uStabRotLoc = -1

    private val texMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    private val activeInputTextures = mutableSetOf<SurfaceTexture>()
    private val outputSurfaces = mutableMapOf<SurfaceOutput, EGLSurface>()

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

    init {
        glExecutor.execute {
            initEgl()
            initGlProgram()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    }

    private fun initGlProgram() {
        val vShader = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uZoomScale;
            uniform vec2 uStabOffset;
            uniform vec2 uStabRot; // cos(roll), sin(roll)
            out vec2 vTexCoord;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                // Center-origin crop/zoom + gyro virtual gimbal compensation in screen space
                vec2 centered = aTexCoord - 0.5;
                centered /= uZoomScale;
                vec2 rotated = vec2(
                    centered.x * uStabRot.x - centered.y * uStabRot.y,
                    centered.x * uStabRot.y + centered.y * uStabRot.x
                );
                vec2 stabilizedUv = clamp(rotated + 0.5 + uStabOffset, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(stabilizedUv, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fShader = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;

            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform samplerExternalOES uExternalTex;
            uniform sampler3D uLutTex;
            uniform vec2 uTexelSize;
            uniform float uSkinProtect;
            uniform float uGlow;
            uniform float uSharpness;

            float getSkinWeight(vec3 rgb) {
                float cb = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b;
                float cr =  0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
                float dCb = (cb + 0.095) / 0.085;
                float dCr = (cr - 0.125) / 0.095;
                float dist = dCb * dCb + dCr * dCr;
                return exp(-dist * 1.65);
            }

            vec3 sampleLut(vec3 c) {
                vec3 scale = vec3(31.0 / 32.0);
                vec3 offset = vec3(0.5 / 32.0);
                return texture(uLutTex, clamp(c, 0.0, 1.0) * scale + offset).rgb;
            }

            void main() {
                vec3 center = texture(uExternalTex, vTexCoord).rgb;

                // 1. Edge-Adaptive HD Sharpening (CAS-style, prevents pixelation on zoom)
                vec3 n = texture(uExternalTex, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                vec3 s = texture(uExternalTex, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
                vec3 e = texture(uExternalTex, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 w = texture(uExternalTex, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;

                vec3 blur = (n + s + e + w) * 0.25;
                vec3 detail = center - blur;
                float edgeMag = dot(abs(detail), vec3(0.333));
                // Avoid amplifying flat sensor noise while sharpening true edges
                float adaptiveGain = smoothstep(0.012, 0.08, edgeMag) * uSharpness * 1.45;
                vec3 sharp = clamp(center + detail * adaptiveGain, 0.0, 1.0);

                // 2. Soft Golden Highlight Bloom (dreamy sun/light glow)
                if (uGlow > 0.01) {
                    vec3 bloomTap = (
                        texture(uExternalTex, vTexCoord + uTexelSize * 3.5).rgb +
                        texture(uExternalTex, vTexCoord - uTexelSize * 3.5).rgb +
                        texture(uExternalTex, vTexCoord + vec2(uTexelSize.x, -uTexelSize.y) * 3.5).rgb +
                        texture(uExternalTex, vTexCoord + vec2(-uTexelSize.x, uTexelSize.y) * 3.5).rgb
                    ) * 0.25;
                    float hi = max(0.0, dot(bloomTap, vec3(0.299, 0.587, 0.114)) - 0.70);
                    sharp += bloomTap * vec3(1.10, 0.96, 0.78) * hi * uGlow * 0.85;
                    sharp = clamp(sharp, 0.0, 1.0);
                }

                // 3. 3D LUT Color Grading
                vec3 graded = sampleLut(sharp);

                // 4. iPhone-style Skin-Tone Protection (natural warm face even with heavy LUTs)
                float skinMask = getSkinWeight(sharp) * uSkinProtect;
                vec3 naturalWarmSkin = clamp(sharp * vec3(1.03, 1.005, 0.975), 0.0, 1.0);
                vec3 finalRgb = mix(graded, mix(graded, naturalWarmSkin, 0.68), skinMask);

                fragColor = vec4(finalRgb, 1.0);
            }
        """.trimIndent()

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vShader)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fShader)
        programId = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs)
            GLES30.glAttachShader(it, fs)
            GLES30.glLinkProgram(it)
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        uTexMatrixLoc = GLES30.glGetUniformLocation(programId, "uTexMatrix")
        uExternalTexLoc = GLES30.glGetUniformLocation(programId, "uExternalTex")
        uLutTexLoc = GLES30.glGetUniformLocation(programId, "uLutTex")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
        uSkinProtectLoc = GLES30.glGetUniformLocation(programId, "uSkinProtect")
        uGlowLoc = GLES30.glGetUniformLocation(programId, "uGlow")
        uSharpnessLoc = GLES30.glGetUniformLocation(programId, "uSharpness")
        uZoomScaleLoc = GLES30.glGetUniformLocation(programId, "uZoomScale")
        uStabOffsetLoc = GLES30.glGetUniformLocation(programId, "uStabOffset")
        uStabRotLoc = GLES30.glGetUniformLocation(programId, "uStabRot")

        val texIds = IntArray(2)
        GLES30.glGenTextures(2, texIds, 0)
        externalTexId = texIds[0]
        lutTexId = texIds[1]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        uploadLutIfNeeded()
    }

    private fun uploadLutIfNeeded() {
        val desired = targetLut
        if (desired == activeLut) return
        val buffer = LutPresets.generateLutBuffer(desired)
        val size = LutPresets.LUT_SIZE
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8,
            size, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        activeLut = desired
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        return shader
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (isReleased.get()) {
            request.willNotProvideSurface()
            return
        }
        val surfaceTexture = SurfaceTexture(externalTexId).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
            setOnFrameAvailableListener(this@UltraGlSurfaceProcessor, glHandler)
        }
        activeInputTextures.add(surfaceTexture)
        val surface = Surface(surfaceTexture)
        request.provideSurface(surface, glExecutor) {
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            surface.release()
            activeInputTextures.remove(surfaceTexture)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (isReleased.get()) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(glExecutor) {
            surfaceOutput.close()
            outputSurfaces.remove(surfaceOutput)?.let { eglSurf ->
                EGL14.eglDestroySurface(eglDisplay, eglSurf)
            }
        }
        val eglSurf = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        outputSurfaces[surfaceOutput] = eglSurf
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleased.get() || !activeInputTextures.contains(surfaceTexture)) return

        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val timestamp = surfaceTexture.timestamp

        uploadLutIfNeeded()

        val stabCrop = if (stabilizationEnabled) 1.12f else 1.0f
        val effectiveZoom = (gpuZoomFactor * stabCrop).coerceAtLeast(1.0f)
        val sx = if (stabilizationEnabled) gyroStabilizer.shiftX else 0f
        val sy = if (stabilizationEnabled) gyroStabilizer.shiftY else 0f
        val roll = if (stabilizationEnabled) gyroStabilizer.rollRad else 0f

        for ((surfaceOutput, eglSurf) in outputSurfaces.entries) {
            surfaceOutput.updateTransformMatrix(outputMatrix, texMatrix)
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)) continue

            val w = surfaceOutput.size.width.coerceAtLeast(1)
            val h = surfaceOutput.size.height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(programId)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexId)
            GLES30.glUniform1i(uExternalTexLoc, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glUniform1i(uLutTexLoc, 1)

            GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, outputMatrix, 0)
            GLES30.glUniform2f(uTexelSizeLoc, 1f / w, 1f / h)
            GLES30.glUniform1f(uSkinProtectLoc, skinToneProtection)
            GLES30.glUniform1f(uGlowLoc, goldenGlowAmount)
            GLES30.glUniform1f(uSharpnessLoc, sharpnessAmount)
            GLES30.glUniform1f(uZoomScaleLoc, effectiveZoom)
            GLES30.glUniform2f(uStabOffsetLoc, sx, sy)
            GLES30.glUniform2f(uStabRotLoc, cos(roll), sin(roll))

            quadVertices.position(0)
            GLES30.glEnableVertexAttribArray(aPosLoc)
            GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

            quadVertices.position(2)
            GLES30.glEnableVertexAttribArray(aTexLoc)
            GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurf, timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurf)
        }
    }

    fun release() {
        if (isReleased.getAndSet(true)) return
        glHandler.post {
            outputSurfaces.values.forEach { EGL14.eglDestroySurface(eglDisplay, it) }
            outputSurfaces.clear()
            activeInputTextures.forEach { it.release() }
            activeInputTextures.clear()
            if (programId != 0) GLES30.glDeleteProgram(programId)
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
            glThread.quitSafely()
        }
    }
}
