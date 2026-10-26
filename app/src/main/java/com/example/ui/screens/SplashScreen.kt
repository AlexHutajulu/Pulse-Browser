package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.DeveloperAiAttributionSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reusable Alex Browser Singularity Brand Emblem on a dark obsidian squircle tile with glowing accretion aura.
 * Used consistently in SplashScreen and Settings > About.
 */
@Composable
fun AdBlockBrandEmblem(
    size: Dp = 96.dp,
    cornerRadius: Dp = 26.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color(0xFF428BFF).copy(alpha = 0.35f),
                spotColor = Color(0xFF7046FF).copy(alpha = 0.45f)
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = Color(0xFF080D19),
        border = BorderStroke(1.2.dp, Color(0xFF428BFF).copy(alpha = 0.4f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_alex_singularity),
                contentDescription = "Alex Browser Singularity Logo",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    val loadProgress = remember { Animatable(0.05f) }
    val logoScale = remember { Animatable(0.86f) }

    LaunchedEffect(Unit) {
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
            )
        }
        launch {
            loadProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing)
            )
        }
        delay(1500)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 3D Modern Cyber Shield & Glowing Globe Brand Emblem on Clean White Surface
            AdBlockBrandEmblem(
                size = 100.dp,
                cornerRadius = 28.dp,
                modifier = Modifier.scale(logoScale.value)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "AdBlock Browser",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Fast · Simple · Secure",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Smooth Loading Progress Indicator + AI-assisted development notice
            LinearProgressIndicator(
                progress = { loadProgress.value },
                modifier = Modifier
                    .width(200.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(100.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Memuat mesin browser · Dikembangkan dengan bantuan AI",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }

        // Bottom Attribution & 3 AI Badges (Gemini, Claude, ChatGPT)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            DeveloperAiAttributionSection(
                isCompact = true,
                showCardContainer = true
            )
        }
    }
}
