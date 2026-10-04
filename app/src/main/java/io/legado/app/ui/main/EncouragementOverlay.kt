package io.legado.app.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 开屏白色覆盖层，正中间显示一句随机鼓励语。
 * 显示 [displayMillis] 后淡出，淡出完成调用 [onDismiss]。
 */
@Composable
fun EncouragementOverlay(
    onDismiss: () -> Unit,
    displayMillis: Long = 1800L,
    fadeMillis: Long = 400L,
) {
    val message = remember { ENCOURAGEMENTS.random() }
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = fadeMillis.toInt()),
        label = "encouragementAlpha",
    )

    LaunchedEffect(Unit) {
        delay(displayMillis)
        visible = false
        delay(fadeMillis + 50)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Color(0xFF333333),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

private val ENCOURAGEMENTS = listOf(
    "今天也要好好读书呀 ✨",
    "慢慢来，比较快 🌱",
    "你已经做得很好了 🌟",
    "阅读是给自己的礼物 📖",
    "别急，一切都会好的 🌤",
    "安静地读完这一章 🍃",
    "每个字都算数 💫",
    "先把手里这本书看完 📚",
    "愿你今天有好心情 ☀️",
    "世界很大，书里更远 🌏",
    "累了就歇一会儿 ☕",
    "你比昨天更厉害了 🔥",
    "一点一点，就是全部 🌼",
    "安静下来，世界就是你的 🎐",
    "读书是最便宜的自由 🕊",
)