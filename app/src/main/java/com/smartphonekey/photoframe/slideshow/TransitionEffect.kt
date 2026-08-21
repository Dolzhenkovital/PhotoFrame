package com.smartphonekey.photoframe.slideshow

import kotlin.random.Random

/**
 * User-selectable transition effects. Names are stored in SharedPreferences
 * and referenced from res/values/arrays.xml — renaming an entry is a
 * settings-migration event, don't do it casually.
 */
enum class TransitionEffect {
    CROSSFADE,
    FADE_BLACK,
    SLIDE_LEFT,
    SLIDE_RIGHT,
    SLIDE_UP,
    SLIDE_DOWN,
    ZOOM_IN,
    ZOOM_OUT,
    KEN_BURNS,
    ROTATE_FADE,
    RANDOM;

    companion object {
        fun fromPref(name: String?): TransitionEffect =
            entries.firstOrNull { it.name == name } ?: CROSSFADE

        /**
         * Resolves RANDOM into a concrete effect, never repeating the
         * previous one so "random" visibly feels random.
         */
        fun resolve(
            selected: TransitionEffect,
            last: TransitionEffect?,
            random: Random,
        ): TransitionEffect {
            if (selected != RANDOM) return selected
            val pool = entries.filter { it != RANDOM && it != last }
            return pool[random.nextInt(pool.size)]
        }
    }
}
