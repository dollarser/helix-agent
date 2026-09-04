package com.helix.spikes.a2a.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfficialSdkProbeTest {
    private static final String CARD =
            """
            {
              "name":"Spike Agent",
              "description":"A bounded Android SDK fixture",
              "supportedInterfaces":[{
                "url":"https://agent.example/a2a/v1",
                "protocolBinding":"JSONRPC",
                "protocolVersion":"1.0"
              }],
              "version":"1.0.0",
              "capabilities":{"streaming":true},
              "defaultInputModes":["text/plain"],
              "defaultOutputModes":["text/plain"],
              "skills":[{
                "id":"echo",
                "name":"Echo",
                "description":"Echo one bounded message",
                "tags":["fixture"]
              }]
            }
            """;

    @Test
    public void recordsAndBothHttpTransportsConstructOnJvm() {
        var card = OfficialSdkProbe.parseCard(CARD);

        assertTrue(card.getClass().isRecord());
        assertEquals("Spike Agent", card.name());
        assertEquals("1.0", card.supportedInterfaces().get(0).protocolVersion());
        assertNotNull(OfficialSdkProbe.androidHttpClient());
        assertNotNull(OfficialSdkProbe.jsonRpcConfig());
        assertNotNull(OfficialSdkProbe.restConfig());
        assertEquals("org.a2aproject.sdk.client.Client", OfficialSdkProbe.clientType().getName());

        try (var jsonRpc = OfficialSdkProbe.buildJsonRpcClient(CARD);
                var rest = OfficialSdkProbe.buildRestClient(CARD.replace("JSONRPC", "HTTP+JSON"))) {
            assertNotNull(jsonRpc);
            assertNotNull(rest);
        }
    }
}
