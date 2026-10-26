package com.example.views1

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/**
 * First rendering prototype for VR180 equirectangular SBS video.
 *
 * The decoder writes into the Surface created from [SurfaceTexture]. OpenGL samples
 * that external texture and renders a perspective view for each eye into one SBS
 * canvas. No decoded frame is copied to the CPU and no pre-transcoding is required.
 *
 * Connect [onVideoSurfaceAvailable] to ExoPlayer.setVideoSurface(). The caller must
 * clear the player's video surface before releasing this view.
 */
class Vr180SbsGlSurfaceView(context: Context) : GLSurfaceView(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val vrRenderer: Vr180Renderer

    /**
     * Called on the main thread when the decoder output Surface is ready.
     * Set ExoPlayer's video output to this Surface.
     */
    var onVideoSurfaceAvailable: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = false
        vrRenderer = Vr180Renderer { surface ->
            mainHandler.post {
                onVideoSurfaceAvailable?.invoke(surface)
            }
        }
        setRenderer(vrRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onResume() {
        super.onResume()
        requestRender()
    }

    /**
     * Call after ExoPlayer has been detached from the Surface and before discarding this view.
     */
    fun releaseRenderer() {
        // Ensure GL resources are released while the GL thread/context still exist.
        queueEvent { vrRenderer.release() }
    }

    private class Vr180Renderer(
        private val onSurfaceReady: (Surface) -> Unit
    ) : Renderer {

        private var program = 0
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var decoderSurface: Surface? = null
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var frameAvailable = false
        private val textureMatrix = FloatArray(16)
        private var positionHandle = 0
        private var texMatrixHandle = 0
        private var eyeHandle = 0
        private var tanHalfFovHandle = 0
        private var aspectHandle = 0
        private var samplerHandle = 0

        private val vertices: FloatBuffer = ByteBuffer
            .allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                // x, y, u, v; triangle strip
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                ))
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            eyeHandle = GLES20.glGetUniformLocation(program, "uEye")
            tanHalfFovHandle = GLES20.glGetUniformLocation(program, "uTanHalfFovY")
            aspectHandle = GLES20.glGetUniformLocation(program, "uAspect")
            samplerHandle = GLES20.glGetUniformLocation(program, "uVideo")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
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
                synchronized(this) { frameAvailable = true }
                requestRender()
            }
            val surface = Surface(st)
            decoderSurface = surface
            onSurfaceReady(surface)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
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
                        // Surface may be concurrently torn down; next frame can recover.
                    }
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0 || textureId == 0) return

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(samplerHandle, 0)
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureMatrix, 0)

            vertices.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertices)

            // Draw one viewport per eye. Output canvas is SBS; each eye gets half the width.
            val eyeWidth = (surfaceWidth / 2).coerceAtLeast(1)
            val aspect = eyeWidth.toFloat() / surfaceHeight.toFloat()
            val tanHalfFovY = tan(Math.toRadians(VERTICAL_FOV_DEGREES / 2.0)).toFloat()

            for (eye in 0..1) {
                GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, surfaceHeight)
                GLES20.glUniform1f(eyeHandle, eye.toFloat())
                GLES20.glUniform1f(aspectHandle, aspect)
                GLES20.glUniform1f(tanHalfFovHandle, tanHalfFovY)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        fun release() {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            decoderSurface?.release()
            decoderSurface = null
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(result)
                GLES20.glDeleteProgram(result)
                throw IllegalStateException("VR180 GL program link failed: $log")
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return result
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("VR180 GL shader compile failed: $log")
            }
            return shader
        }

        companion object {
            private const val VERTICAL_FOV_DEGREES = 83.97

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
                uniform float uTanHalfFovY;
                uniform float uAspect;
                varying vec2 vScreen;

                const float PI = 3.14159265358979323846;

                void main() {
                    // Perspective camera ray; camera looks along -Z.
                    vec3 ray = normalize(vec3(
                        vScreen.x * uAspect * uTanHalfFovY,
                        vScreen.y * uTanHalfFovY,
                        -1.0
                    ));

                    // VR180 equirectangular mapping: longitude spans -90..+90 degrees.
                    float longitude = atan(ray.x, -ray.z);
                    float latitude = asin(clamp(ray.y, -1.0, 1.0));

                    // Source texture is SBS. Select the left or right source half.
                    float eyeU = longitude / PI + 0.5;
                    float sourceU = (uEye + eyeU) * 0.5;
                    float sourceV = 0.5 + latitude / PI;
                    vec2 uv = (uTexMatrix * vec4(sourceU, sourceV, 0.0, 1.0)).xy;

                    // Pixels outside the forward 180-degree hemisphere are black.
                    if (abs(longitude) > PI * 0.5) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    } else {
                        gl_FragColor = texture2D(uVideo, uv);
                    }
                }
            """
        }
    }
}
