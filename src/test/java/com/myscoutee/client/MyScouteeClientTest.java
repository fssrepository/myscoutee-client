package com.myscoutee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class MyScouteeClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void connectSendsBearerTokenAndStableClientId() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> clientIdHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/connect", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            clientIdHeader.set(exchange.getRequestHeaders().getFirst(MyScouteeClient.CLIENT_ID_HEADER));
            byte[] response = "{\"connected\":true,\"maxBatchSize\":1000}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        UUID clientId = UUID.randomUUID();
        MyScouteeClient client = new MyScouteeClient(new MyScouteeClientConfig(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"),
                "msc_secret",
                clientId));

        MyScouteeClient.ConnectResponse response = client.connect();

        assertEquals(true, response.connected());
        assertEquals(1000, response.maxBatchSize());
        assertEquals("Bearer msc_secret", authorization.get());
        assertEquals(clientId.toString(), clientIdHeader.get());
    }

    @Test
    void missingTokenIsRejectedBeforeARequestCanBeSent() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MyScouteeClientConfig(
                        URI.create("http://localhost/api/integrations/v1"),
                        "  ",
                        UUID.randomUUID()));

        assertEquals("token is required", exception.getMessage());
    }

    @Test
    void unauthorizedResponseIsExposedWithStatusAndServerMessage() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/connect", exchange -> {
            byte[] response = "{\"message\":\"A valid integration token is required.\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        MyScouteeClient client = new MyScouteeClient(new MyScouteeClientConfig(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"),
                "msc_invalid",
                UUID.randomUUID()));

        MyScouteeApiException exception = assertThrows(MyScouteeApiException.class, client::connect);

        assertEquals(401, exception.statusCode());
        assertTrue(exception.responseBody().contains("A valid integration token is required."));
    }

    @Test
    void rejectedItemKeepsItsCallerIdAndValidationError() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/assets", exchange -> {
            byte[] response = ("{\"items\":[{\"id\":\"hotel-room-101\",\"externalId\":null,"
                    + "\"result\":\"rejected\",\"error\":\"id_must_be_uuid\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        MyScouteeClient client = new MyScouteeClient(new MyScouteeClientConfig(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"),
                "msc_secret",
                UUID.randomUUID()));

        MyScouteeClient.BatchResponse response = client.createAssets(List.of(new MyScouteeClient.AssetItem(
                "hotel-room-101",
                "Accommodation",
                "Room",
                null,
                "Room",
                null,
                1,
                1,
                null,
                null,
                List.of(),
                "Private")));

        assertEquals("hotel-room-101", response.items().get(0).id());
        assertEquals(null, response.items().get(0).externalId());
        assertEquals("rejected", response.items().get(0).result());
        assertEquals("id_must_be_uuid", response.items().get(0).error());
    }

    @Test
    void typedBatchRejectsMoreThanOneThousandItemsBeforeSending() {
        MyScouteeClient client = new MyScouteeClient(new MyScouteeClientConfig(
                URI.create("http://localhost/api/integrations/v1"),
                "msc_secret",
                UUID.randomUUID()));
        List<MyScouteeClient.AssetItem> items = java.util.Collections.nCopies(
                1001,
                new MyScouteeClient.AssetItem(
                        UUID.randomUUID().toString(),
                        "Accommodation",
                        "Room",
                        null,
                        "Room",
                        null,
                        1,
                        1,
                        null,
                        null,
                        List.of(),
                        "Private"));

        assertThrows(IllegalArgumentException.class, () -> client.createAssets(items));
    }

    @Test
    void fixtureBatchesAreSentToTheirDocumentedEndpoints() throws IOException {
        AtomicReference<String> assetRequest = new AtomicReference<>();
        AtomicReference<String> eventRequest = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        registerBatchEndpoint("/api/integrations/v1/assets", assetRequest);
        registerBatchEndpoint("/api/integrations/v1/events", eventRequest);
        server.start();
        MyScouteeClient client = new MyScouteeClient(new MyScouteeClientConfig(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"),
                "msc_secret",
                UUID.randomUUID()));

        client.createAssets(readFixture("fixtures/assets.json"));
        client.createEvents(readFixture("fixtures/events.json"));

        assertEquals(2, readTree(assetRequest.get()).path("items").size());
        assertEquals(2, readTree(eventRequest.get()).path("items").size());
    }

    @Test
    void imageIsUploadedOnTheSameAssetEndpointUnderItsItemId() throws IOException {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/assets", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = ("{\"items\":[{\"id\":\"7d857d7f-8d48-4cf1-95cc-cbf257640afa\","
                    + "\"externalId\":\"8c872909-e5e1-4ad2-80c8-996b3e82ca5b\","
                    + "\"result\":\"created\",\"error\":null}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        MyScouteeClient client = new MyScouteeClient(new MyScouteeClientConfig(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"),
                "msc_secret",
                UUID.randomUUID()));
        Path image = Files.createTempFile("myscoutee-client-image-", ".png");
        try {
            Files.write(image, java.util.Base64.getDecoder().decode(readTextFixture("fixtures/sample-image.png.base64")));

            JsonNode response = client.createAssets(
                    readFixture("fixtures/assets.json"),
                    Map.of("7d857d7f-8d48-4cf1-95cc-cbf257640afa", image));

            String multipart = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
            assertEquals(true, contentType.get().startsWith("multipart/form-data; boundary=myscoutee-"));
            assertEquals(true, multipart.contains("name=\"request\""));
            assertEquals(true, multipart.contains("name=\"7d857d7f-8d48-4cf1-95cc-cbf257640afa\""));
            assertEquals("8c872909-e5e1-4ad2-80c8-996b3e82ca5b",
                    response.path("items").get(0).path("externalId").asText());
        } finally {
            Files.deleteIfExists(image);
        }
    }

    private void registerBatchEndpoint(String path, AtomicReference<String> requestBody) {
        server.createContext(path, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    private JsonNode readFixture(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + name);
            }
            return new ObjectMapper().readTree(input);
        }
    }

    private String readTextFixture(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        }
    }

    private JsonNode readTree(String value) throws IOException {
        return new ObjectMapper().readTree(value);
    }
}
