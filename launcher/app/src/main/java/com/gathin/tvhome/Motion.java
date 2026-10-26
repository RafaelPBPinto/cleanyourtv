package com.gathin.tvhome;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The launcher's motion: Apple-style easing and springs, and for reduced motion (config.xml reduced_motion) the one
 * spring and the one time limit every animation uses then.
 */
final class Motion {

    /** Fast start, long soft landing, no overshoot: the curve iOS uses for sheets and focus moves. */
    static final Interpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    /** A spring with a 0.35 s response, SwiftUI's default; stiffness is (2 pi / response) squared. */
    static final float SMOOTH_STIFFNESS = 322f;
    /** Just under critical: the glide ends with a settle too small to read as a bounce, like tvOS focus. */
    static final float SMOOTH_DAMPING = 0.86f;

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    static void smooth(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(SMOOTH_STIFFNESS).setDampingRatio(SMOOTH_DAMPING));
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
