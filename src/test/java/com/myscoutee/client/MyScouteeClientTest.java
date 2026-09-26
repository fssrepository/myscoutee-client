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
            byte[] response = """
                    {"connected":true,"maxBatchSize":1000,"profileId":"profile-1",
                    "profileName":"Owner","groupId":"group-1","groupName":"Team",
                    "operations":["createEvents"],"inviteGroups":[{"id":"group-1","name":"Team"}]}
                    """
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
        assertEquals("profile-1", response.profileId());
        assertEquals("group-1", response.groupId());
        assertEquals(List.of("createEvents"), response.operations());
        assertEquals("group-1", response.inviteGroups().get(0).id());
        assertEquals("Bearer msc_secret", authorization.get());
        assertEquals(clientId.toString(), clientIdHeader.get());
    }

    @Test
    void createsMingleWithOrganizerLinkAndReadsDetailsEncountersAndInvites() throws IOException {
        AtomicReference<JsonNode> submitted = new AtomicReference<>();
        var json = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/events", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            if (path.endsWith("/encounters")) {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("offset=0&limit=1000", exchange.getRequestURI().getQuery());
                response = "{\"id\":\"partner-event\",\"externalId\":\"event\",\"revision\":7,\"pairs\":[],\"total\":0,\"nextOffset\":null}";
            } else if (path.endsWith("/invites")) {
                JsonNode request = json.readTree(exchange.getRequestBody());
                assertEquals("partner-42", request.path("participantIds").get(0).asText());
                response = "{\"items\":[{\"id\":\"partner-42\",\"inviteUrl\":\"https://example.test/game?partnerInvite=opaque\",\"claimed\":false}]}";
            } else if (exchange.getRequestMethod().equals("GET")) {
                response = "{\"id\":\"partner-event\",\"externalId\":\"event\",\"title\":\"Mingle\",\"status\":\"A\",\"sourceLink\":\"https://organizer.test/event\",\"mode\":\"Mingle\",\"mingleConfiguration\":{\"groupSize\":2,\"plannedRounds\":3,\"roundDurationMinutes\":10,\"breakDurationMinutes\":2,\"requireGenderBalance\":false}}";
            } else {
                submitted.set(json.readTree(exchange.getRequestBody()));
                response = "{\"items\":[{\"id\":\"partner-event\",\"externalId\":\"event\",\"result\":\"created\",\"error\":null}]}";
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var client = new MyScouteeClient(new MyScouteeClientConfig(URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"), "msc_test", UUID.randomUUID()));
        var config = new MyScouteeClient.MingleConfiguration(2, 3, 10, 2, false);
        client.createEvents(List.of(new MyScouteeClient.EventItem(UUID.randomUUID().toString(), "Mingle", "Meet", "2030-01-01T10:00:00Z", "2030-01-01T12:00:00Z", "Budapest", 2, 20, "Public", "active", "https://organizer.test/event", List.of(), "Mingle", config)));
        JsonNode item = submitted.get().path("items").get(0);
        assertEquals("Mingle", item.path("mode").asText());
        assertEquals("https://organizer.test/event", item.path("sourceLink").asText());
        assertEquals(2, item.path("mingleConfiguration").path("groupSize").asInt());
        assertEquals(config, client.event("event").mingleConfiguration());
        assertEquals("https://organizer.test/event", client.event("event").sourceLink());
        assertEquals(7, client.encounters("event", 0, 1000).revision());
        assertEquals("partner-42", client.inviteParticipants("event", List.of("partner-42")).items().get(0).id());
    }


    @Test
    void groupInvitationsUseGroupPathAndPreserveParticipantIds() throws IOException {
        AtomicReference<JsonNode> submitted = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/groups/team/with space/invites", exchange -> {
            method.set(exchange.getRequestMethod());
            submitted.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = "{\"items\":[{\"id\":\"guest-1\",\"inviteUrl\":\"https://example.test/game?partnerInvite=opaque\",\"claimed\":false}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var client = new MyScouteeClient(new MyScouteeClientConfig(URI.create("http://localhost:" + server.getAddress().getPort() + "/api/integrations/v1"), "msc_test", UUID.randomUUID()));
        assertEquals("guest-1", client.inviteGroupParticipants("team/with space", List.of("guest-1")).items().get(0).id());
        assertEquals("POST", method.get());
        assertEquals("guest-1", submitted.get().path("participantIds").get(0).asText());
        assertThrows(IllegalArgumentException.class, () -> client.inviteGroupParticipants("", List.of("guest-1")));
        assertThrows(IllegalArgumentException.class, () -> client.inviteGroupParticipants("team", List.of()));
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
    void watchRegistersAndUnregistersTheCallbackForTheSameClient() throws IOException {
        AtomicReference<String> registered = new AtomicReference<>();
        AtomicReference<String> unregistered = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/integrations/v1/watch", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = readTree(request);
            boolean enabled = body.path("enabled").asBoolean();
            if (enabled) {
                registered.set(request);
            } else {
                unregistered.set(request);
            }
            byte[] response = enabled
                    ? ("{\"enabled\":true,\"callbackUrl\":\"https://example.test/hooks/myscoutee\","
                            + "\"events\":[\"event.changed\"]}").getBytes(StandardCharsets.UTF_8)
                    : "{\"enabled\":false,\"callbackUrl\":null,\"events\":[]}"
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

        MyScouteeClient.WatchResponse watch = client.watch(
                "https://example.test/hooks/myscoutee",
                List.of("event.changed"));
        MyScouteeClient.WatchResponse unwatch = client.unwatch();

        assertEquals(true, watch.enabled());
        assertEquals(List.of("event.changed"), watch.events());
        assertEquals(false, unwatch.enabled());
        assertEquals("https://example.test/hooks/myscoutee", readTree(registered.get()).path("callbackUrl").asText());
        assertEquals(false, readTree(unregistered.get()).path("enabled").asBoolean());
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
