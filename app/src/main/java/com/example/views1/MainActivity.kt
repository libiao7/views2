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
import kotlin.math.abs
import kotlin.math.sign

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var vlcPlayer: VlcPlayer? = null

    private val currentPlayer: Player
        get() = vlcPlayer ?: player
//    private var usingVlc = false
//    private var surfaceView: SurfaceView? = null

    //    private val vlcListener = object : Player.Listener {
//        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
//            PlaybackHub.setCurrentIndex(currentPlayer.currentMediaItemIndex)
//        }
//    }
    private var startX = 0f
    private var startY = 0f

    // 1. 记录当前的解码模式与最新的播放参数
    private var currentExtensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    private var currentUrl: String? = null
    private var currentSubUrl: String? = null

    @SuppressLint("ClickableViewAccessibility")
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
        playerView.subtitleView?.setStyle(
            CaptionStyleCompat(
                0xFF555555.toInt(),              // 字体颜色
                0x55000000,        // 背景颜色 (设为透明更美观)
                Color.TRANSPARENT,        // 窗口颜色
                CaptionStyleCompat.EDGE_TYPE_NONE, // 边缘类型
                Color.BLACK,              // 边缘颜色
                null                      // 字体 (null 为系统默认)
            )
        )
        playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
//                    playerView.performClick() // 等同于 _.performClick()
                    startX = event.x
                    startY = event.y
                    currentPlayer.pause() // 【核心】触碰即暂停
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
                        // 安全分发 SeekParameters 给对应的播放器
                        when (val p = currentPlayer) {
                            is ExoPlayer -> p.setSeekParameters(seekSync)
                            is VlcPlayer -> p.setSeekParameters(seekSync)
                        }
                        currentPlayer.seekTo(currentPlayer.currentPosition + moveMs)
                        currentPlayer.play()
                    } else if (absDy > 10 && absDy > absDx) {
                        if (dy > 0) { // 向下滑：倍速还原 + 显示控制栏（保持暂停）
                            window.insetsController?.show(WindowInsets.Type.systemBars())
                            playerView.useController = true
                            playerView.showController()
                            currentPlayer.setPlaybackSpeed(1.0f)
//                            currentPlayer.play()
                        } else if (dy < -200) { // 大幅向上滑：切换ZOOM/FIT
                            playerView.resizeMode =
                                if (playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                                    playerView.subtitleView?.setPadding(0, 0, 0, 0)
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                } else {
                                    playerView.subtitleView?.setPadding(0, 0, 0, 120)
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                }
                            currentPlayer.play()
                            playerView.useController = false
                        } else { // 向上滑：2倍速播放
                            currentPlayer.setPlaybackSpeed(currentPlayer.playbackParameters.speed * 2.0f)
                            currentPlayer.play()
                            playerView.useController = false
                        }
                    } else {
                        // 如果播放器处于初始状态或错误状态，必须重新 prepare
                        if (currentPlayer.playbackState == Player.STATE_IDLE) {
                            currentPlayer.prepare()
                        }
                        // 普通松手：继续播放
                        window.insetsController?.hide(WindowInsets.Type.systemBars())
                        currentPlayer.play()
                        playerView.useController = false
                    }
                    true
                }

                else -> false
            }
        }

        applyFullScreen()
        initPlayer(currentExtensionRendererMode)

        // 2. 处理启动意图
        handleIntent(intent)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // 必做：更新当前 Activity 的 intent，否则调用 handleIntent(intent) 拿到的还是旧的
        setIntent(intent)

        // 2. 停止当前正在播放的内容
