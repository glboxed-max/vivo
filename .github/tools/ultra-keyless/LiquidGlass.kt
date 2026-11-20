package com.sukisu.ultra.backend

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

private val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun GlassEnvironment(background: @Composable () -> Unit, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val scene = remember(layer) { Backdrop(layer) }
    CompositionLocalProvider(LocalBackdrop provides scene) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().onGloballyPositioned { scene.origin = it.positionInRoot() }
                .drawWithContent {
                    if (UiPreferences.liquidGlass) {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    } else drawContent()
                }) {
                background()
                if (UiPreferences.liquidGlass && com.sukisu.ultra.ui.theme.ThemeConfig.customBackgroundUri == null) {
                    val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                    val tertiary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.radialGradient(listOf(primary, Color.Transparent),
                            center = Offset(size.width * 0.12f, size.height * 0.22f), radius = size.width * 0.95f))
                        drawRect(Brush.radialGradient(listOf(tertiary, Color.Transparent),
                            center = Offset(size.width * 0.96f, size.height * 0.69f), radius = size.width * 1.1f))
                    }
                }
            }
            content()
        }
    }
}

private const val refraction = """
uniform shader content;
uniform float2 resolution;
uniform float phase;
half4 main(float2 p) {
    float2 extent = max(resolution, float2(1.0));
    float2 uv = p / extent;
    float2 center = uv * 2.0 - 1.0;
    float edge = pow(clamp(max(abs(center.x), abs(center.y)), 0.0, 1.0), 4.0);
    float2 ripple = float2(sin(uv.y * 8.0 + phase), cos(uv.x * 7.0 + phase)) * 1.2;
    float2 samplePoint = clamp(p + center * edge * 8.0 + ripple, float2(0.0), extent);
    return content.eval(samplePoint);
}
"""

@Composable
private fun GlassSurface(modifier: Modifier, shape: Shape, content: @Composable () -> Unit) {
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val transition = rememberInfiniteTransition(label = "glass")
    val phase by transition.animateFloat(0f, 6.283185f,
        infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "refraction")
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { runCatching { RuntimeShader(refraction) }.getOrNull() } else null
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }.clip(shape), propagateMinConstraints = true) {
        if (backdrop != null) {
            Box(Modifier.matchParentSize().graphicsLayer {
                if (Build.VERSION.SDK_INT >= 31) {
                    val blur = RenderEffect.createBlurEffect(13f, 13f, Shader.TileMode.CLAMP)
                    renderEffect = if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                        shader.setFloatUniform("resolution", size.width, size.height)
                        shader.setFloatUniform("phase", phase)
                        RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
                            .asComposeRenderEffect()
                    } else blur.asComposeRenderEffect()
                }
            }.drawWithContent {
                val relative = origin - backdrop.origin
                translate(-relative.x, -relative.y) { drawLayer(backdrop.layer) }
            })
        }
        content()
        Box(Modifier.matchParentSize().border(1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))), shape))
    }
}

@Composable
private fun glassColors(colors: CardColors) = CardDefaults.cardColors(
    containerColor = colors.containerColor.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.66f else 0.32f),
    contentColor = colors.contentColor,
    disabledContainerColor = colors.disabledContainerColor.copy(alpha = 0.18f),
    disabledContentColor = colors.disabledContentColor,
)

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!UiPreferences.liquidGlass) {
        ElevatedCard(modifier, shape, colors, elevation, content)
        return
    }
    GlassSurface(modifier, shape) {
        ElevatedCard(shape = shape, colors = glassColors(colors), elevation = CardDefaults.elevatedCardElevation(0.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!UiPreferences.liquidGlass) {
        ElevatedCard(onClick, modifier, enabled, shape, colors, elevation, interactionSource, content)
        return
    }
    GlassSurface(modifier, shape) {
        ElevatedCard(onClick = onClick, enabled = enabled, shape = shape, colors = glassColors(colors),
            elevation = CardDefaults.elevatedCardElevation(0.dp), interactionSource = interactionSource, content = content)
    }
}

@Composable
fun LiquidNavigationBar(
    modifier: Modifier = Modifier,
    containerColor: Color = NavigationBarDefaults.containerColor,
    tonalElevation: Dp = NavigationBarDefaults.Elevation,
    content: @Composable RowScope.() -> Unit,
) {
    if (!UiPreferences.liquidGlass) {
        NavigationBar(modifier = modifier, containerColor = containerColor, tonalElevation = tonalElevation, content = content)
        return
    }
    GlassSurface(modifier, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        NavigationBar(containerColor = containerColor.copy(alpha = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.68f else 0.28f), tonalElevation = 0.dp, content = content)
    }
}
