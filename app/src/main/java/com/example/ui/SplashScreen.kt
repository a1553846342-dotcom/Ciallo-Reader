package com.example.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.PreferencesManager
import com.example.ui.theme.clickableWithFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SplashScreen"

@Composable
fun SplashScreen(
    prefs: PreferencesManager,
    onSplashFinished: () -> Unit
) {
    val context = LocalContext.current
    val scale = remember { Animatable(1.05f) }
    val alpha = remember { Animatable(0f) }
    val splashQuotes = remember(context) {
        val array = context.resources.getStringArray(R.array.splash_quotes)
        if (array.isNotEmpty()) array else arrayOf("如果不战斗，就无法获胜！ ——《进击的巨人》")
    }
    val randomQuote = remember(splashQuotes) { splashQuotes.random() }

    val customPoster = prefs.customSplashPosterUri
    val isPureMode = prefs.splashPureMode

    // 默认海报改为程序化绘制的三套主题（不依赖已损坏的 drawable 图片）
    val defaultPosterStyle = remember { (0..2).random() }

    // 自定义海报：等解码真正完成后再判断成功/失败，避免“还没解码完就被当成失败”
    var customBitmap by remember(customPoster) { mutableStateOf<ImageBitmap?>(null) }
    var customDecodeFinished by remember(customPoster) { mutableStateOf(false) }

    LaunchedEffect(customPoster) {
        if (!customPoster.isNullOrEmpty()) {
            customBitmap = withContext(Dispatchers.IO) {
                decodeSplashBitmap(context, customPoster)
            }
            if (customBitmap == null) {
                Log.w(TAG, "Custom splash poster failed to decode, falling back to default.")
            }
        }
        customDecodeFinished = true
    }

    var isFinishedCalled by remember { mutableStateOf(false) }
    val safeOnSplashFinished = remember {
        {
            if (!isFinishedCalled) {
                isFinishedCalled = true
                Log.d(TAG, "Navigating away from splash screen")
                onSplashFinished()
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            scale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 800)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scale animation interrupted or failed", e)
        }
    }

    LaunchedEffect(Unit) {
        if (isPureMode) {
            // 纯净模式：不显示任何开屏海报，直接进入软件
            safeOnSplashFinished()
            return@LaunchedEffect
        }
        try {
            alpha.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 400)
            )
            // 开屏海报最长停留 900ms（可点击随时跳过），降低冷启动感知等待
            delay(900)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 350)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Alpha animation interrupted or failed", e)
        } finally {
            safeOnSplashFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F141C))
            .clickableWithFeedback { safeOnSplashFinished() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                }
        ) {
            if (customBitmap != null) {
                Image(
                    bitmap = customBitmap!!,
                    contentDescription = "开屏海报",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                )
            } else if (customPoster.isNullOrEmpty() || customDecodeFinished) {
                // 默认海报：程序化绘制的主题海报
                ProceduralArtisticPoster(randomQuote = randomQuote, styleIndex = defaultPosterStyle)
            } else {
                // 自定义海报解码中：先保持纯色背景，避免闪一下默认图
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F141C)))
            }

            if (!isPureMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.38f)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 手绘风格 LOGO（任务三）：替换原"Ciallo阅读"文字标题；
                    // 原图为透明底浅色手绘（均亮 ~225），在海报渐变压暗区上对比清晰
                    Image(
                        painter = painterResource(R.drawable.splash_ciallo_logo),
                        contentDescription = "Ciallo阅读",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(168.dp)
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                            }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = randomQuote,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = "点击跳过",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

private fun decodeSplashBitmap(context: android.content.Context, model: String): ImageBitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 2 // Downsample 2x for fast decoding and low memory
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        when {
            model.startsWith("content://") -> {
                context.contentResolver.openInputStream(Uri.parse(model))?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
                }
            }
            model.startsWith("file://") -> {
                val path = Uri.parse(model).path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
                    } else null
                } else null
            }
            else -> {
                val file = File(model)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
                } else null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode splash bitmap: $model", e)
        null
    }
}

@Composable
private fun ProceduralArtisticPoster(randomQuote: String, styleIndex: Int) {
    val palette: List<Color> = when (styleIndex) {
        1 -> listOf(Color(0xFF2A1E14), Color(0xFF4A2C1A), Color(0xFF14100C), Color(0x33FFB74D))
        2 -> listOf(Color(0xFF101C2E), Color(0xFF1B2F52), Color(0xFF0A1020), Color(0x337FA8FF))
        else -> listOf(Color(0xFF141923), Color(0xFF1E2838), Color(0xFF0D1118), Color(0x337FD8C8))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        palette[0],
                        palette[1],
                        palette[2]
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.4f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette[3],
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.width * 0.7f
                ),
                center = center,
                radius = size.width * 0.7f
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier
                .padding(32.dp)
                .wrapContentSize()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 手绘风格 LOGO（任务三）：替换原 AutoStories 图标 + "Ciallo阅读"文字
                Image(
                    painter = painterResource(R.drawable.splash_ciallo_logo),
                    contentDescription = "Ciallo阅读",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(144.dp)
                )
            }
        }
    }
}
