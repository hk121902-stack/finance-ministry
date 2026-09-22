package `in`.financeministry.app.data

import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.util.UUID
import `in`.financeministry.app.core.model.transactionCategories

data class BudgetProgress(val spentMinor: Long, val limitMinor: Long) {
    init { require(spentMinor >= 0); require(limitMinor > 0) }
    val remainingMinor: Long get() = (limitMinor - spentMinor).coerceAtLeast(0)
    val percentUsed: Int get() = ((BigInteger.valueOf(spentMinor) * BigInteger.valueOf(100) +
        BigInteger.valueOf(limitMinor / 2)) / BigInteger.valueOf(limitMinor)).min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    fun reached(percent: Int): Boolean {
        require(percent in 1..100)
        return BigInteger.valueOf(spentMinor) * BigInteger.valueOf(100) >=
            BigInteger.valueOf(limitMinor) * BigInteger.valueOf(percent.toLong())
    }
}

data class BudgetStatus(val budget: BudgetEntity, val spentMinor: Long, val progress: BudgetProgress)

object BudgetAlertDecision {
    fun notificationKey(status: BudgetStatus, month: LocalDate): String? {
        val threshold = status.budget.alertPercent ?: return null
        if (!status.progress.reached(threshold)) return null
        return "${status.budget.id}:${YearMonth.from(month)}:${status.budget.updatedAt}:$threshold"
    }
    fun shouldNotify(status: BudgetStatus, month: LocalDate, previousKey: String?): Boolean =
        notificationKey(status, month)?.let { it != previousKey } == true
}

object RecurringSchedule {
    fun dueDate(year: Int, month: Int, preferredDay: Int): LocalDate {
        require(preferredDay in 1..31)
        val period = YearMonth.of(year, month)
        return period.atDay(preferredDay.coerceAtMost(period.lengthOfMonth()))
    }

    fun nextDue(preferredDay: Int, from: LocalDate): LocalDate {
        val thisMonth = dueDate(from.year, from.monthValue, preferredDay)
        if (!thisMonth.isBefore(from)) return thisMonth
        val next = YearMonth.from(from).plusMonths(1)
        return dueDate(next.year, next.monthValue, preferredDay)
    }
}

private fun optionalToolAmount(value: String, optional: Boolean = false): Long? {
    if (optional && value.isBlank()) return null
    val amount = try { BigDecimal(value.trim()).movePointRight(2).longValueExact() }
    catch (_: Exception) { throw IllegalArgumentException("Enter a positive amount with up to two decimal places.") }
    require(amount > 0) { "Enter a positive amount with up to two decimal places." }
    return amount
}

suspend fun TransactionRepository.budgets(): List<BudgetEntity> = withLedger { it.transactions().budgets() }

suspend fun TransactionRepository.budgetStatuses(month: LocalDate, today: LocalDate = LocalDate.now()): List<BudgetStatus> = withLedger { db ->
    val insight = SpendingInsights.calculate(db.transactions().all(), month, today)
    val max = BigInteger.valueOf(Long.MAX_VALUE)
    val spent = insight.categories.associate { it.category to it.amount.coerceAtMost(max).toLong() }
    db.transactions().budgets().map { budget ->
        val categorySpent = spent[budget.category] ?: 0L
        BudgetStatus(budget, categorySpent, BudgetProgress(categorySpent, budget.monthlyLimitMinor))
    }
}

suspend fun TransactionRepository.saveBudget(category: String, amount: String, alertPercent: Int?): BudgetEntity = withLedger { db ->
    require(category in transactionCategories) { "Choose a supported category." }
    require(alertPercent == null || alertPercent in 50..100) { "Choose an alert from 50% to 100%." }
    val limit = requireNotNull(optionalToolAmount(amount))
    val dao = db.transactions()
    val existing = dao.budgets().firstOrNull { it.category == category }
    val now = System.currentTimeMillis()
    val saved = existing?.copy(monthlyLimitMinor = limit, alertPercent = alertPercent, updatedAt = now)
        ?: BudgetEntity(UUID.randomUUID().toString(), category, limit, alertPercent, now, now)
    if (existing == null) dao.addBudget(saved) else dao.updateBudget(saved)
    revision.value++
    saved
}

