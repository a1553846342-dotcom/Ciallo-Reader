package com.example.ui.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.clickableWithFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CategoryEntity
import com.example.ui.components.AppSwitch
import com.example.ui.components.GlassCard
import com.example.ui.theme.MintPrimary

/* ══════════════ 隐私模式 UI（第七轮第 6.4 条） ══════════════
 *
 * 悬浮毛玻璃窗口体系：与设置面板（第 3 条）/书架（第 5 条）同一套设计语言——
 * 苹果式精致克制、GlassCard 真实背景采样模糊、品牌薄荷点缀、无霓虹无重投影。
 * 作为主窗口内的整页覆盖层实现（而非平台 Dialog 窗口）：只有这样 GlassCard
 * 才能采样到 App 真实内容做毛玻璃，"悬浮在书架/设置之上"的材质才成立。
 */

/** 6 位密码圆点指示 */
@Composable
private fun PinDots(filled: Int, error: Boolean, modifier: Modifier = Modifier) {
    // 2026-09-21 修复：原实现用 animateFloatAsState 把 shake 从 0 补间到 1 后停住，
    // 而 offsetX 由 `shake > 0.5f` 二值判断 —— 于是圆点从 -8 跳到 +8 之后永久停在 +8，
    // 表现为“整排圆点错位后回不来”，而不是抖动。改为阻尼衰减摆动后归零（iOS 同款反馈）。
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (!error) {
            offsetX.snapTo(0f)
            return@LaunchedEffect
        }
        // 3 次递减摆动：振幅 10 → 6.7 → 3.3，末了回到 0
        repeat(3) { i ->
            val amp = 10f * (1f - i / 3f)
            offsetX.animateTo(amp, spring(dampingRatio = 0.15f, stiffness = 1400f))
            offsetX.animateTo(-amp, spring(dampingRatio = 0.15f, stiffness = 1400f))
        }
        offsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 900f))
    }
    Row(
        modifier.graphicsLayer { translationX = offsetX.value },
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(6) { i ->
            val active = i < filled
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = if (active) 1f else 0.45f }
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> MaterialTheme.colorScheme.error
                            active -> MintPrimary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
            )
        }
    }
}

/** 玻璃数字键盘按键 */
@Composable
private fun GlassPinKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keySize: androidx.compose.ui.unit.Dp = 68.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "pinKeyScale"
    )
    Box(
        modifier
            .size(keySize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.55f else 0.25f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (keySize < 60.dp) 19.sp else 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.9f else 0.35f)
        )
    }
}

/** 玻璃数字键盘按键（图标版：删除键） */
@Composable
private fun GlassPinIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    keySize: androidx.compose.ui.unit.Dp = 68.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "pinKeyScale"
    )
    Box(
        modifier
            .size(keySize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.size(if (keySize < 60.dp) 19.dp else 22.dp)
        )
    }
}

/** PIN 输入模式 */
enum class PinEntryMode { SETUP, CONFIRM, VERIFY, CHANGE_OLD, CHANGE_NEW }

/**
 * PIN 输入悬浮毛玻璃窗口：
 * - SETUP → CONFIRM：首次开启隐私模式的"设置 + 二次确认"流程；
 * - VERIFY：进入受保护分类 / 打开隐私管理窗口前的验证；
 * - CHANGE_OLD → CHANGE_NEW：修改密码。
 * 输满 6 位自动提交，错误时圆点抖动 + 清空重试。
 */
