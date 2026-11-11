package dev.zain.znkeyboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS-style rubber band for a vertically scrolling page: past either end, and on a page shorter
 * than the screen, the content follows the finger with growing resistance and springs back on
 * release; a fling that hits an end bounces a little. Put it before `verticalScroll(state,
 * overscrollEffect = null)` so the scroll hands it what it cannot use.
 */
@Composable
fun Modifier.bounceScroll(): Modifier {
    val density = LocalDensity.current
    val limit = remember(density) { with(density) { MAX_PULL.toPx() } }
    val bounce = remember(limit) { Bounce(limit) }
    return this
        .graphicsLayer { translationY = bounce.offset }
        .nestedScroll(bounce)
}

private class Bounce(private val limit: Float) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)

    /** Pulling back toward the rest position takes the movement first. */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (offset == 0f || source != NestedScrollSource.UserInput || sign(available.y) == sign(offset)) return Offset.Zero
        val next = offset + available.y
        return if (sign(next) != sign(offset)) {
            val used = -offset
            offset = 0f
            Offset(0f, used)
        } else {
            offset = next
            available
        }
    }

    /** What the scroll could not use stretches the page, harder the further it is pulled. */
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        val resistance = (1f - abs(offset) / limit).coerceIn(0f, 1f) * PULL_FACTOR
        offset = (offset + available.y * resistance).coerceIn(-limit, limit)
        return available
    }

    /** Let go while stretched: spring back, carrying the finger's speed. */
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset == 0f) return Velocity.Zero
        animate(offset, 0f, available.y * RELEASE_VELOCITY_SHARE, spring(Spring.DampingRatioNoBouncy, RETURN_STIFFNESS)) { value, _ ->
            offset = value
        }
        return available
    }

    /** A fling that runs into an end goes on a little past it and comes back. */
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (abs(available.y) < MIN_BOUNCE_VELOCITY) return Velocity.Zero
        val velocity = (available.y * HIT_VELOCITY_SHARE).coerceIn(-MAX_BOUNCE_VELOCITY, MAX_BOUNCE_VELOCITY)
        animate(offset, 0f, velocity, spring(Spring.DampingRatioNoBouncy, BOUNCE_STIFFNESS)) { value, _ ->
            offset = value
        }
        return available
    }

    private companion object {
        /** The share of the finger's movement the page follows at the start of a pull. */
        const val PULL_FACTOR = 0.55f
        const val RELEASE_VELOCITY_SHARE = 0.25f
        const val HIT_VELOCITY_SHARE = 0.3f
        const val MIN_BOUNCE_VELOCITY = 600f
        const val MAX_BOUNCE_VELOCITY = 4000f
        const val RETURN_STIFFNESS = 300f
        const val BOUNCE_STIFFNESS = 220f
    }
}

/** How far the page can be pulled past an end, at most. */
private val MAX_PULL = 220.dp
