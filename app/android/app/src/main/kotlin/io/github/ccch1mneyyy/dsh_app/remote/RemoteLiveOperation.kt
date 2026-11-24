package io.github.ccch1mneyyy.dsh_app.remote

import io.github.ccch1mneyyy.dsh_app.R

internal enum class RemoteLiveOperation(val wire: String, val label: Int) {
    EXEC("exec", R.string.live_remote_exec),
    SHELL("shell", R.string.live_remote_shell),
    READ("read", R.string.live_remote_read),
    WRITE("write", R.string.live_remote_write),
    SEARCH("search", R.string.live_remote_search),
    DELEGATE("delegate", R.string.live_remote_delegate),
    SCREEN("screen", R.string.live_remote_screen),
    OPEN("open", R.string.live_remote_open),
    TAP("tap", R.string.live_remote_tap),
    INPUT("input", R.string.live_remote_input),
    SCROLL("scroll", R.string.live_remote_scroll),
    ROUND("round", R.string.live_remote_round),
    TOOL("tool", R.string.live_remote_tool);

    companion object {
        fun fromWire(value: Any?): RemoteLiveOperation? = entries.firstOrNull { it.wire == value }

        fun forMcp(name: String): RemoteLiveOperation = when (name) {
            "dsh_exec" -> EXEC
            "android_shell" -> SHELL
            "screen", "collect" -> SCREEN
            "open", "intent" -> OPEN
            "tap", "tap_xy", "key" -> TAP
            "type" -> INPUT
            "scroll" -> SCROLL
            "start_next_round" -> ROUND
            else -> TOOL
        }
    }
}
