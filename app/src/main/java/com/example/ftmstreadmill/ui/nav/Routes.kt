package com.example.ftmstreadmill.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Destinations. Top-level tabs show the navigation bar; everything else is full screen. */
sealed interface Route : NavKey {
    @Serializable data object Control : Route
    @Serializable data object Workouts : Route
    @Serializable data object History : Route

    @Serializable data class WorkoutDetail(val savedId: Long? = null, val presetId: String? = null) : Route

    /** New workout when both ids are null; [presetId] starts from a copy of that preset. */
    @Serializable data class Editor(val workoutId: Long? = null, val presetId: String? = null) : Route

    @Serializable data object Run : Route
    @Serializable data class SessionDetail(val sessionId: Long) : Route
    @Serializable data object WorkoutSettings : Route
}

val Route.isTopLevel: Boolean get() = this == Route.Control || this == Route.Workouts || this == Route.History

/**
 * Navigation actions. There is one back stack: the start tab (Control) is always at the bottom, so
 * Back from another tab returns there, as Material navigation guidance recommends.
 */
class Navigator(private val backStack: MutableList<NavKey>) {
    val current: Route get() = backStack.last() as Route

    val currentTab: Route get() = backStack.filterIsInstance<Route>().lastOrNull { it.isTopLevel } ?: Route.Control

    fun selectTab(tab: Route) {
        backStack.clear()
        backStack += Route.Control
        if (tab != Route.Control) backStack += tab
    }

    fun push(route: Route) {
        // A destination may only appear once; bring an existing Run screen forward instead.
        backStack.remove(route)
        backStack += route
    }

    /** Replaces the current destination, e.g. editor → detail after saving a new workout. */
    fun replace(route: Route) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        push(route)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun openRun() = push(Route.Run)
}
