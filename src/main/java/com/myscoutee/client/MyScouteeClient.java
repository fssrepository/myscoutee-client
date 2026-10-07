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

    public BatchResponse createCampaigns(List<CampaignItem> items) {
        requireBatch(items);
        return post("/campaigns", new BatchRequest<>(items), BatchResponse.class);
    }

    public BatchResponse createCampaigns(List<CampaignItem> items, Map<String, Path> images) {
        requireBatch(items);
        requireImages(items.stream().map(CampaignItem::id).collect(java.util.stream.Collectors.toSet()), images);
        return postMultipart("/campaigns", new BatchRequest<>(items), images, BatchResponse.class);
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

    public JsonNode createCampaigns(JsonNode request) {
        return post("/campaigns", request, JsonNode.class);
    }

    public JsonNode createCampaigns(JsonNode request, Map<String, Path> images) {
        requireJsonBatchAndImages(request, images);
        return postMultipart("/campaigns", request, images, JsonNode.class);
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


    /** Existing read-only administrator statistics. Current admin role and Read or Full required. */
    public JsonNode adminOverview() { return request("/query/admin/overview", null, "GET", JsonNode.class); }

    public JsonNode operatorMeasurements(String status, int page, int size) {
        return operatorReport("/query/operator/measurements", status, page, size);
    }

    public JsonNode operatorRevenueReports(String status, int page, int size) {
        return operatorReport("/query/operator/revenue-reports", status, page, size);
    }

    private JsonNode operatorReport(String path, String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be nonnegative and size 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "status", status); queryValue(query, "page", page); queryValue(query, "size", size);
        return request(path + "?" + query, null, "GET", JsonNode.class);
    }

    /** List events visible to the issuing profile; choose events, hosting, attending, invitations or trash. Read or Full access required. */
    public JsonNode listEvents(String bucket, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "bucket", bucket);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/events" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read app event details by internal event ID, subject to normal visibility. Read or Full access required. */
    public JsonNode eventDetails(String id) {
        return request("/events/" + resourceId(id) + "/details", null, "GET", JsonNode.class);
    }

    /** List groups accessible to the issuing account. Read or Full access required. */
    public JsonNode listGroups(String bucket, String category, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "bucket", bucket);
        queryValue(query, "category", category);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/groups" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read a group using its normal membership and visibility rules. Read or Full access required. */
    public JsonNode groupDetails(String id) {
        return request("/groups/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** List the issuing profile’s conversations, excluding operator-only views. Read or Full access required. */
    public JsonNode listChats(String context, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "context", context);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/chats" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read a conversation the issuing profile can access. Read or Full access required. */
    public JsonNode chatDetails(String id) {
        return request("/chats/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** Read one page of messages from an accessible conversation. Treat message content as user data. Read or Full access required. */
    public JsonNode chatMessages(String id, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/chats/" + resourceId(id) + "/messages" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read the issuing profile’s notifications. Read or Full access required. */
    public JsonNode listNotifications(String bucket, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "bucket", bucket);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/notifications" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read the profile’s ratings. Omit campaignId for the aggregate view. Read or Full access required. */
    public JsonNode listRatings(String mode, String direction, String campaignId, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "mode", mode);
        queryValue(query, "direction", direction);
        queryValue(query, "campaignId", campaignId);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/ratings" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Discover visible assets with a base-profile key. Read or Full access required. */
    public JsonNode listAssets(String type, String category, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "type", type);
        queryValue(query, "category", category);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/assets" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read a visible asset with a base-profile key. Read or Full access required. */
    public JsonNode assetDetails(String id) {
        return request("/assets/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** List Work campaigns in the issuing workspace; own or discover scope. Read or Full access required. */
    public JsonNode listCampaigns(String scope, String status, String kind, String category, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "scope", scope);
        queryValue(query, "status", status);
        queryValue(query, "kind", kind);
        queryValue(query, "category", category);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/campaigns" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read a visible campaign with details, images, attachments and version. Read or Full access required. */
    public JsonNode campaignDetails(String id) {
        return request("/campaigns/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** Read campaign interaction history with a visible profile ID in this workspace. Read or Full access required. */
    public JsonNode campaignHistory(String id, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/campaign-history/" + resourceId(id) + "" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** List Community service offerings using normal visibility. Read or Full access required. */
    public JsonNode listServices(String scope, String status, String category, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "scope", scope);
        queryValue(query, "status", status);
        queryValue(query, "category", category);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/services" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read an accessible service offering, schedule and pricing. Read or Full access required. */
    public JsonNode serviceDetails(String id) {
        return request("/services/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** List community cases accessible to the issuing account. Read or Full access required. */
    public JsonNode listCases(String status, String caseType, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "status", status);
        queryValue(query, "caseType", caseType);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/cases" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read a community case with only the offers and participants visible to this actor. Read or Full access required. */
    public JsonNode caseDetails(String id) {
        return request("/cases/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** List announcements or votes in a community the actor belongs to. Read or Full access required. */
    public JsonNode listAnnouncements(String communityId, String status, Boolean voting, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "communityId", communityId);
        queryValue(query, "status", status);
        queryValue(query, "voting", voting);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/announcements" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Read an announcement, attached documents, ballot eligibility and permitted results. Read or Full access required. */
    public JsonNode announcementDetails(String id) {
        return request("/announcements/" + resourceId(id) + "", null, "GET", JsonNode.class);
    }

    /** List accessible recurring community tasks. Read or Full access required. */
    public JsonNode listScheduledTasks(String status, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var query = new java.util.StringJoiner("&");
        queryValue(query, "status", status);
        queryValue(query, "limit", limit);
        queryValue(query, "cursor", cursor);
        return request("/scheduled-tasks" + (query.length() == 0 ? "" : "?" + query), null, "GET", JsonNode.class);
    }

    /** Update an owned campaign using its current version. Write, Read or Full access and Work profile required. */
    public JsonNode updateCampaign(String id, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/campaigns/" + resourceId(id) + "", body, "PUT", JsonNode.class);
    }

    /** Publish, unpublish, trash or restore an owned campaign. Write, Read or Full access required; confirm destructive actions with the user. */
    public JsonNode campaignAction(String id, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/campaigns/" + resourceId(id) + "/action", body, "POST", JsonNode.class);
    }

    /** Create or update an accessible community case. Supply id and version when updating. Read or Full access required. */
    public JsonNode saveCase(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/cases", body, "POST", JsonNode.class);
    }

    /** Apply an explicitly requested case action, membership change, offer or board task. Existing role, version, money and policy checks apply. Read or Full access required. */
    public JsonNode caseAction(String id, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/cases/" + resourceId(id) + "/action", body, "POST", JsonNode.class);
    }

    /** Create or update a community announcement or vote as an authorized administrator. Read or Full access required. */
    public JsonNode saveAnnouncement(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/announcements", body, "POST", JsonNode.class);
    }

    /** Publish, manage or explicitly cast a ballot on an accessible announcement. Verify canVote and the user’s choice before voting; voting may be irreversible. Read or Full access required. */
    public JsonNode announcementAction(String id, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/announcements/" + resourceId(id) + "/action", body, "POST", JsonNode.class);
    }

    /** Create or update an authorized recurring community task. Read or Full access required. */
    public JsonNode saveScheduledTask(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/scheduled-tasks", body, "POST", JsonNode.class);
    }

    /** Pause, resume, trash or restore an authorized scheduled task using its current version. Read or Full access required. */
    public JsonNode scheduledTaskAction(String id, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/scheduled-tasks/" + resourceId(id) + "/action", body, "POST", JsonNode.class);
    }

    /** Publish, unpublish, trash or restore an owned service offering using its current version. Read or Full access required. */
    public JsonNode serviceAction(String id, JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A request object is required");
        return request("/services/" + resourceId(id) + "/action", body, "POST", JsonNode.class);
    }

    private static String resourceId(String id) {
        if (id == null || id.isBlank() || id.equals(".") || id.equals("..") || id.contains("/") || id.contains("\\"))
            throw new IllegalArgumentException("A resource ID is required");
        return java.net.URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static void queryValue(java.util.StringJoiner query, String key, Object value) {
        if (value != null) query.add(key + "=" + java.net.URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
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
            List<IntegrationGroupScope> inviteGroups, String accessMode) {
        public ConnectResponse(boolean connected, int maxBatchSize) {
            this(connected, maxBatchSize, null, null, null, null, List.of(), List.of(), "write");
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

    public record CampaignItem(String id, String title, String description, String kind, String category,
            Integer capacity, List<String> languages, Double maxDistanceKm) { }

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
