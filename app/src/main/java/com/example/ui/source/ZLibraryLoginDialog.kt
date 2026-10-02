package com.example.ui.source

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.source.BookSource
import com.example.source.LoginCredential
import com.example.source.SourceResult
import com.example.library.ZLibraryNodeConfig
import dev.chrisbanes.haze.HazeState
import com.example.ui.components.liquidGlass
import com.example.ui.components.AppIconButton
import com.example.ui.components.GlassDialogWindowEffect
import com.example.ui.components.AppIconButton
import com.example.ui.components.filmGrain
import com.example.ui.components.AppIconButton
import com.example.ui.components.iridescentBorder
import com.example.ui.components.AppIconButton
import com.example.ui.components.rememberIridescentColors
import com.example.ui.components.AppIconButton
import com.example.ui.components.rememberThemedGlassBackdrop
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppLiquidButton
import com.example.ui.components.AppIconButton
import com.example.ui.components.DialogLiquidGlass
import com.example.ui.components.AppIconButton
import kotlinx.coroutines.launch
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.AppToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZLibraryLoginDialog(
    source: BookSource,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    hazeState: HazeState? = null,
    /** 源支持站内注册时提供（如 vomic）：登录窗口下方出现「注册新账号」入口 */
    onRegister: (suspend () -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val blurPx = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
    val scope = rememberCoroutineScope()
    val registrationUrl by produceState<String?>(null, source) {
        value = kotlinx.coroutines.withTimeoutOrNull(5_000) { source.getRegistrationUrl() }
    }
    val isJsSource = source.id.startsWith("js_")
    val supportsCookieLogin = !isJsSource || source.id == "js_mycomic"
    var loginModeTab by remember(source.id) { mutableStateOf(if (source.id == "js_mycomic") 1 else 0) } // 0: 账号密码, 1: Cookie
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cookieString by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
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
                targetValue = if (appear) 1f else 0.85f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "loginPanelSpring"
            )
            val panelAlpha by animateFloatAsState(
                targetValue = if (appear) 1f else 0f,
                animationSpec = tween(220),
                label = "loginPanelFade"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                val cardShape = RoundedCornerShape(28.dp)
                val dialogBackdrop = rememberThemedGlassBackdrop()
                val iridescent = rememberIridescentColors()
                val baseModifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = AdaptiveSpec.dialogMaxWidth)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
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
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isJsSource) "登录 ${source.name}" else "登录 Z-Library",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        AppIconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (supportsCookieLogin && source.id != "js_mycomic") {
                        TabRow(
                            selectedTabIndex = loginModeTab,
                            containerColor = Color.Transparent,
                            divider = {}
                        ) {
                            Tab(
                                selected = loginModeTab == 0,
                                onClick = { loginModeTab = 0 },
                                text = { Text("账号密码", fontSize = 14.sp, fontWeight = if (loginModeTab == 0) FontWeight.Bold else FontWeight.Normal) }
                            )
                            Tab(
                                selected = loginModeTab == 1,
                                onClick = { loginModeTab = 1 },
                                text = { Text(if (source.id == "js_mycomic") "cf_clearance" else "粘贴 Cookie", fontSize = 14.sp, fontWeight = if (loginModeTab == 1) FontWeight.Bold else FontWeight.Normal) }
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

                    if (loginModeTab == 0) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("邮箱 / 账号") },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = textFieldColors
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = textFieldColors
                        )
                    } else {
                        OutlinedTextField(
                            value = cookieString,
                            onValueChange = { cookieString = it },
                            label = { Text(if (source.id == "js_mycomic") "cf_clearance 的值" else "Cookie (包含 remix_userkey)") },
                            placeholder = { Text(if (source.id == "js_mycomic") "从浏览器复制 cf_clearance 的值" else "remix_userid=...; remix_userkey=...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = textFieldColors
                        )
                    }

                    if (isLoading) {
                        Spacer(modifier = Modifier.height(20.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    AppLiquidButton(
                        text = if (isLoading) "登录中" else "确认登录",
                        enabled = !isLoading,
                        onClick = {
                            scope.launch {
                                isLoading = true
                                val credential = if (loginModeTab == 0) {
                                    LoginCredential(
                                        username = username,
                                        password = password
                                    )
                                } else {
                                    LoginCredential(
                                        cookie = cookieString
                                    )
                                }
                                when (val result = source.login(credential)) {
                                    is SourceResult.Success -> {
                                        AppToast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                                        onSuccess()
                                        onDismiss()
                                    }
                                    is SourceResult.Error -> {
                                        AppToast.makeText(context, result.exception.message ?: "登录失败", Toast.LENGTH_LONG).show()
                                    }
                                }
                                isLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (onRegister != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    runCatching { onRegister() }
                                        .onSuccess {
                                            AppToast.makeText(context, "注册成功，已自动登录", Toast.LENGTH_SHORT).show()
                                            onSuccess()
                                            onDismiss()
                                        }
                                        .onFailure {
                                            AppToast.makeText(context, it.message ?: "注册失败", Toast.LENGTH_LONG).show()
                                        }
                                    isLoading = false
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = "没有账号？注册新账号",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    registrationUrl?.let { url ->
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }.onFailure {
                                    AppToast.makeText(context, "无法打开注册页面", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
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
}
