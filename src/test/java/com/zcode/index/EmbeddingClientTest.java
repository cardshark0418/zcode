package com.zcode.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmbeddingClientTest {

    @Test
    void embeddingsEndpointNormalizes() {
        assertEquals("https://api.openai.com/v1/embeddings", EmbeddingClient.embeddingsEndpoint("https://api.openai.com"));
        assertEquals("https://api.openai.com/v1/embeddings", EmbeddingClient.embeddingsEndpoint("https://api.openai.com/v1"));
        assertEquals(
                "https://api.openai.com/v1/embeddings",
                EmbeddingClient.embeddingsEndpoint("https://api.openai.com/v1/embeddings"));
    }
}
