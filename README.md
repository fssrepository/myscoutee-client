# MyScoutee Java client

Java 17 SDK and CLI for the small MyScoutee integration API.

The API is intentionally write-oriented. It accepts asset and event batches and returns the caller's `id` together with the stable MyScoutee resource ID as `externalId`. It also exposes owner-scoped event details and completed Mingle encounters.

## Client slot and authentication

Each MyScoutee user can create up to three active tokens. One token represents one external client slot.

The CLI creates a persistent UUID on first configuration and sends it as `X-MyScoutee-Client-Id`. The first successful connection atomically claims the token. A different client UUID cannot use that token afterward. Revoking the token from the MyScoutee profile blocks it immediately.

The complete token is shown only once in MyScoutee. Treat the CLI configuration file as a secret.

## Build

```bash
./gradlew installDist
```

The executable is created under `build/install/myscoutee-client/bin/`.

## Configure

Copy the base URL and a newly generated token from the API popup on the MyScoutee profile page:

```bash
build/install/myscoutee-client/bin/myscoutee-client \
  configure https://example.com/api/integrations/v1 'msc_your_token'
```

By default the CLI stores one token and its client UUID in `~/.myscoutee/client.properties`. Set `MYSCOUTEE_CONFIG` to use a different file.

## Endpoints

| Operation | Endpoint | Limit |
| --- | --- | ---: |
| Connect and claim token | `POST /connect` | one client UUID per token |
| Create assets | `POST /assets` | 1–1000 items per request |
| Create events | `POST /events` | 1–1000 items per request |
| Read event details (including `sourceLink`) | `GET /events/{externalId}` | managed events only |
| Read completed encounters | `GET /events/{externalId}/encounters?offset=0&limit=1000` | up to 1000 unique pairs per page |
| Create group invitation links | `POST /groups/{groupId}/invites` | 1–1000 distinct participant IDs |
| Create participant invitation links | `POST /events/{externalId}/invites` | 1–1000 distinct participant IDs |
| Register or disable callback | `POST /watch` | one callback per claimed token/client |

The watch endpoint is a callback registration, not a polling or general read
API. MyScoutee posts `event.changed` only for events managed by the token owner
and `asset.changed` only for assets owned by that user. A normal participant
Join also triggers `event.changed`. The callback contains the caller's `id`
when the resource originated through this API, the MyScoutee `externalId`, a
small resource snapshot, and for membership changes only the participant's
name, age, gender and first profile image. It never includes an e-mail address
or internal user id.

The 1000-item limit applies to one request only. Clients may send later batches and the three client slots may operate concurrently.

## Asset example

```json
{
  "items": [
    {
      "id": "7d857d7f-8d48-4cf1-95cc-cbf257640afa",
      "type": "Accommodation",
      "title": "Hotel room 101",
      "category": "Room",
      "city": "Bratislava",
      "capacityTotal": 2,
      "quantity": 1,
      "routes": ["Bratislava"],
      "visibility": "Private"
    }
  ]
}
```

```bash
myscoutee-client assets assets.json
```

## Event example

```json
{
  "items": [
    {
      "id": "1066b61b-c0ac-43d5-ab28-f588f5a162f6",
      "title": "Hotel evening",
      "subtitle": "Welcome event for hotel guests",
      "startAt": "2026-10-10T18:00:00Z",
      "endAt": "2026-10-10T20:00:00Z",
      "location": "Bratislava",
      "capacityMin": 1,
      "capacityMax": 30,
      "visibility": "Private",
      "status": "draft"
    }
  ]
}
```

```bash
myscoutee-client events events.json
```

Register both callback types (the default when the list is omitted):

```bash
myscoutee-client watch https://integration.example.test/hooks/myscoutee
```

Register only event callbacks, or disable the current client's callback:

```bash
myscoutee-client watch https://integration.example.test/hooks/myscoutee event.changed
myscoutee-client unwatch
```

Production callbacks must use HTTPS and resolve to a public address. Delivery
uses a durable queue and an elastic worker pool (zero idle workers, scaling to
at most 100 parallel deliveries), so callback HTTP traffic never runs on the
Join/request thread and independent client destinations can be served in
parallel. Every 2xx response is success, redirects are not followed, and
failures retry after approximately 5 seconds, 30 seconds, 2 minutes and 10
minutes. Receivers should deduplicate by `deliveryId` or the identical
`X-MyScoutee-Webhook-Id` header.

Both calls return one result per item:

```json
{
  "items": [
    {
      "id": "1066b61b-c0ac-43d5-ab28-f588f5a162f6",
      "externalId": "55fe8563-f360-48cf-af0e-7cfb0404ee84",
      "result": "created",
      "error": null
    }
  ]
}
```

Sending the same `id` again does not create or update another resource. It returns `existing` with the same `externalId`, so a timed-out POST can be retried safely.

## Error handling

Missing local configuration or a blank token stops the CLI before an HTTP request is sent. An invalid, expired, revoked or already claimed token produces an `API error (HTTP 401)` and includes the server response; API errors use process exit code `2`. Local configuration, file and JSON syntax errors use exit code `1`.

Batch field validation is item based so one invalid item does not discard valid items in the same request. The response keeps the caller's `id`, returns `externalId: null`, `result: "rejected"` and a machine-readable `error`, for example `id_must_be_uuid` or `title_required`. Because the batch itself was processed, this response uses HTTP 200 and the CLI exits normally; integrations must inspect every result row.

## Optional images

An image is attached to the same asset or event POST. The JSON stays in the `request` multipart part, and every image part is named with the caller-owned item `id`:

```bash
myscoutee-client assets assets.json \
  7d857d7f-8d48-4cf1-95cc-cbf257640afa=room.png
```

