package `in`.financeministry.app.data

import org.json.JSONObject
import java.math.BigInteger
import java.util.Locale

/** Validate the complete allocation graph before any restore deletes the current ledger. */
internal fun validateGroupBackup(tables: Map<String, Map<String, JSONObject>>) {
    val members = tables.getValue("local_members")
    val names = mutableSetOf<String>()
    members.values.forEach {
        val name = it.getString("name")
        validateMemberName(name)
        require(name == name.trim() && names.add(name.lowercase(Locale.ROOT)) && it.getLong("createdAt") > 0) { "Invalid or duplicate local person." }
    }
    val transactions = tables.getValue("transactions")
    val shares = tables.getValue("group_shares").values.groupBy { it.getString("expenseId") }
    shares.forEach { (id, rows) ->
        val expense = requireNotNull(transactions[id]) { "Split expense is missing." }
        require(expense.getString("ownership") == "Group" && expense.getString("direction") == "Debit" &&
            expense.getLong("repaymentExpected") == 1L && expense.getString("status") == "Successful" &&
            expense.getString("transactionType") !in setOf("SelfTransfer", "CardRepayment")) { "Invalid individual split expense." }
        val inputs = rows.map {
            val member = it.getString("memberId")
            require(member in members) { "Split person is missing." }
            MemberShareInput(member, it.getLong("amountMinor"))
        }
        require(!expense.isNull("amountMinor") && !expense.isNull("personalShareMinor")) { "Split amounts are missing." }
        GroupSplitAccounting.validate(expense.getLong("amountMinor"), expense.getLong("personalShareMinor"), inputs)
    }
    val received = mutableMapOf<Pair<String, String>, BigInteger>()
    tables.getValue("repayments").values.forEach { receipt ->
        val expense = receipt.getString("expenseId")
        val allocations = shares[expense].orEmpty()
        if (allocations.isEmpty()) require(receipt.isNull("memberId")) { "Repayment has no individual split." }
        else {
            require(!receipt.isNull("memberId") && receipt.getString("method") != "Legacy") { "Individual repayment needs a person and receipt." }
            val member = receipt.getString("memberId")
            val share = requireNotNull(allocations.firstOrNull { it.getString("memberId") == member }) { "Repayment person is not in this expense." }
            val key = expense to member
            val total = (received[key] ?: BigInteger.ZERO) + BigInteger.valueOf(receipt.getLong("amountMinor"))
            require(total <= BigInteger.valueOf(share.getLong("amountMinor"))) { "Repayment exceeds this person's share." }
            received[key] = total
        }
    }
}
