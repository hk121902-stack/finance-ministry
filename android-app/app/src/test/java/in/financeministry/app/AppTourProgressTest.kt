package `in`.financeministry.app

import `in`.financeministry.app.feature.shouldShowAppTour
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTourProgressTest {
    @Test fun new_install_is_offered_the_tour() { assertTrue(shouldShowAppTour(0, 18)) }
    @Test fun update_is_offered_the_tour_even_if_the_previous_tour_was_completed() { assertTrue(shouldShowAppTour(17, 18)) }
    @Test fun completing_or_skipping_this_version_does_not_repeat_it() { assertFalse(shouldShowAppTour(18, 18)) }
    @Test fun opening_an_older_build_does_not_repeat_a_newer_tour() { assertFalse(shouldShowAppTour(19, 18)) }
}
