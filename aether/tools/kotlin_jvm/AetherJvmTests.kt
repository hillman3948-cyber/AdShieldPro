import com.adshield.pro.AetherEngineTestSuite

/**
 * Standalone runner for the 12 JVM unit tests in AetherEngineTestSuite.kt.
 * The suite uses org.junit annotations/asserts; minimal JVM stubs are supplied
 * so it can run without the Android Gradle plugin or JUnit jars.
 */
fun main() {
    val s = AetherEngineTestSuite()
    val tests: List<Pair<String, () -> Unit>> = listOf(
        "confirmedIntelligenceBlocksAndCarriesProvenance" to s::confirmedIntelligenceBlocksAndCarriesProvenance,
        "subdomainSuffixMatchWorks" to s::subdomainSuffixMatchWorks,
        "knownGoodDomainIsAllowed" to s::knownGoodDomainIsAllowed,
        "typosquatIsFlaggedButNeverAutoBlocked" to s::typosquatIsFlaggedButNeverAutoBlocked,
        "privacyLevelControlsTrackerBlocking" to s::privacyLevelControlsTrackerBlocking,
        "disablingProtectionPreservesExistingDnsBehavior" to s::disablingProtectionPreservesExistingDnsBehavior,
        "failSafeVerdictAllowsDns" to s::failSafeVerdictAllowsDns,
        "temporaryAllowRequiresApprovalAndExpires" to s::temporaryAllowRequiresApprovalAndExpires,
        "verifiedFalsePositiveAndRollbackSnapshot" to s::verifiedFalsePositiveAndRollbackSnapshot,
        "assistantInvestigatesAndResistsPromptInjection" to s::assistantInvestigatesAndResistsPromptInjection,
        "educationQuizGradingAndLabPermissionGate" to s::educationQuizGradingAndLabPermissionGate,
        "normalizationAndLevenshteinHelpers" to s::normalizationAndLevenshteinHelpers
    )
    var failed = 0
    for ((name, fn) in tests) {
        try {
            fn()
            println("PASS  $name")
        } catch (e: Throwable) {
            failed++
            println("FAIL  $name -> $e")
        }
    }
    println("AETHER Kotlin JVM result: ${tests.size} tests, $failed failures")
    if (failed > 0) throw AssertionError("$failed Kotlin JVM tests failed")
}
