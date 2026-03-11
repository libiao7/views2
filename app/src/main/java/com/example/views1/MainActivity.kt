package com.example.views1

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlin.math.abs
import kotlin.math.sign

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var startX = 0f
    private var startY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        // 1. 初始化界面逻辑
        playerView = findViewById(R.id.playerView)
        applyFullScreen()
        initPlayer()

        // 2. 处理启动意图
        handleIntent(intent)

    }

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                128000, // minBufferMs: 至少缓冲多少
                256000, // maxBufferMs: 最多缓冲多少
                1500,  // bufferForPlaybackMs: 起播缓冲
                2000,  // bufferForPlaybackAfterRebufferMs: 卡顿后重新起播缓冲
            )
            .setBackBuffer(128000, true) // 核心代码：保留过去 多少毫秒的数据在内存中，不立即丢弃
            .build()

// 使用这个 loadControl 初始化 player
        // 1. 显式创建支持扩展的工厂
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
//        player = ExoPlayer.Builder(this).build()
        playerView.player = player
// --- 修改字幕样式开始 ---
        val subtitleView = playerView.subtitleView
        if (subtitleView != null) {
            // 创建自定义样式：白色文字、透明背景、带黑色阴影边缘
            val style = CaptionStyleCompat(
                0xFF555555.toInt(),              // 字体颜色
                0x55000000,        // 背景颜色 (设为透明更美观)
                Color.TRANSPARENT,        // 窗口颜色
                CaptionStyleCompat.EDGE_TYPE_NONE, // 边缘类型：阴影
                Color.BLACK,              // 边缘颜色
                null                      // 字体 (null 为系统默认)
            )

            subtitleView.setStyle(style)

            // 设置字幕大小：基于播放器高度的比例 (0.053f 是一个比较舒服的默认值)
            // 你可以尝试 0.06f 让字体更大一些
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.1f)
        }
        // --- 修改字幕样式结束 ---
        // 自动旋转逻辑：根据视频宽高比决定横竖屏
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                requestedOrientation = if (videoSize.width > videoSize.height) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        })

        setupTouchLogic()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return

        if (data.scheme == "pauseplayer") {
            val segments = data.pathSegments

            // 提取加密后的 URL (必选)
            val encodedUrl = segments.getOrNull(0) ?: return
            val url = String(Base64.decode(encodedUrl, Base64.DEFAULT))

            // 提取加密后的 字幕URL (可选)
            val subURL = segments.getOrNull(1)?.let {
                String(Base64.decode(it, Base64.DEFAULT))
            }

            startPlay(url, subURL)
        } else if (intent?.action == Intent.ACTION_VIEW) {
            // 处理从文件管理器打开的情况
            startPlay(data.toString(), null)
        }
    }

    private fun startPlay(url: String, subURL: String?) {
        val uri = url.toUri()

        // 创建 MediaItem
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)

        if (subURL != null) {
            val mimeType = when {
                subURL.endsWith(".srt", ignoreCase = true) -> "application/x-subrip"
                subURL.endsWith(".ass", ignoreCase = true) -> "text/x-ssa"
                else -> "text/plain"
            }
            mediaItemBuilder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subURL.toUri())
                        .setMimeType(mimeType)
                        .setLanguage(mimeType.substringAfterLast('-'))
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        val mediaItem = mediaItemBuilder.build()

        // 根据 URL 类型选择合适的 DataSource
        if (uri.scheme?.startsWith("http") == true) {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)

            val userInfo = uri.userInfo
            if (!userInfo.isNullOrEmpty()) {
                val authHeader =
                    "Basic " + Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
                httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to authHeader))
            }

            player.setMediaSource(
                DefaultMediaSourceFactory(httpDataSourceFactory)
                    .createMediaSource(mediaItem)
            )
        } else {
            // 本地文件或 Content Provider
            player.setMediaItem(mediaItem)
        }

        player.prepare()
        player.play()
    }

    @SuppressLint("ClickableViewAccessibility") // 加在方法或者类上方
    private fun setupTouchLogic() {
        playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
//                    playerView.performClick() // 等同于 _.performClick()
                    startX = event.x
                    startY = event.y
                    player.pause() // 【核心】触碰即暂停
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val density = resources.displayMetrics.density
                    val dx = (event.x - startX) / density
                    val dy = (event.y - startY) / density
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    if (absDx > 10 && absDx > absDy) {
                        // 横向滑动：快进/快退 (基于你的 JS 公式)
                        val moveMs = (dx * dx).toLong() * sign(dx).toLong()
                        val seekSync = if (moveMs < 0 && moveMs > -5000) {
                            SeekParameters.EXACT
                        } else if (moveMs < 0) {
                            SeekParameters.PREVIOUS_SYNC
                        } else if (moveMs > 0) {
                            SeekParameters.NEXT_SYNC
                        } else {
                            SeekParameters.CLOSEST_SYNC
                        }
                        player.setSeekParameters(seekSync)
                        player.seekTo(player.currentPosition + moveMs)
                        player.play()
                    } else if (absDy > 10 && absDy > absDx) {
                        if (dy > 0) { // 向下滑：倍速还原 + 显示控制栏（保持暂停）
                            window.insetsController?.show(WindowInsets.Type.systemBars())
                            playerView.useController = true
                            playerView.showController()
                            player.setPlaybackSpeed(1.0f)
//                            player.play()
                        } else if (dy < -200) { // 大幅向上滑：切换ZOOM/FIT
                            playerView.resizeMode =
                                if (playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                                    playerView.subtitleView?.setPadding(0, 0, 0, 0)
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                } else {
                                    playerView.subtitleView?.setPadding(0, 0, 0, 120)
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                }
                            player.play()
                            playerView.useController = false
                        } else { // 向上滑：2倍速播放
                            player.setPlaybackSpeed(2.0f)
                            player.play()
                            playerView.useController = false
                        }
                    } else {
                        // 如果播放器处于初始状态或错误状态，必须重新 prepare
                        if (player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        }
                        // 普通松手：继续播放
                        window.insetsController?.hide(WindowInsets.Type.systemBars())
                        player.play()
                        playerView.useController = false
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun applyFullScreen() {
        // 针对 Android 16 的沉浸式处理，隐藏三大按键导航栏
//        window.setDecorFitsSystemWindows(false)
        val controller = window.insetsController
        if (controller != null) {
//            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 释放资源
    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}