package `in`.financeministry.app

import org.junit.Assert.assertSame
import org.junit.Test

class BuildContractTest {
    @Test
    fun applicationRetainsOneContainerBoundToIt() {
        val application = FinanceMinistryApp()

        val firstContainer = application.container
        val secondContainer = application.container

        assertSame(application, firstContainer.application)
        assertSame(firstContainer, secondContainer)
    }
}
