package com.helix.spikes.a2a.sdk;

import com.google.gson.Gson;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.android.AndroidA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.rest.RestTransportConfig;
import org.a2aproject.sdk.client.transport.rest.RestTransport;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;

/** HXA-077 compile/R8 entry points. This package is not a production A2A implementation. */
public final class OfficialSdkProbe {
    private OfficialSdkProbe() {}

    public static AgentCard parseCard(String json) {
        return new Gson().fromJson(json, AgentCard.class);
    }

    public static A2AHttpClient androidHttpClient() {
        return new AndroidA2AHttpClient();
    }

    public static JSONRPCTransportConfig jsonRpcConfig() {
        return new JSONRPCTransportConfig(androidHttpClient());
    }

    public static RestTransportConfig restConfig() {
        return new RestTransportConfig(androidHttpClient());
    }

    public static Client buildJsonRpcClient(String cardJson) throws A2AClientException {
        return Client.builder(parseCard(cardJson))
                .withTransport(JSONRPCTransport.class, jsonRpcConfig())
                .build();
    }

    public static Client buildRestClient(String cardJson) throws A2AClientException {
        return Client.builder(parseCard(cardJson))
                .withTransport(RestTransport.class, restConfig())
                .build();
    }

    public static Class<?> clientType() {
        return Client.class;
    }
}
