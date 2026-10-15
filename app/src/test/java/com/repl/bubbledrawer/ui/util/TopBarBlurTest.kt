package com.repl.bubbledrawer.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top bar's progressive blur is a runtime shader inside miuix-blur: the only thing this
 * project owns is three constants, and one of them decides how tall the frosted band looks.
 *
 * These cases pin the shape those constants produce, because the failure mode is purely
 * visual and was reported by eye once already ("模糊带比正常矮一截", 2026-10): the curve had
 * been lowered from 10 to 2 while a hand-rolled alpha mask was faded in below the bar's
 * midline, and the band ended up roughly half the height HyperMusicCover's does.
 *
 * Geometry: `fraction` is the position down the bar, 0 at the top edge and 1 at the bottom.
 */
class TopBarBlurTest {

    private val reference = TopBarBlurConfig.GradientCurve

    @Test fun constantsMatchTheReferenceImplementation() {
        // HyperMusicCover's ui/util/BlurUtils.kt, same miuix-blur version. A change here is a
        // change to the bar's whole look, so it should be deliberate and reviewed, not a
        // side effect of some other commit.
        assertEquals(15f, TopBarBlurConfig.BlurRadius, 0f)
        assertEquals(10f, reference, 0f)
        assertEquals(0.3f, TopBarBlurConfig.SurfaceAlpha, 0f)
    }

    @Test fun theBandIsStillFullyBlurredHalfwayDown() {
        assertTrue(TopBarBlurRamp.blurRadiusFactor(0.5f, reference) > 0.99f)
    }

    @Test fun threeQuartersDownStillCarriesMostOfTheRadius() {
        // The property the user actually sees: at 3/4 of the bar's height the blur is still
        // near full strength, so the frosted region reads as the full height of the bar.
        assertTrue(
            "radius factor at 75% height was " +
                TopBarBlurRamp.blurRadiusFactor(0.75f, reference),
            TopBarBlurRamp.blurRadiusFactor(0.75f, reference) >= 0.8f,
        )
    }

    @Test fun theOldLoweredCurveIsWhatShortenedTheBand() {
        // Kept as the counter-example: curve = 2 loses three quarters of the radius by the
        // same 75% mark, which is the shortening that was reported.
        assertTrue(TopBarBlurRamp.blurRadiusFactor(0.75f, 2f) < 0.35f)
    }

    @Test fun theRadiusReachesZeroAtTheBarsBottomEdge() {
        // Where the radius hits 0 the shader returns the *unblurred* backdrop instead of a
        // faded one, which is why there is no hard cut at the bar's bottom edge and no alpha
        // mask is needed.
        assertEquals(0f, TopBarBlurRamp.blurRadiusFactor(1f, reference), 0.0001f)
    }

    @Test fun theRadiusNeverGrowsOnTheWayDown() {
        var previous = Float.MAX_VALUE
        for (step in 0..20) {
            val current = TopBarBlurRamp.blurRadiusFactor(step / 20f, reference)
            assertTrue("radius grew at $step/20: $previous -> $current", current <= previous + 1e-6f)
            previous = current
        }
    }

    @Test fun theTintFadesOutTogetherWithTheBlur() {
        // Same band and curve for the colour blend, so the surface tint cannot outlive the
        // blur and leave an edge behind at the bar's bottom.
        assertEquals(1f, TopBarBlurRamp.tintWeight(0f, reference), 0.001f)
        assertEquals(0f, TopBarBlurRamp.tintWeight(1f, reference), 0.001f)
        assertTrue(TopBarBlurRamp.tintWeight(0.5f, reference) > 0.99f)
        assertTrue(TopBarBlurRamp.tintWeight(0.9f, reference) < 0.2f)
    }

    @Test fun positionsOutsideTheBarAreClamped() {
        assertEquals(1f, TopBarBlurRamp.blurRadiusFactor(-3f, reference), 0.0001f)
        assertEquals(0f, TopBarBlurRamp.blurRadiusFactor(9f, reference), 0.0001f)
    }
}
