package `in`.financeministry.app

import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.IncomingSms
import `in`.financeministry.app.parser.RuleBasedFinancialSmsParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local-only evaluation. Inputs stay outside APKs; output never includes SMS text. */
class ParserCorpusEvaluationTest {
    @Test fun evaluate_external_sanitized_corpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runParserEvaluation") == "true")
        val input = File(instrumentation.targetContext.filesDir, "parser-evaluation-input.json")
        val cases = JSONArray(input.readText())
        assertTrue(cases.length() > 0)
        val parser = RuleBasedFinancialSmsParser()
        val output = JSONArray()
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val result = parser.parse(IncomingSms("SANITIZED-EVALUATION", 1788600000000L, item.getString("body")))
            output.put(JSONObject().put("id", item.getString("id"))
                .put("decision", result.decision.name).put("rule", result.ruleId)
                .put("amountMinor", result.amountMinor ?: JSONObject.NULL)
                .put("direction", result.direction.name).put("status", result.status.name))
        }
        File(instrumentation.targetContext.filesDir, "parser-evaluation-output.json").writeText(output.toString())
        instrumentation.sendStatus(0, android.os.Bundle().apply { putInt("evaluatedCases", cases.length()) })
    }
}
