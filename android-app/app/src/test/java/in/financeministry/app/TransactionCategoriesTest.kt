package `in`.financeministry.app

import `in`.financeministry.app.core.model.transactionCategories
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCategoriesTest {
    @Test fun income_and_investment_are_available_as_transaction_categories() {
        assertTrue("Income must be selectable", "Income" in transactionCategories)
        assertTrue("Investment must be selectable", "Investment" in transactionCategories)
    }
}
