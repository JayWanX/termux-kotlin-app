package com.termux.shared.terminal

/**
 * Control keys that can be sent to a terminal session.
 *
 * These are intentionally a small, well-defined set rather than arbitrary escape sequences, so that
 * callers (including automated ones) cannot accidentally construct malformed input.
 */
enum class TerminalKey {
    ENTER,
    TAB,
    ESC,
    BACKSPACE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    CTRL_C,
    CTRL_D,
    CTRL_L,
    CTRL_Z;

    /** The single control character this key maps to, for keys that are plain control codes. */
    val controlChar: Char?
        get() = when (this) {
            // 0x03 ETX / 0x04 EOT / 0x0C FF / 0x1A SUB
            CTRL_C -> '\u0003'
            CTRL_D -> '\u0004'
            CTRL_L -> '\u000C'
            CTRL_Z -> '\u001A'
            else -> null
        }
}