suspend fun TransactionRepository.deleteBudget(id: String) = withLedger { db ->
    requireNotNull(db.transactions().budget(id)) { "Budget no longer exists." }
    db.transactions().deleteBudget(id); revision.value++
}

suspend fun TransactionRepository.recurringReminders(): List<RecurringReminderEntity> = withLedger { it.transactions().recurringReminders() }

suspend fun TransactionRepository.recurringPaymentCandidates(reminderId: String): List<TransactionEntity> = withLedger { db ->
    val reminder = requireNotNull(db.transactions().recurringReminder(reminderId)) { "Reminder no longer exists." }
    val reversed = db.transactions().reversedOriginals().toSet()
    db.transactions().all().asSequence().filter { row ->
        RepaymentAccounting.eligible(row, reversed) && row.direction == "Debit" &&
            (reminder.amountMinor == null || row.amountMinor == reminder.amountMinor)
    }.take(20).toList()
}

suspend fun TransactionRepository.saveRecurringReminder(title: String, amount: String, preferredDay: Int,
    category: String): RecurringReminderEntity = withLedger { db ->
    val cleanTitle = title.trim()
    require(cleanTitle.length in 2..40) { "Enter a short reminder name." }
    require(preferredDay in 1..31) { "Choose a day from 1 to 31." }
    require(category in transactionCategories) { "Choose a supported category." }
    val parsedAmount = optionalToolAmount(amount, optional = true)
    val now = System.currentTimeMillis()
    val saved = RecurringReminderEntity(UUID.randomUUID().toString(), cleanTitle, parsedAmount,
        preferredDay, category, true, null, null, now, now)
    db.transactions().addRecurringReminder(saved); revision.value++
    if (isMainLedger) `in`.financeministry.app.sms.RecurringPaymentReminder.schedule(applicationContext)
    saved
}

suspend fun TransactionRepository.setRecurringReminderActive(id: String, active: Boolean) = withLedger { db ->
    val row = requireNotNull(db.transactions().recurringReminder(id)) { "Reminder no longer exists." }
    db.transactions().updateRecurringReminder(row.copy(active = active, updatedAt = System.currentTimeMillis()))
    if (isMainLedger) `in`.financeministry.app.sms.RecurringPaymentReminder.sync(applicationContext,
        db.transactions().recurringReminders().any { it.active })
    revision.value++
}

suspend fun TransactionRepository.linkRecurringPayment(id: String, transactionId: String) = withLedger { db ->
    val reminder = requireNotNull(db.transactions().recurringReminder(id)) { "Reminder no longer exists." }
    val payment = requireNotNull(db.transactions().get(transactionId)) { "Payment no longer exists." }
    require(RepaymentAccounting.eligible(payment, db.transactions().reversedOriginals().toSet()) && payment.direction == "Debit") {
        "Choose a confirmed money-out transaction."
    }
    db.transactions().updateRecurringReminder(reminder.copy(lastLinkedTransactionId = payment.id,
        lastLinkedAt = payment.effectiveTimestamp, updatedAt = System.currentTimeMillis()))
    revision.value++
}

suspend fun TransactionRepository.deleteRecurringReminder(id: String) = withLedger { db ->
    requireNotNull(db.transactions().recurringReminder(id)) { "Reminder no longer exists." }
    db.transactions().deleteRecurringReminder(id)
    if (isMainLedger) `in`.financeministry.app.sms.RecurringPaymentReminder.sync(applicationContext,
        db.transactions().recurringReminders().any { it.active })
    revision.value++
}
