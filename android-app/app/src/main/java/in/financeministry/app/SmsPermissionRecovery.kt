package `in`.financeministry.app

internal fun smsNeedsSettings(granted: Boolean, previouslyRequested: Boolean, canShowRationale: Boolean): Boolean =
    !granted && previouslyRequested && !canShowRationale
