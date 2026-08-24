package app.hermes.mobile.core.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

@Serializable
data class PairingVector(
    val name: String,
    val uri: String,
    @SerialName("expected_result") val expectedResult: String? = null,
    @SerialName("expected_error") val expectedError: String? = null
)

class PairingVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectors(): List<PairingVector> {
        val possiblePaths = listOf(
            File("docs/pairing-vectors.json"),
            File("../docs/pairing-vectors.json"),
            File("../../docs/pairing-vectors.json")
        )
        val file = possiblePaths.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find docs/pairing-vectors.json in paths: $possiblePaths")
        val content = file.readText(Charsets.UTF_8)
        return json.decodeFromString<List<PairingVector>>(content)
    }

    @Test
    fun testAllPairingVectors() {
        val vectors = loadVectors()
        assertTrue("Vectors list should not be empty", vectors.isNotEmpty())

        val failures = mutableListOf<String>()

        for (vector in vectors) {
            val result = HermesPairingParser.parse(vector.uri)
            if (vector.expectedResult == "success") {
                if (result !is PairingResult.Success) {
                    failures.add("[${vector.name}] Expected SUCCESS, but got: $result")
                }
            } else if (vector.expectedError != null) {
                if (result !is PairingResult.Failure) {
                    failures.add("[${vector.name}] Expected FAILURE with error '${vector.expectedError}', but got: $result")
                } else {
                    val actualCode = result.error.code
                    if (actualCode != vector.expectedError) {
                        failures.add("[${vector.name}] Expected error code '${vector.expectedError}', but got '${actualCode}' (${result.error})")
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Pairing vector test failures (${failures.size}/${vectors.size}):\n" + failures.joinToString("\n"))
        }
    }
}
