package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
fun GeminiLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.img_logo_gemini),
        contentDescription = "Gemini Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun ClaudeLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.img_logo_claude),
        contentDescription = "Claude Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun ChatGptLogoIcon(
    modifier: Modifier = Modifier.size(20.dp)
) {
    Image(
        painter = painterResource(id = R.drawable.ic_logo_chatgpt),
        contentDescription = "ChatGPT Logo",
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
fun AiAssistantBadge(
    name: String,
    iconBgColor: Color,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    logo: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.testTag("ai_badge_${name.lowercase()}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isCompact) 10.dp else 12.dp,
                vertical = if (isCompact) 6.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isCompact) 24.dp else 28.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                logo()
            }
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (isCompact) 12.sp else 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun DeveloperAiAttributionSection(
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    showCardContainer: Boolean = true
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (showCardContainer) 16.dp else 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Developer Credit Pill
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Developed by Alex Hutajulu",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 12.5.sp else 13.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Browser ini dikembangkan dengan bantuan AI",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = if (isCompact) 11.5.sp else 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3 Modern AI Badge Cards: Gemini, Claude, ChatGPT using the uploaded logo images
            val badgeIconSize: Dp = if (isCompact) 17.dp else 19.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AiAssistantBadge(
                    name = "Gemini",
                    iconBgColor = Color(0xFF0B0F19),
                    isCompact = isCompact
                ) {
                    GeminiLogoIcon(modifier = Modifier.size(badgeIconSize))
                }

                AiAssistantBadge(
                    name = "Claude",
                    iconBgColor = Color(0xFFFFF8F5),
                    isCompact = isCompact
                ) {
                    ClaudeLogoIcon(modifier = Modifier.size(badgeIconSize))
                }

                AiAssistantBadge(
                    name = "ChatGPT",
                    iconBgColor = Color(0xFF000000),
                    isCompact = isCompact
                ) {
                    ChatGptLogoIcon(modifier = Modifier.size(badgeIconSize))
                }
            }
        }
    }

    if (showCardContainer) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        ) {
            content()
        }
    } else {
        Box(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }
}
