package `in`.financeministry.app.feature

/** Versioned locally: skipping and finishing both acknowledge the current build's tour. */
internal const val APP_TOUR_VERSION_KEY = "app_tour_seen_version"

internal fun shouldShowAppTour(seenVersion: Int, currentVersion: Int): Boolean = seenVersion < currentVersion