@Composable
fun PrivacyPinOverlay(
    mode: PinEntryMode,
    onPinSet: suspend (String) -> Unit,       // SETUP/CONFIRM 全流程完成（新 PIN 生效）
    onPinVerified: suspend (String) -> Boolean, // VERIFY / CHANGE_OLD：返回校验结果
    onDismiss: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var firstSetup by remember { mutableStateOf<String?>(null) }
    // CHANGE 流程在 overlay 内部推进：OLD 验证通过后切到 NEW
    var currentMode by remember { mutableStateOf(mode) }
    // 2026-09-21 新增：标记当前 CONFIRM 阶段属于“修改密码”流程（而非首次设置），
    // 使二次确认失败时回到 CHANGE_NEW 而不是 SETUP。
    var changeFlow by remember { mutableStateOf(false) }

    fun reset(msg: String? = null) {
        entered = ""
        error = msg
    }

    // 输满自动提交
    LaunchedEffect(entered, currentMode) {
        if (entered.length != 6) return@LaunchedEffect
        when (currentMode) {
            PinEntryMode.SETUP -> {
                firstSetup = entered
                currentMode = PinEntryMode.CONFIRM
                reset()
            }
            PinEntryMode.CONFIRM -> {
                if (entered == firstSetup) onPinSet(entered)
                else {
                    val inChangeFlow = changeFlow
                    firstSetup = null
                    changeFlow = false
                    if (inChangeFlow) {
                        // 改密码的二次确认失败：回到“输入新密码”，不要退回首次设置流程
                        currentMode = PinEntryMode.CHANGE_NEW
                        reset("两次输入不一致，请重新输入新密码")
                    } else {
                        currentMode = PinEntryMode.SETUP
                        reset("两次输入不一致，请重新设置")
                    }
                }
            }
            PinEntryMode.VERIFY -> {
                if (onPinVerified(entered)) onDismiss() else reset("密码错误，请重试")
            }
            PinEntryMode.CHANGE_OLD -> {
                if (onPinVerified(entered)) {
                    currentMode = PinEntryMode.CHANGE_NEW
                    reset()
                } else reset("密码错误，请重试")
            }
            PinEntryMode.CHANGE_NEW -> {
                // 2026-09-21 修复：原实现只输入一次新密码就直接 onPinSet 生效，
                // 手滑输错 6 位会永久锁死自己的隐私分类（项目内没有找回/重置途径），
                // 且与“首次设置”要求的二次确认标准不一致。
                // 现在改为与首次设置同规格：先存下来，进入 CONFIRM 阶段再输一次。
                firstSetup = entered
                changeFlow = true
                currentMode = PinEntryMode.CONFIRM
                reset()
            }
        }
    }

    BackHandler(onBack = onDismiss)
    // 第十一轮第 4 条：隐私弹窗出现动画——遮罩淡入 + 卡片淡入放大（与全局弹窗转场同款）
    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        com.example.ui.components.ScrimEntrance {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }
        com.example.ui.components.DialogEntrance {
        // 第八轮审查修复（Agent B/C 发现）：横屏矮屏（高度 < 560dp）下原布局
        // 四行键盘 + 头部超出屏幕，0/⌫ 行被裁出屏幕外无法点击。紧凑模式：
        // 键 52dp、行距 7dp、去盾牌图标、收紧间距——完整键盘留在屏内。
        androidx.compose.foundation.layout.BoxWithConstraints {
            val compact = maxHeight < 560.dp
            val keySize = if (compact) 52.dp else 68.dp
            val rowSpace = if (compact) 7.dp else 12.dp
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 拦截穿透点击 */ },
                shape = RoundedCornerShape(28.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp, vertical = if (compact) 14.dp else 24.dp
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!compact) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MintPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        when (currentMode) {
                            PinEntryMode.SETUP -> "设置隐私密码"
                            PinEntryMode.CONFIRM -> "再次输入以确认"
                            PinEntryMode.VERIFY -> "输入隐私密码"
                            PinEntryMode.CHANGE_OLD -> "输入当前密码"
                            PinEntryMode.CHANGE_NEW -> "输入新密码"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (currentMode) {
                            PinEntryMode.SETUP, PinEntryMode.CONFIRM ->
                                "6 位数字 · 开启后受保护分类需要密码查看"
                            else -> "6 位数字密码"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                    PinDots(filled = entered.length, error = error != null)
                    // 错误提示占位（固定高度避免跳动）
                    Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                    Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            error ?: "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(rowSpace)) {
                        listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf("", "0", "⌫"),
                        ).forEach { rowKeys ->
                            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp)) {
                                rowKeys.forEach { key ->
                                    when {
                                        key.isEmpty() -> Spacer(Modifier.size(keySize))
                                        key == "⌫" -> GlassPinIconKey(
                                            icon = Icons.Filled.Backspace,
                                            contentDescription = "删除",
                                            keySize = keySize,
                                            onClick = {
                                                if (entered.isNotEmpty()) {
                                                    entered = entered.dropLast(1)
                                                    error = null
                                                }
                                            },
                                        )
                                        else -> GlassPinKey(
                                            label = key,
                                            keySize = keySize,
                                            onClick = {
                                                if (entered.length < 6) {
                                                    entered += key
                                                    error = null
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        } // DialogEntrance 结束
    }
}

/**
 * 隐私模式管理悬浮毛玻璃窗口（6.4.3：统一管理"哪些分类需要密码保护"）。
 * 进入前提：隐私模式已开启且已验证 PIN。窗口内可切换各分类保护开关、
 * 修改密码、关闭隐私模式（后两者需再次验证 PIN）。
 */
@Composable
fun PrivacyManageOverlay(
    categories: List<CategoryEntity>,
    privacyModeEnabled: Boolean,
    onToggleProtected: (CategoryEntity, Boolean) -> Unit,
    /** 第九轮：全局无痕浏览开关——开启后所有阅读（含在线/普通分类）不计入统计 */
    onToggleIncognito: (Boolean) -> Unit = {},
    incognitoBrowsingEnabled: Boolean = false,
    /** 「我喜欢的」是否也要密码才能看（与受保护分类同一套 PIN） */
    favoritesProtected: Boolean = false,
    onToggleFavoritesProtected: (Boolean) -> Unit = {},
    onChangePin: () -> Unit,
    onDisablePrivacy: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // 第十一轮第 4 条：隐私弹窗出现动画——遮罩淡入 + 卡片淡入放大（与全局弹窗转场同款）
    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        com.example.ui.components.ScrimEntrance {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }
        com.example.ui.components.DialogEntrance {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .padding(horizontal = 28.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* 拦截穿透点击 */ },
            shape = RoundedCornerShape(28.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MintPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "隐私模式",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "完成",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = MintPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "开启密码保护的分类需要验证密码查看，其中的阅读不计入统计。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(10.dp))

                /* ── 第九轮：全局无痕浏览总开关 ──
                 * 开启后所有阅读（在线阅读、普通分类）都不写入阅读统计/阅读会话；
                 * 阅读进度保存不受影响（无痕只作用于统计口径）。 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = if (incognitoBrowsingEnabled) MintPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "无痕浏览",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (incognitoBrowsingEnabled) "已开启 · 全部阅读不计入统计"
                            else "开启后在线等全部阅读不计入统计",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                    AppSwitch(
                        checked = incognitoBrowsingEnabled,
                        onCheckedChange = { onToggleIncognito(it) }
                    )
                }
                Spacer(Modifier.height(10.dp))

                /* ── 「我喜欢的」密码保护 ──
                 * 与分类保护是同一套 PIN：开启后书架页的「我喜欢的」板块
                 * 必须先验证密码才能显示内容（未解锁时只显示一把锁）。 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = if (favoritesProtected) MintPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "「我喜欢的」需要密码",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (favoritesProtected) "已开启 · 进入该板块需验证密码"
                            else "开启后查看收藏要先验证密码",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                    AppSwitch(
                        checked = favoritesProtected,
                        onCheckedChange = { onToggleFavoritesProtected(it) }
                    )
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // 2026-09-21：改用 items(list) + key，不再按下标取元素。
                    // 原写法 items(size) 没有身份信息，分类增删改后重排会错位。
                    items(categories, key = { it.id }) { cat ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (cat.isProtected) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = null,
                                tint = if (cat.isProtected) MintPrimary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cat.name,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (cat.isProtected) {
                                    Text(
                                        "无痕 · 阅读不计入统计",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    )
                                }
                            }
                            AppSwitch(
                                checked = cat.isProtected,
                                onCheckedChange = { onToggleProtected(cat, it) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .clickableWithFeedback { onChangePin() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "修改密码",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                            .clickableWithFeedback { onDisablePrivacy() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "关闭隐私模式",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        } // DialogEntrance 结束
    }
}
