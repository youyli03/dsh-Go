package io.github.ccch1mneyyy.dsh_app.phone.a11y

/** Observation identity is separate from an action target's stable fingerprint. */
internal class ScreenObservation(
    val packageName: String?,
    windows: List<UiWindow>,
    val screen: Box,
    val keyboardShown: Boolean?,
    val unavailable: Boolean,
    val incomplete: Boolean,
    val elements: List<String>,
    private val targets: List<ScreenFormat.ObservationElement> = emptyList(),
) {
    // A masked screenshot does not authorize gestures on a password/code page, even after approval.
    val hasSecretFields = secretFieldBounds(windows).isNotEmpty()
    private data class Window(val id: Int, val kind: WindowKind, val packageName: String?, val title: String?, val bounds: Box)
    private val windows = windows.map { Window(it.id, it.kind, it.packageName, it.title, it.bounds) }

    fun comparableTo(previous: ScreenObservation): Boolean =
        !unavailable && !previous.unavailable && !incomplete && !previous.incomplete &&
            elements.isNotEmpty() && previous.elements.isNotEmpty()

    fun sameContext(previous: ScreenObservation): Boolean =
        packageName != null && packageName == previous.packageName && windows.isNotEmpty() &&
            windows == previous.windows && screen == previous.screen

    fun changedFrom(previous: ScreenObservation): Boolean =
        !sameContext(previous) || elements != previous.elements || keyboardShown != previous.keyboardShown

    fun keyboardChange(previous: ScreenObservation): String? {
        val sameWindows = packageName != null && packageName == previous.packageName &&
            windows.map { it.id to it.packageName } == previous.windows.map { it.id to it.packageName }
        if (!sameWindows) return null
        return when {
            previous.keyboardShown == true && keyboardShown == false -> ScreenVerdict.KEYBOARD_HIDDEN
            previous.keyboardShown == false && keyboardShown == true -> ScreenVerdict.KEYBOARD_SHOWN
            else -> null
        }
    }

    /** A canvas may have no numbered elements, but still has a usable, stable image viewport. */
    fun reusableCoordinates(previous: ScreenObservation): Boolean =
        coordinateMismatch(previous, null) == null

    fun reusableLongPress(previous: ScreenObservation, x: Int, y: Int): Boolean =
        coordinateMismatch(previous, x to y) == null

    fun coordinateMismatch(previous: ScreenObservation, longPressPoint: Pair<Int, Int>?): String? {
        if (hasSecretFields || previous.hasSecretFields) return "sensitive_fields"
        if (unavailable || previous.unavailable || incomplete || previous.incomplete) return "unreadable_or_incomplete_tree"
        if (keyboardShown == null || keyboardShown != previous.keyboardShown) return "keyboard_changed_or_unknown"
        if (!sameContext(previous)) return "app_window_or_screen_changed"
        if (elements == previous.elements) return null
        val (x, y) = longPressPoint ?: return "screen_elements_changed"
        val window = windows.firstOrNull { it.bounds.contains(x, y) }
        if (window == null || window.kind != WindowKind.APP || window.packageName != packageName)
            return "point_outside_foreground_app"
        fun atPoint(observation: ScreenObservation) =
            observation.targets.filter { it.windowId == window.id && it.bounds.contains(x, y) }
        val local = atPoint(this)
        if (local.none { it.interactive && it.bounds.intersect(window.bounds) != window.bounds })
            return "no_local_interactive_target"
        return if (local == atPoint(previous)) null else "local_target_changed"
    }

}
