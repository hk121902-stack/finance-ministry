package `in`.financeministry.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPermissionRecoveryTest {
    @Test fun first_request_uses_android_prompt() { assertFalse(smsNeedsSettings(false, false, false)) }
    @Test fun denial_with_rationale_can_retry_prompt() { assertFalse(smsNeedsSettings(false, true, true)) }
    @Test fun repeated_denial_requires_settings() { assertTrue(smsNeedsSettings(false, true, false)) }
    @Test fun granted_permission_never_requests_recovery() { assertFalse(smsNeedsSettings(true, true, false)) }
}