The client reads the local file and uploads its bytes. It never downloads an image URL. The server stores the original and creates 128 px, 640 px and 1280 px WebP variants. Omit the mapping to create an item without an image.

## Versioned test resources

The repository includes payloads that are also exercised by the automated client tests:

| File | Purpose |
| --- | --- |
| `src/test/resources/fixtures/assets.json` | Valid two-item asset batch and idempotent retry input |
| `src/test/resources/fixtures/events.json` | Valid two-item event batch and idempotent retry input |
| `src/test/resources/fixtures/sample-image.png.base64` | Tiny PNG fixture used by the multipart transport test |
| `src/test/resources/fixtures/duplicate-id.json` | Per-item rejection for a duplicate UUID in one batch |
| `src/test/resources/fixtures/assets-required-fields.json` | Asset form-required field rejection cases |
| `src/test/resources/fixtures/events-required-fields.json` | Event form-required field rejection cases |
| `src/test/resources/fixtures/watch.json` | Watch callback registration example |
| `src/test/resources/fixtures/qa-api-asset.json` | Single Asset used by the assembled-stack API checkpoint |
| `src/test/resources/fixtures/qa-api-event.json` | Single public Event used by the assembled-stack API checkpoint |
| `src/test/resources/fixtures/integration-api-qa.png` | Generated image uploaded with both API checkpoint resources |
| `src/test/resources/fixtures/invalid-id.json` | Non-UUID caller ID rejection case |
| `src/test/resources/fixtures/malformed.json` | Deliberately incomplete JSON syntax error case |

After configuration, the same files can be sent with the CLI:

```bash
myscoutee-client assets src/test/resources/fixtures/assets.json
myscoutee-client events src/test/resources/fixtures/events.json

myscoutee-client assets src/test/resources/fixtures/qa-api-asset.json \
  1f2f24d2-d14c-43ce-a27f-67270ae93d4a=src/test/resources/fixtures/integration-api-qa.png
myscoutee-client events src/test/resources/fixtures/qa-api-event.json \
  a3b891c2-1a45-4fe8-b8c2-c217c22466b4=src/test/resources/fixtures/integration-api-qa.png
```

## Event formats and organizer links

`sourceLink` is the external organizer's event-page URL, matching the asset link
contract. It is returned in event details and `event.changed` callbacks. It is
not a separate payment URL.

Event creation accepts `mode` (`Casual`, `Tournament`, `Mingle`; omitted means
`Casual`) and an optional `mingleConfiguration`. Configuration is valid only
for Mingle and, when supplied, must specify `groupSize` (2–20), `plannedRounds`
(1–100), `roundDurationMinutes` (1–240), `breakDurationMinutes` (0–60) and
`requireGenderBalance` (boolean). Selecting Mingle does not invent a configuration.

```java
var event = client.event(createdEvent.externalId());
var page = client.encounters(event.externalId(), 0, 1000);
```

Encounters require a completed Mingle session (otherwise HTTP 409). Each pair
appears once across all completed rounds, using their frozen actual table
memberships. Neither ratings nor friendship status affects the export.
Participant `id` is the organizer's external participant ID when linked;
`externalId` is an opaque, stable identifier scoped to that organizer. Internal
user IDs and contact details are not exported. Continue with `nextOffset` while
it is non-null, and keep pages from the same `revision` together.

Subscribe explicitly to `event.encounters` to receive the same result through
watch callbacks. Results are chunked into pages of up to 1000 pairs, with
`offset`, `nextOffset`, `total` and `revision`. The existing default watch event
list remains `event.changed` and `asset.changed`. Completion is persisted before
outbox creation; a restart retries pending publication without replacing or
resending an already queued delivery. Callback delivery itself retains the
existing delivery retry behavior. Register watch before completion; historical
sessions are queried through the encounters endpoint.


## Participant invitation links

```java
var invitations = client.inviteParticipants(event.externalId(), List.of("guest-001", "guest-002"));
```

The body is `{"participantIds":["guest-001","guest-002"]}`. The response is
`{"items":[{"id":"guest-001","inviteUrl":"https://example.com/game?partnerInvite=…","claimed":false},…]}`.
IDs are trimmed, nonblank, at most 200 characters and unique within the batch.
Retries for the same organizer, event and participant return the same link.
Keep each URL private and deliver it only to its intended participant.

Opening the URL preserves it through sign-in or registration. Claiming binds
that external participant ID to the authenticated account and creates the
normal pending event invitation. The participant still follows the app's
ordinary acceptance and payment flow. A claimed link cannot be transferred to
another account; an organizer's participant ID cannot identify two accounts.
Encounter exports use this ID after a successful claim. Participants who joined
without a partner link have `id: null` and an opaque organizer-scoped `externalId`.

```sh
myscoutee-client event <myscoutee-event-id>
myscoutee-client invites <myscoutee-event-id> guest-001 guest-002
myscoutee-client encounters <myscoutee-event-id> 0 1000
myscoutee-client watch https://integration.example.test/hooks/myscoutee event.changed,event.encounters
```

The app calls this format **Speed meeting** (**Villámtalálkozók** in Hungarian).
Its API enum remains `Mingle`, and its configuration field remains `mingleConfiguration`.
These additions are documented from the current source tree; they do not imply
that an earlier published client binary already contains the new commands.

### Group invitations

Use `client.inviteGroupParticipants(groupId, participantIds)` or
`myscoutee-client group-invites <group-id> guest-001 guest-002` for an existing
group administered by the token owner. Participant IDs are external identifiers,
not account IDs. Repeating a group/participant pair returns its existing claim link.
Claiming after sign-in creates a pending invitation. Membership approval creates
the group profile; a claim never grants workspace access by itself.
