package com.example.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BertWordPieceTokenizerTest {

    private val tokenizer = BertWordPieceTokenizer()

    @Test
    fun testBasicTokenization() {
        val text = "cyberpunk city"
        val output = tokenizer.tokenize(text, maxSeqLength = 10)
        
        // Tokens: [CLS], cyberpunk, city, [SEP]
        assertTrue(output.tokens.contains("cyberpunk"))
        assertTrue(output.tokens.contains("city"))
        assertEquals(BertWordPieceTokenizer.CLS_TOKEN, output.tokens[0])
        assertEquals(BertWordPieceTokenizer.SEP_TOKEN, output.tokens[output.tokens.size - 1])
        
        assertEquals(output.tokens.size, output.inputIds.size)
        assertEquals(1L, output.attentionMask[0])
        assertEquals(1L, output.attentionMask[output.attentionMask.size - 1])
    }

    @Test
    fun testSubwordTokenization() {
        // "rainy" might be "rain", "##y" depending on vocab, but let's test a known word with "##"
        // In our standard vocab, we added "##" affixes.
        val out1 = tokenizer.tokenize("running")
        
        // "running" -> "run", "##ning" or "running"
        // Since "run" is not explicitly in our tiny vocab but "walking" is... 
        // Wait, "walk", "walking" are in vocab.
        val out2 = tokenizer.tokenize("walking")
        assertTrue(out1.tokens.size >= 3) // [CLS], ..., [SEP]
        assertTrue(out2.tokens.size >= 3) // [CLS], ..., [SEP]
    }

    @Test
    fun testTruncation() {
        val text = "this is a very long sentence that should be truncated by the tokenizer"
        val maxLen = 5
        val output = tokenizer.tokenize(text, maxSeqLength = maxLen)
        
        assertEquals(maxLen, output.tokens.size)
        assertEquals(maxLen, output.inputIds.size)
        assertEquals(BertWordPieceTokenizer.CLS_TOKEN, output.tokens[0])
        assertEquals(BertWordPieceTokenizer.SEP_TOKEN, output.tokens[maxLen - 1])
    }

    @Test
    fun testPadding() {
        val text = "short"
        val maxLen = 10
        val output = tokenizer.tokenize(text, maxSeqLength = maxLen, padToMax = true)
        
        assertEquals(maxLen, output.inputIds.size)
        assertEquals(maxLen, output.attentionMask.size)
        
        // Padding tokens have ID 0 and mask 0
        assertEquals(0L, output.inputIds[maxLen - 1])
        assertEquals(0L, output.attentionMask[maxLen - 1])
    }

    @Test
    fun testUnknownToken() {
        // Create a tokenizer with a very limited vocab that doesn't include 'x'
        val limitedTokenizer = BertWordPieceTokenizer(customVocab = mapOf(
            BertWordPieceTokenizer.PAD_TOKEN to 0,
            BertWordPieceTokenizer.UNK_TOKEN to 100,
            BertWordPieceTokenizer.CLS_TOKEN to 101,
            BertWordPieceTokenizer.SEP_TOKEN to 102
        ))
        val text = "xyz"
        val output = limitedTokenizer.tokenize(text)
        
        assertTrue("Tokens should contain UNK: ${output.tokens}", output.tokens.contains(BertWordPieceTokenizer.UNK_TOKEN))
        val unkIndex = output.tokens.indexOf(BertWordPieceTokenizer.UNK_TOKEN)
        assertEquals(BertWordPieceTokenizer.UNK_TOKEN_ID.toLong(), output.inputIds[unkIndex])
    }
}
