package com.myscoutee.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class MyScouteeClient {
    public static final int MAX_BATCH_SIZE = 1000;
    public static final String CLIENT_ID_HEADER = "X-MyScoutee-Client-Id";

    private final MyScouteeClientConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MyScouteeClient(MyScouteeClientConfig config) {
        this(
                config,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                new ObjectMapper());
    }

    public MyScouteeClient(
            MyScouteeClientConfig config,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ConnectResponse connect() {
        return post("/connect", objectMapper.createObjectNode(), ConnectResponse.class);
    }

    public WatchResponse watch(String callbackUrl, List<String> events) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("callbackUrl is required");
        }
        return post(
                "/watch",
                new WatchRequest(callbackUrl.trim(), events == null ? List.of() : List.copyOf(events), true),
                WatchResponse.class);
    }

    public WatchResponse unwatch() {
        return post("/watch", new WatchRequest(null, List.of(), false), WatchResponse.class);
    }

    public BatchResponse createAssets(List<AssetItem> items) {
        requireBatch(items);
        return post("/assets", new BatchRequest<>(items), BatchResponse.class);
    }

    public BatchResponse createAssets(List<AssetItem> items, Map<String, Path> images) {
        requireBatch(items);
        requireImages(items.stream().map(AssetItem::id).collect(java.util.stream.Collectors.toSet()), images);
        return postMultipart("/assets", new BatchRequest<>(items), images, BatchResponse.class);
    }

    public BatchResponse createEvents(List<EventItem> items) {
        requireBatch(items);
        return post("/events", new BatchRequest<>(items), BatchResponse.class);
    }

    public BatchResponse createEvents(List<EventItem> items, Map<String, Path> images) {
        requireBatch(items);
        requireImages(items.stream().map(EventItem::id).collect(java.util.stream.Collectors.toSet()), images);
        return postMultipart("/events", new BatchRequest<>(items), images, BatchResponse.class);
    }

    public JsonNode createAssets(JsonNode request) {
        return post("/assets", request, JsonNode.class);
    }

    public JsonNode createAssets(JsonNode request, Map<String, Path> images) {
        requireJsonBatchAndImages(request, images);
        return postMultipart("/assets", request, images, JsonNode.class);
    }

    public JsonNode createEvents(JsonNode request) {
        return post("/events", request, JsonNode.class);
    }

    public JsonNode createEvents(JsonNode request, Map<String, Path> images) {
        requireJsonBatchAndImages(request, images);
        return postMultipart("/events", request, images, JsonNode.class);
    }

    public ParticipantInvitesResponse inviteParticipants(String eventExternalId, List<String> participantIds) {
        requireBatch(participantIds);
        return post(eventPath(eventExternalId) + "/invites", Map.of("participantIds", participantIds), ParticipantInvitesResponse.class);
    }

    /** Creates claimable links for a group administered by the token owner. */
    public ParticipantInvitesResponse inviteGroupParticipants(String groupId, List<String> participantIds) {
        requireBatch(participantIds);
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId is required");
        return post("/groups/" + java.net.URLEncoder.encode(groupId.trim(), StandardCharsets.UTF_8).replace("+", "%20") + "/invites",
                Map.of("participantIds", participantIds), ParticipantInvitesResponse.class);
    }


    public EventResponse event(String externalId) {
        return request(eventPath(externalId), null, "GET", EventResponse.class);
    }

    public EncountersResponse encounters(String externalId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_BATCH_SIZE) throw new IllegalArgumentException("Invalid encounters page.");
        return request(eventPath(externalId) + "/encounters?offset=" + offset + "&limit=" + limit,
                null, "GET", EncountersResponse.class);
    }

    private String eventPath(String externalId) {
        if (externalId == null || externalId.isBlank()) throw new IllegalArgumentException("externalId is required");
        return "/events/" + java.net.URLEncoder.encode(externalId.trim(), StandardCharsets.UTF_8);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return request(path, body, "POST", responseType);
    }

    private <T> T request(String path, Object body, String method, Class<T> responseType) {
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(resolve(path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.token())
                    .header(CLIENT_ID_HEADER, config.clientId().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MyScouteeApiException(response.statusCode(), response.body());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to process the API payload.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to reach the MyScoutee API.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The MyScoutee API request was interrupted.", exception);
        }
    }

    private <T> T postMultipart(
            String path,
            Object body,
            Map<String, Path> requestedImages,
            Class<T> responseType) {
        Map<String, Path> images = requestedImages == null ? Map.of() : new LinkedHashMap<>(requestedImages);
        if (images.isEmpty()) {
            return post(path, body, responseType);
        }
        try {
            String boundary = "myscoutee-" + UUID.randomUUID();
            List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
            parts.add(textPart(
                    boundary,
                    "request",
                    "application/json",
                    objectMapper.writeValueAsString(body)));
            for (Map.Entry<String, Path> image : images.entrySet()) {
                Path file = image.getValue();
                String contentType = Files.probeContentType(file);
                if (contentType == null || !contentType.startsWith("image/")) {
                    throw new IllegalArgumentException("Image content type could not be determined: " + file);
                }
                parts.add(filePart(boundary, image.getKey(), file, contentType));
            }
            parts.add(HttpRequest.BodyPublishers.ofByteArray(
                    ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));

            HttpRequest request = HttpRequest.newBuilder(resolve(path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.token())
                    .header(CLIENT_ID_HEADER, config.clientId().toString())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MyScouteeApiException(response.statusCode(), response.body());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to process the API payload.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read an image or reach the MyScoutee API.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The MyScoutee API request was interrupted.", exception);
        }
    }

    private HttpRequest.BodyPublisher textPart(
            String boundary,
            String name,
            String contentType,
            String value) {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n"
                + value + "\r\n";
        return HttpRequest.BodyPublishers.ofByteArray(part.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.BodyPublisher filePart(
            String boundary,
            String itemId,
            Path file,
            String contentType) throws IOException {
        String filename = file.getFileName().toString().replace("\"", "");
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + itemId
                + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(header.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    private URI resolve(String path) {
        return URI.create(config.baseUrl() + (path.startsWith("/") ? path : "/" + path));
    }

    private void requireBatch(List<?> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A batch must contain at least one item.");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("A batch can contain at most " + MAX_BATCH_SIZE + " items.");
        }
    }

    private void requireJsonBatchAndImages(JsonNode request, Map<String, Path> images) {
        if (request == null || !request.path("items").isArray()) {
            throw new IllegalArgumentException("The request must contain an items array.");
        }
        Set<String> itemIds = new java.util.HashSet<>();
        request.path("items").forEach(item -> itemIds.add(item.path("id").asText("")));
        requireImages(itemIds, images);
    }

    private void requireImages(Set<String> itemIds, Map<String, Path> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        if (images.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("A batch can contain at most " + MAX_BATCH_SIZE + " images.");
        }
        images.forEach((itemId, file) -> {
            try {
                UUID.fromString(itemId);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Each image key must be an item id UUID: " + itemId, exception);
            }
            if (!itemIds.contains(itemId)) {
                throw new IllegalArgumentException("Image id is not present in the request batch: " + itemId);
            }
            if (file == null || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Image file does not exist: " + file);
            }
        });
    }

    public record ConnectResponse(boolean connected, int maxBatchSize, String profileId,
            String profileName, String groupId, String groupName, List<String> operations,
            List<IntegrationGroupScope> inviteGroups) {
        public ConnectResponse(boolean connected, int maxBatchSize) {
            this(connected, maxBatchSize, null, null, null, null, List.of(), List.of());
        }
    }

    public record IntegrationGroupScope(String id, String name) { }

    public record WatchRequest(String callbackUrl, List<String> events, boolean enabled) {
    }

    public record WatchResponse(boolean enabled, String callbackUrl, List<String> events) {
    }

    public record BatchRequest<T>(List<T> items) {
    }

    public record BatchResponse(List<BatchResult> items) {
    }

    public record BatchResult(String id, String externalId, String result, String error) {
    }

    public record AssetItem(
            String id,
            String type,
            String title,
            String subtitle,
            String category,
            String city,
            Integer capacityTotal,
            Integer quantity,
            String details,
            String sourceLink,
            List<String> routes,
            String visibility) {
    }

    public record EventItem(
            String id,
            String title,
            String subtitle,
            String startAt,
            String endAt,
            String location,
            Integer capacityMin,
            Integer capacityMax,
            String visibility,
            String status,
            String sourceLink,
            List<String> topics,
            String mode,
            MingleConfiguration mingleConfiguration) {
        public EventItem(String id, String title, String subtitle, String startAt, String endAt, String location,
                Integer capacityMin, Integer capacityMax, String visibility, String status, String sourceLink, List<String> topics) {
            this(id, title, subtitle, startAt, endAt, location, capacityMin, capacityMax, visibility, status,
                    sourceLink, topics, null, null);
        }
    }
    public record MingleConfiguration(Integer groupSize, Integer plannedRounds, Integer roundDurationMinutes,
            Integer breakDurationMinutes, Boolean requireGenderBalance) { }
    public record EventResponse(String id, String externalId, String title, String status, String sourceLink,
            String mode, MingleConfiguration mingleConfiguration) { }
    public record EncounterParticipant(String id, String externalId) { }
    public record EncounterPair(EncounterParticipant first, EncounterParticipant second) { }
    public record EncountersResponse(String id, String externalId, int revision, List<EncounterPair> pairs,
            int total, Integer nextOffset) { }
    public record ParticipantInvite(String id, String inviteUrl, boolean claimed) { }
    public record ParticipantInvitesResponse(List<ParticipantInvite> items) { }

}
