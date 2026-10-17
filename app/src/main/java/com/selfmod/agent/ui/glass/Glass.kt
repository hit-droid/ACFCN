package com.selfmod.agent.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfmod.agent.ui.theme.AccentBlue
import com.selfmod.agent.ui.theme.AccentGreen
import com.selfmod.agent.ui.theme.GlassBorder
import com.selfmod.agent.ui.theme.GlassHighlight
import com.selfmod.agent.ui.theme.GlassShadow
import com.selfmod.agent.ui.theme.GlassTint
import com.selfmod.agent.ui.theme.GlassTintStrong
import com.selfmod.agent.ui.theme.TextPrimary
import com.selfmod.agent.ui.theme.TextSecondary
import com.selfmod.agent.ui.theme.WallpaperBlue
import com.selfmod.agent.ui.theme.WallpaperPink
import com.selfmod.agent.ui.theme.WallpaperPurple
import com.selfmod.agent.ui.theme.WallpaperTeal

/**
 * The core "liquid glass" surface: a translucent frosted fill, a bright specular
 * top edge that fades into a soft hairline, and a contact shadow so the panel
 * appears to float above the wallpaper.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = GlassTint,
    border: Color = GlassBorder,
    elevation: Dp = 14.dp,
): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                tint.copy(alpha = (tint.alpha * 1.6f).coerceAtMost(1f)),
                tint.copy(alpha = (tint.alpha * 0.7f).coerceAtMost(1f)),
            ),
        ),
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(GlassHighlight.copy(alpha = 0.55f), border, border.copy(alpha = 0.35f)),
        ),
        shape,
    )

/** Brighter variant for floating chrome (bars, pills). */
fun Modifier.glassChrome(
    shape: Shape = RoundedCornerShape(26.dp),
    elevation: Dp = 18.dp,
): Modifier = glass(shape, GlassTintStrong, GlassBorder, elevation)

/** A glass card that sizes to its content and pads the children. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().glass(shape).padding(contentPadding)) {
        content()
    }
}

/** Full-screen animated wallpaper painted behind every screen. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF080A12), Color(0xFF020207)))),
    ) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
            val center = Offset(cx, cy)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.35f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
            )
        }
        blob(WallpaperBlue, w * 0.16f, h * 0.10f, w * 0.78f, 0.46f)
        blob(WallpaperPurple, w * 0.94f, h * 0.20f, w * 0.72f, 0.40f)
        blob(WallpaperPink, w * 0.72f, h * 0.70f, w * 0.82f, 0.32f)
        blob(WallpaperTeal, w * 0.10f, h * 0.62f, w * 0.72f, 0.28f)
    }
}

/** Translucent header used at the top of a screen. */
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC0A0B12), Color(0x660A0B12), Color(0x1A0A0B12)),
                ),
            )
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }
        actions()
    }
}

/** Inline header for scrolling screens (no status-bar inset, no background). */
@Composable
fun GlassHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

data class GlassTab(val label: String, val icon: ImageVector)

/** Floating iOS-style tab bar with a glass highlight on the selected item. */
@Composable
fun GlassBottomBar(
    tabs: List<GlassTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .glassChrome(RoundedCornerShape(28.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { i, t ->
                val sel = i == selected
                val tint by animateColorAsState(
                    if (sel) AccentBlue else TextSecondary,
                    label = "tabTint$i",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .then(
                            if (sel) {
                                Modifier
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0x33FFFFFF), Color(0x14FFFFFF)),
                                        ),
                                    )
                                    .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, color = tint, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

/** iOS-style pill switch. */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(
        if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "toggleFraction",
    )
    val track = lerp(Color(0x33FFFFFF), AccentGreen, fraction)
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .offset(x = 20.dp * fraction)
                .size(26.dp)
                .shadow(6.dp, CircleShape, clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** A labelled setting row with a glass container. */
@Composable
fun GlassRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), elevation = 8.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            subtitle?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
        }
        trailing?.let { it() }
    }
}
