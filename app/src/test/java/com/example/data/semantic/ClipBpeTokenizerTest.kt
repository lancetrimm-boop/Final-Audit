package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Test

class ClipBpeTokenizerTest {

    @Test
    fun testBasicTokenization() {
        val vocab = mapOf(
            "<start_of_text>" to 1,
            "<end_of_text>" to 2,
            "beach</w>" to 3,
            "sunny</w>" to 4,
            "b" to 10, "e" to 11, "a" to 12, "c" to 13, "h</w>" to 14,
            "s" to 15, "u" to 16, "n" to 17, "y</w>" to 18
        )
        // Provide merges to combine characters into the full word
        val merges = listOf(
            "b" to "e",
            "be" to "a",
            "bea" to "c",
            "beac" to "h</w>",
            "s" to "u",
            "su" to "n",
            "sun" to "n",
            "sunn" to "y</w>"
        )
        val tokenizer = ClipBpeTokenizer(vocab, merges)
        
        val result = tokenizer.tokenize("beach sunny")
        
        // SOT, beach, sunny, EOT, ... (rest 0s)
        assertEquals(1L, result[0])
        assertEquals(3L, result[1])
        assertEquals(4L, result[2])
        assertEquals(2L, result[3])
    }

    @Test
    fun testTruncation() {
        val vocab = mutableMapOf(
            "<start_of_text>" to 1,
            "<end_of_text>" to 2
        )
        for (i in 0..100) vocab["token$i</w>"] = i + 10
        
        val tokenizer = ClipBpeTokenizer(vocab, emptyList())
        val longText = (1..100).joinToString(" ") { "token$it" }
        
        val result = tokenizer.tokenize(longText)
        
        assertEquals(77, result.size)
        assertEquals(1L, result[0]) // SOT
        assertEquals(2L, result[76]) // EOT at the very end
    }
}
