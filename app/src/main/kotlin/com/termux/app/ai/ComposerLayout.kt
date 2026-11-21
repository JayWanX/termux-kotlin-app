package com.termux.app.ai

/**
 * Layout rules for the composer at the bottom of the assistant panel.
 *
 * Kept as pure arithmetic because the interesting part is a measurement, not a rendering decision:
 * with a one-line field the send button must sit centred, but once the field grows the same button has
 * to drop to the bottom so it stays next to the line being typed.
 */
object ComposerLayout {

    /**
     * Field height at or below which the composer is considered to be showing one line.
     *
     * Material's outlined field has a 56dp minimum, which a single line never exceeds, and gains a
     * line's worth of height only once the text actually wraps. 64dp sits above the former and below
     * the latter.
     */
    const val SINGLE_LINE_MAX_DP = 64f

    /**
     * Whether the send button should be vertically centred beside the field.
     *
     * @param fieldHeightPx the measured height of the text field; 0 before the first layout pass.
     * @param density the display density, to convert [SINGLE_LINE_MAX_DP].
     */
    fun sendButtonCentered(fieldHeightPx: Int, density: Float): Boolean {
        // Unmeasured fields are one line tall, so centring is the right starting point; otherwise the
        // button would visibly jump on the first measurement.
        if (fieldHeightPx <= 0) return true
        if (density <= 0f) return true
        return fieldHeightPx <= SINGLE_LINE_MAX_DP * density
    }
}