//        player.stop()
//        player.clearMediaItems()
// 直接调用 handleIntent 即可
        // 只要 handleIntent 内部使用的是 player.setMediaItem(mediaItem)
        // 它会自动帮你把旧的 MediaItem 替换掉，不需要额外手动 clear
        // 3. 处理新的视频数据并开始播放
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        player.pause() // 只要用户看不见界面，就强制暂停
    }

    // 2. 改造 initPlayer 支持传入 extensionRendererMode 参数
    private fun initPlayer(extensionRendererMode: Int) {
        // 如果已存在 player 实例，先释放
        if (::player.isInitialized) {
            player.release()
        }

        player = ExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this).setExtensionRendererMode(extensionRendererMode)
        ).setLoadControl(
            DefaultLoadControl.Builder()
//                .setBufferDurationsMs(
//                    128000, // minBufferMs: 至少缓冲多少
//                    256000, // maxBufferMs: 最多缓冲多少
//                    1500,  // bufferForPlaybackMs: 起播缓冲
//                    2000,  // bufferForPlaybackAfterRebufferMs: 卡顿后重新起播缓冲
//                )
                .setBackBuffer(12000, true) // 核心代码：保留过去 多少毫秒的数据在内存中，不立即丢弃
                .build()
        ).build()
        playerView.player = player
        // 自动旋转逻辑：根据视频宽高比决定横竖屏
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                requestedOrientation = if (videoSize.width > videoSize.height) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 1. 获取最详细的错误追踪文本
                val fullDescription = android.util.Log.getStackTraceString(error)

                // 2. 在控制台打印，方便调试
                android.util.Log.e("PlayerError", "详细错误内容: $fullDescription")

                // 3. 使用 AlertDialog 弹窗显示详细信息并提供用户选项（类似 confirm）
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("播放发生错误")
                    .setMessage(fullDescription) // 内容过多时对话框会自动支持上下滚动
                    .setCancelable(false) // 点击弹窗外部不自动关闭，强制用户做出选择
                    // 3. 修改 PositiveButton 逻辑：切换模式并重试播放
                    .setPositiveButton("音频ffmpeg") { dialog, _ ->
                        dialog.dismiss()

                        // 修改为优先扩展（软解）模式
                        currentExtensionRendererMode =
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        initPlayer(currentExtensionRendererMode)

                        // 重新播放当前记录的媒体资源
                        if (currentUrl != null) {
                            startPlay(currentUrl!!, currentSubUrl)
                        }
                    }
                    .setNeutralButton("复制log") { dialog, _ ->                        // 点击确定按钮：复制到剪贴板
                        val clipboard =
                            getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip =
                            android.content.ClipData.newPlainText("Player Error", fullDescription)
                        clipboard.setPrimaryClip(clip)

                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "已复制到剪贴板",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton("VLC") { dialog, _ ->
                        // 点击取消/关闭按钮：仅关闭弹窗
                        val items =
                            (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                        val index = player.currentMediaItemIndex.coerceAtLeast(0)
                        val position = player.currentPosition.coerceAtLeast(0L)

                        player.stop()
                        player.clearMediaItems()
//                        exoControls.detachVideoOutput()
//                        val view = surfaceView
//                        if (view != null) {
//                            player.clearVideoSurfaceView(view)
//                            (view.parent as? ViewGroup)?.removeView(view)
//                            surfaceView = null
//                        }
                        vlcPlayer?.release()
                        val vlc = VlcPlayer(this@MainActivity).also { vlcPlayer = it }
                        playerView.player = vlc
                        vlc.attachVideoOutput(playerView)
//                        vlc.addListener(vlcListener)
                        vlc.setMediaItems(items, index, position)
//                        vlc.setVideoEnabled(!audioOnly)
                        vlc.prepare()
                        vlc.playWhenReady = true
                        vlc.addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                requestedOrientation = if (videoSize.width > videoSize.height) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                }
                            }

                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                // 1. 获取最详细的错误追踪文本
                                val fullDescription = android.util.Log.getStackTraceString(error)

                                // 2. 在控制台打印，方便调试
                                android.util.Log.e("PlayerError", "详细错误内容: $fullDescription")

                                // 3. 使用 AlertDialog 弹窗显示详细信息并提供用户选项（类似 confirm）
                                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("VLC播放发生错误")
                                    .setMessage(fullDescription) // 内容过多时对话框会自动支持上下滚动
                                    .setCancelable(false) // 点击弹窗外部不自动关闭，强制用户做出选择
                                    // 3. 修改 PositiveButton 逻辑：切换模式并重试播放
                                    .setNeutralButton("复制VLC错误信息") { dialog, _ ->                        // 点击确定按钮：复制到剪贴板
                                        val clipboard =
                                            getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip =
                                            android.content.ClipData.newPlainText(
                                                "VLCPlayer Error",
                                                fullDescription
                                            )
                                        clipboard.setPrimaryClip(clip)

                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "已复制到剪贴板",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        dialog.dismiss()
                                    }
                                    .show()
                            }
                        })

//                        usingVlc = true
//                        mediaSession?.player = vlc
//                        PlaybackHub.setPlayer(vlc, vlc)
//                        applyOrder()
//                        // libVLC no expone sesión de audio: los efectos del sistema no le llegan.
//                        enhancer.release()
//                        PlaybackHub.setAudioCapabilities(null)

                        dialog.dismiss()
                    }
                    .show()
            }
        })

    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return

        if (data.scheme == "pauseplayer") {
            val segments = data.pathSegments

            // 提取加密后的 URL (必选)
            val encodedUrl = segments.getOrNull(0) ?: return
            val url = Uri.decode(String(Base64.decode(encodedUrl, Base64.DEFAULT)))

            // 提取加密后的 字幕URL (可选)
            val subURL = segments.getOrNull(1)?.let {
                Uri.decode(String(Base64.decode(it, Base64.DEFAULT)))
            }

            startPlay(url, subURL)
        } else if (intent.action == Intent.ACTION_VIEW) {
            // 处理从文件管理器打开的情况
            startPlay(data.toString(), null)
        }
    }

    private fun startPlay(url: String, subURL: String?) {
        // 4. 保存当前播放参数，以便重新创建 Player 时重试
        this.currentUrl = url
        this.currentSubUrl = subURL
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
                .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
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
        if (::player.isInitialized) {
            player.release()
        }
        vlcPlayer?.release() // 释放 VLC 资源
        vlcPlayer = null
    }
}