
package com.example.views1

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * VR180 equirectangular SBS video renderer.
 *
 * ExoPlayer decodes directly into the Surface backed by SurfaceTexture.
 * OpenGL renders a perspective view for each eye without CPU frame copies.
 */
class Vr180SbsGlSurfaceView(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val vrRenderer: Vr180Renderer

    /**
     * Called on the main thread when the decoder output Surface is ready.
     */
    var onVideoSurfaceAvailable: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = false

        vrRenderer = Vr180Renderer(
            screenXDpi = resources.displayMetrics.xdpi,
            onSurfaceReady = { surface ->
                mainHandler.post {
                    onVideoSurfaceAvailable?.invoke(surface)
                }
            },
            requestRender = { requestRender() }
        )

        setRenderer(vrRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onResume() {
        super.onResume()
        requestRender()
    }

    /**
     * Adjust the viewing direction.
     * Positive yaw moves the view horizontally.
     * Positive pitch moves the view vertically.
     */
    fun adjustView(yawDelta: Float, pitchDelta: Float) {
        queueEvent {
            vrRenderer.adjustView(yawDelta, pitchDelta)
            requestRender()
        }
    }

    /**
     * scaleFactor > 1: zoom in.
     * scaleFactor < 1: zoom out.
     */
    fun adjustZoom(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        queueEvent {
            vrRenderer.adjustZoom(scaleFactor)
            requestRender()
        }
    }

    /** Cycles the three supported fisheye-style projections and reports the active one. */
    fun cycleProjection(onChanged: (String) -> Unit) {
        queueEvent {
            val projectionName = vrRenderer.cycleProjection()
            requestRender()
            mainHandler.post { onChanged(projectionName) }
        }
    }

    /** Shifts the two source-eye images in opposite horizontal directions. */
    fun adjustStereoOffset(delta: Float) {
        queueEvent {
            vrRenderer.adjustStereoOffset(delta)
            requestRender()
        }
    }

    /**
     * Call after ExoPlayer has detached from the Surface.
     */
    fun releaseRenderer() {
        queueEvent {
            vrRenderer.release()
        }
    }

    private class Vr180Renderer(
        private val screenXDpi: Float,
        private val onSurfaceReady: (Surface) -> Unit,
        private val requestRender: () -> Unit
    ) : Renderer {

        private var program = 0
        private var textureId = 0

        // Shader uniform handles.
        private var yawHandle = 0
        private var pitchHandle = 0
        private var projectionHandle = 0
        private var stereoOffsetHandle = 0
        private var ipdOffsetHandle = 0

        // User-adjustable viewing parameters.
        private var fovDegrees = VERTICAL_FOV_DEGREES.toFloat()
        private var yawDegrees = 0f
        private var pitchDegrees = 0f
        private var projectionMode = PROJECTION_STEREOGRAPHIC
        private var stereoOffset = 0f

        private var surfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var frameAvailable = false

        private val textureMatrix = FloatArray(16)

        private var positionHandle = 0
        private var texMatrixHandle = 0
        private var eyeHandle = 0
        private var halfFovHandle = 0
        private var aspectHandle = 0
        private var samplerHandle = 0

        private val vertices: FloatBuffer = ByteBuffer
            .allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                // x, y, u, v; triangle strip.
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 0f,
                        1f, -1f, 1f, 0f,
                        -1f,  1f, 0f, 1f,
                        1f,  1f, 1f, 1f
                    )
                )
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            eyeHandle = GLES20.glGetUniformLocation(program, "uEye")
            halfFovHandle =
                GLES20.glGetUniformLocation(program, "uHalfFovRadians")
            aspectHandle = GLES20.glGetUniformLocation(program, "uAspect")
            samplerHandle = GLES20.glGetUniformLocation(program, "uVideo")
            yawHandle = GLES20.glGetUniformLocation(program, "uYaw")
            pitchHandle = GLES20.glGetUniformLocation(program, "uPitch")
            projectionHandle = GLES20.glGetUniformLocation(program, "uProjectionMode")
            stereoOffsetHandle = GLES20.glGetUniformLocation(program, "uStereoOffset")
            ipdOffsetHandle = GLES20.glGetUniformLocation(program, "uIpdOffset")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]

            GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                textureId
            )

            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )

            val st = SurfaceTexture(textureId)
            surfaceTexture = st

            st.setOnFrameAvailableListener {
                synchronized(this) {
                    frameAvailable = true
                }
                requestRender()
            }

            val surface = Surface(st)
            decoderSurface = surface
            onSurfaceReady(surface)
        }

        override fun onSurfaceChanged(
            gl: GL10?,
            width: Int,
            height: Int
        ) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)

            GLES20.glViewport(
                0,
                0,
                surfaceWidth,
                surfaceHeight
            )
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture

            if (st != null) {
                val shouldUpdate = synchronized(this) {
                    val value = frameAvailable
                    frameAvailable = false
                    value
                }

                if (shouldUpdate) {
                    try {
                        st.updateTexImage()
                        st.getTransformMatrix(textureMatrix)
                    } catch (_: RuntimeException) {
                        // Surface may be concurrently torn down.
                    }
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (program == 0 || textureId == 0) return

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                textureId
            )

            GLES20.glUniform1i(samplerHandle, 0)
            GLES20.glUniformMatrix4fv(
                texMatrixHandle,
                1,
                false,
                textureMatrix,
                0
            )

            vertices.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertices
            )

            // The output canvas is SBS: each eye gets half the width.
            val eyeWidth = (surfaceWidth / 2).coerceAtLeast(1)
            val aspect = eyeWidth.toFloat() / surfaceHeight.toFloat()

            val halfFovRadians =
                Math.toRadians(fovDegrees.toDouble() / 2.0).toFloat()

            GLES20.glUniform1f(yawHandle, yawDegrees)
            GLES20.glUniform1f(pitchHandle, pitchDegrees)
            GLES20.glUniform1i(projectionHandle, projectionMode)
            GLES20.glUniform1f(stereoOffsetHandle, stereoOffset)

            // Shift each eye's optical axis inward so the two axes are 58 mm apart.
            // The shift is normalized to each half-screen viewport.
            val physicalScreenWidthMm = if (screenXDpi > 0f) {
                surfaceWidth * 25.4f / screenXDpi
            } else {
                158f // Fallback for the 3200 px / 158 mm target display.
            }
            val ipdOffset = ((physicalScreenWidthMm - 2f * TARGET_IPD_MM) /
                physicalScreenWidthMm).coerceAtLeast(0f)
            GLES20.glUniform1f(ipdOffsetHandle, ipdOffset)

            for (eye in 0..1) {
                GLES20.glViewport(
                    eye * eyeWidth,
                    0,
                    eyeWidth,
                    surfaceHeight
                )

                GLES20.glUniform1f(eyeHandle, eye.toFloat())
                GLES20.glUniform1f(aspectHandle, aspect)
                GLES20.glUniform1f(
                    halfFovHandle,
                    halfFovRadians
                )

                GLES20.glDrawArrays(
                    GLES20.GL_TRIANGLE_STRIP,
                    0,
                    4
                )
            }

            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        fun adjustView(yawDelta: Float, pitchDelta: Float) {
            yawDegrees = (yawDegrees + yawDelta).coerceIn(-85f, 85f)
            pitchDegrees =
                (pitchDegrees + pitchDelta).coerceIn(-80f, 80f)
        }

        fun adjustZoom(scaleFactor: Float) {
            if (scaleFactor <= 0f) return
            fovDegrees = (fovDegrees / scaleFactor).coerceIn(35f, 180f)
        }

        fun cycleProjection(): String {
            projectionMode = (projectionMode + 1) % PROJECTION_COUNT
            fovDegrees = fovDegrees.coerceAtMost(180f)
            return when (projectionMode) {
                PROJECTION_STEREOGRAPHIC -> "球极平面投影（Stereographic）"
                PROJECTION_EQUIDISTANT -> "等距鱼眼投影（Equidistant）"
                PROJECTION_EQUISOLID -> "等立体角投影（Equisolid）"
                else -> error("Unsupported projection mode: $projectionMode")
            }
        }

        fun adjustStereoOffset(delta: Float) {
            stereoOffset = (stereoOffset + delta * 0.0005f).coerceIn(-0.08f, 0.08f)
        }

        fun release() {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null

            decoderSurface?.release()
            decoderSurface = null

            if (textureId != 0) {
                GLES20.glDeleteTextures(
                    1,
                    intArrayOf(textureId),
                    0
                )
                textureId = 0
            }

            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun createProgram(
            vertexSource: String,
            fragmentSource: String
        ): Int {
            val vertex = compileShader(
                GLES20.GL_VERTEX_SHADER,
                vertexSource
            )
            val fragment = compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                fragmentSource
            )

            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)

            val status = IntArray(1)
            GLES20.glGetProgramiv(
                result,
                GLES20.GL_LINK_STATUS,
                status,
                0
            )

            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(result)
                GLES20.glDeleteProgram(result)
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                throw IllegalStateException(
                    "VR180 GL program link failed: $log"
                )
            }

            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)

            return result
        }

        private fun compileShader(
            type: Int,
            source: String
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val status = IntArray(1)
            GLES20.glGetShaderiv(
                shader,
                GLES20.GL_COMPILE_STATUS,
                status,
                0
            )

            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException(
                    "VR180 GL shader compile failed: $log"
                )
            }

            return shader
        }

        companion object {
            private const val VERTICAL_FOV_DEGREES = 83.97
            private const val TARGET_IPD_MM = 58f
            private const val PROJECTION_STEREOGRAPHIC = 0
            private const val PROJECTION_EQUIDISTANT = 1
            private const val PROJECTION_EQUISOLID = 2
            private const val PROJECTION_COUNT = 3

            private const val VERTEX_SHADER = """
                attribute vec2 aPosition;
                varying vec2 vScreen;

                void main() {
                    vScreen = aPosition;
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision highp float;

                uniform samplerExternalOES uVideo;
                uniform mat4 uTexMatrix;
                uniform float uEye;
                uniform float uHalfFovRadians;
                uniform float uAspect;
                uniform float uYaw;
                uniform float uPitch;
                uniform int uProjectionMode;
                uniform float uStereoOffset;
                uniform float uIpdOffset;

                varying vec2 vScreen;

                const float PI = 3.14159265358979323846;

                void main() {
                    // Inverse-map screen radius using the selected standard projection.
                    // uHalfFovRadians is the vertical half-FOV. Each projection uses
                    // its own radial function, scaled so the vertical edge midpoint
                    // corresponds to that same spherical angle.
                    float eyeAxisShift = uEye < 0.5 ? uIpdOffset : -uIpdOffset;
                    vec2 screen = vec2((vScreen.x - eyeAxisShift) * uAspect, vScreen.y);
                    float radius = length(screen);
                    float alpha = uHalfFovRadians;
                    float theta = 0.0;
                    bool projectionValid = true;

                    if (uProjectionMode == 0) {
                        // Stereographic: rho = tan(theta/2) / tan(alpha/2).
                        theta = 2.0 * atan(radius * tan(alpha * 0.5));
                        projectionValid = theta < 3.1415925;
                    } else if (uProjectionMode == 1) {
                        // Equidistant: rho = theta / alpha.
                        theta = radius * alpha;
                        projectionValid = theta <= 3.1415925;
                    } else {
                        // Equisolid: rho = sin(theta/2) / sin(alpha/2).
                        float value = radius * sin(alpha * 0.5);
                        projectionValid = value <= 1.0;
                        if (projectionValid) {
                            theta = 2.0 * asin(value);
                        }
                    }

                    float sinThetaOverRadius = radius > 0.000001
                        ? sin(theta) / radius
                        : 0.0;
                    vec3 ray = vec3(
                        screen.x * sinThetaOverRadius,
                        screen.y * sinThetaOverRadius,
                        -cos(theta)
                    );

                    // Rotate the view ray horizontally and vertically.
                    float yaw = radians(uYaw);
                    float pitch = radians(uPitch);

                    float cy = cos(yaw);
                    float sy = sin(yaw);
                    float cp = cos(pitch);
                    float sp = sin(pitch);

                    vec3 yawRay = vec3(
                        cy * ray.x + sy * ray.z,
                        ray.y,
                        -sy * ray.x + cy * ray.z
                    );

                    vec3 viewRay = vec3(
                        yawRay.x,
                        cp * yawRay.y - sp * yawRay.z,
                        sp * yawRay.y + cp * yawRay.z
                    );

                    float longitude = atan(viewRay.x, -viewRay.z);
                    float latitude = asin(
                        clamp(viewRay.y, -1.0, 1.0)
                    );

                    // Select the left or right source half.
                    float eyeU = longitude / PI + 0.5;
                    float eyeOffset = uEye < 0.5 ? uStereoOffset : -uStereoOffset;
                    float sourceU = (uEye + eyeU + eyeOffset) * 0.5;
                    float sourceV = 0.5 + latitude / PI;

                    vec2 uv = (
                        uTexMatrix *
                        vec4(sourceU, sourceV, 0.0, 1.0)
                    ).xy;

                    // Pixels outside the forward 180-degree hemisphere are black.
                    if (!projectionValid || abs(longitude) > PI * 0.5 || sourceU < uEye * 0.5 || sourceU > (uEye + 1.0) * 0.5) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    } else {
                        gl_FragColor = texture2D(uVideo, uv);
                    }
                }
            """
        }
    }
}
