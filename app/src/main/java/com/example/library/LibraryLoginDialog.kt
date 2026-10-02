package com.example.library

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import com.example.ui.components.liquidGlass
import com.example.ui.components.GlassDialogWindowEffect
import com.example.ui.components.filmGrain
import com.example.ui.components.iridescentBorder
import com.example.ui.components.rememberIridescentColors
import com.example.ui.components.rememberThemedGlassBackdrop
import com.example.ui.components.AppLiquidButton
import com.example.ui.components.DialogLiquidGlass
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.AppToast

/**
 * 居中毛玻璃登录卡片：复用玻璃拟态半透明卡片 + Spring 弹性入场动画，
 * 大尺寸居中表现，包含细腻边框与强调色配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryLoginDialog(
    message: String,
    loading: Boolean,
    onLogin: (String, String) -> Unit,
    onDismiss: () -> Unit,
    hazeState: HazeState? = null
) {
    val context = LocalContext.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val blurPx = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = { if (!loading) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        DialogLiquidGlass {
            GlassDialogWindowEffect(activity = activity, blurRadiusPx = blurPx)
            var appear by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { appear = true }

            val panelScale by animateFloatAsState(
                targetValue = if (appear) 1f else 0.82f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "libraryLoginPanelSpring"
            )
            val panelAlpha by animateFloatAsState(
                targetValue = if (appear) 1f else 0f,
                animationSpec = tween(220),
                label = "libraryLoginPanelFade"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (!loading) onDismiss() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val cardShape = RoundedCornerShape(28.dp)
                val dialogBackdrop = rememberThemedGlassBackdrop()
                val iridescent = rememberIridescentColors()
                val baseModifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = AdaptiveSpec.dialogMaxWidth)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .graphicsLayer {
                        scaleX = panelScale
                        scaleY = panelScale
                        alpha = panelAlpha
                    }
                    .liquidGlass(
                        backdrop = dialogBackdrop,
                        shape = cardShape,
                        surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        blurRadius = 22.dp,
                        refraction = false
                    )
                    .clip(cardShape)
                    .filmGrain(alpha = 0.04f)
                    .iridescentBorder(
                        shape = cardShape,
                        colors = iridescent,
                        width = 2.dp,
                        alpha = 0.22f
                    )

                Box(
                    modifier = baseModifier
                        .padding(24.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "登录 Z-Library",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (!loading) onDismiss() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val textFieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedLabelColor = MaterialTheme.colorScheme.secondary,
                        cursorColor = MaterialTheme.colorScheme.secondary,
                        focusedLeadingIconColor = MaterialTheme.colorScheme.secondary
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("邮箱 / 账号") },
                        leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = textFieldColors
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = textFieldColors
                    )

                    if (message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = if (message.contains("成功")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (loading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    AppLiquidButton(
                        text = if (loading) "登录中" else "确认登录",
                        onClick = { onLogin(email, password) },
                        enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = {
                            val url = "https://${ZLibraryNodeConfig.domain}/registration"
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }.onFailure {
                                AppToast.makeText(context, "无法打开注册页面", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "没有账号？去注册",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
        }
    }
}
