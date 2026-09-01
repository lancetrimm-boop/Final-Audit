package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Test

class ClipBpeTokenizerTest {

    @Test
    fun testBasicTokenization() {
        val vocab = mapOf(
            "<start_of_text>" to 1,
            "<end_of_text>" to 2,
            "beach" to 3,
            "sunny" to 4
        )
        val merges = emptyList<Pair<String, String>>()
        val tokenizer = ClipBpeTokenizer(vocab, merges)
        
        val result = tokenizer.tokenize("beach sunny")
        
        // SOT, beach, sunny, EOT, ... (rest 0s)
        assertEquals(1L, result[0])
        assertEquals(3L, result[1])
        assertEquals(4L, result[2])
        assertEquals(2L, result[3])
        assertEquals(0L, result[4])
        assertEquals(77, result.size)
    }

    @Test
    fun testTruncation() {
        val vocab = mutableMapOf(
            "<start_of_text>" to 1,
            "<end_of_text>" to 2
        )
        for (i in 0..100) vocab["token$i"] = i + 10
        
        val tokenizer = ClipBpeTokenizer(vocab, emptyList())
        val longText = (1..100).joinToString(" ") { "token$it" }
        
        val result = tokenizer.tokenize(longText)
        
        assertEquals(77, result.size)
        assertEquals(1L, result[0]) // SOT
        assertEquals(2L, result[76]) // EOT at the very end
    }
}
