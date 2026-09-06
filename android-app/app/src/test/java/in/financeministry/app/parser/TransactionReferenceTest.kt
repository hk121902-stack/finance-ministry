package `in`.financeministry.app.parser

import org.junit.Assert.*
import org.junit.Test

class TransactionReferenceTest {
    @Test fun only_explicit_unambiguous_references_are_extracted() {
        assertEquals("000000000001", TransactionReference.extract("UPI Ref No 000000000001"))
        assertEquals("000000000001", TransactionReference.extract("UTR: 000000000001"))
        assertNull(TransactionReference.extract("INR 42 account 000000000001"))
        assertNull(TransactionReference.extract("Ref 000000000001 original Ref 000000000002"))
        assertNull(TransactionReference.extract("Ref 123"))
        assertNull(TransactionReference.extract("Ref 1234567890123456789012345"))
    }
}
