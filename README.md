<div align="center">

# 🔑 Idempotency Key Pattern

### Spring Boot idempotency with the Idempotency-Key header: every ride charged once, however often the client retries

[![CI](https://github.com/dmitriy-iliyov/spring-idempotency/actions/workflows/ci.yml/badge.svg)](https://github.com/dmitriy-iliyov/spring-idempotency/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/dmitriy-iliyov/spring-idempotency/graph/badge.svg)](https://codecov.io/gh/dmitriy-iliyov/spring-idempotency)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-idempotency%20store-336791?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-response%20cache-DC382D?logo=redis)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-at--least--once-black?logo=apachekafka)](https://kafka.apache.org/)
[![Built on idempify](https://img.shields.io/badge/built%20on-idempify%200.1.0-6f42c1)](https://github.com/dmitriy-iliyov/idempify)
[![Built on oncebox](https://img.shields.io/badge/built%20on-oncebox%202.0.0-6f42c1)](https://github.com/dmitriy-iliyov/oncebox)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

A rider taps "Book" on a train with a weak signal. The request reaches the server, the ride is booked and the
card is charged, but the answer is lost in a tunnel. The app does what any app does without an answer: it sends
the request again. Without protection the second request books a second ride and charges the card a second time,
and the rider pays twice for one trip.

This repository is a ride-booking service that charges through Stripe and cannot be made to charge twice. Every
booking carries an **Idempotency-Key**: the first request under a key books and charges, and every repeat under
the same key gets the first answer back, status and body as they were, without booking or charging again. A
repeat with a different body under the same key is refused, and a repeat that arrives while the first request is
still running is told so instead of running beside it.

> 🧩 **Built on [idempify](https://github.com/dmitriy-iliyov/idempify) `0.1.0`** - idempify claims a key
> atomically in PostgreSQL, keeps the answer of the operation on the claim's row, replays it to every repeat, and
> refuses a repeat with another body. ride-service puts `@Idempotent` on its booking endpoint, keeps the
> recorded answers in Redis in front of the store and counts every outcome in Micrometer. Configuration
> reference: [idempify documentation](https://github.com/dmitriy-iliyov/idempify#readme). Receipts travel through
> the [oncebox](https://github.com/dmitriy-iliyov/oncebox) `2.0.0` outbox and inbox.

## 📑 Contents

- [Highlights](#-highlights)
- [Why idempotency keys?](#-why-idempotency-keys)
- [Architecture](#%EF%B8%8F-architecture)
- [How it works](#-how-it-works)
- [Guarantees](#%EF%B8%8F-guarantees)
- [When a booking fails](#-when-a-booking-fails)
- [Keeping the tables small](#-keeping-the-tables-small)
- [Quick start](#-quick-start)
- [See it at work](#-see-it-at-work)
- [Tests](#-tests)
- [Modules](#-modules)
- [Contributing](#-contributing)

## ✨ Highlights

- **One charge per key.** A ride is booked and charged once per Idempotency-Key, under concurrent duplicates,
  retries after a lost answer and repeats after a crash in the middle of a charge.
- **The first answer, replayed.** A repeat gets the recorded status, body and headers of the first answer, served
  from Redis in front of the record when it is there and from PostgreSQL when it is not.
- **Changed body, refused.** A key reused with another route is answered `422`, not with somebody else's ride.
- **Safe across the Stripe boundary.** The charge cannot be rolled back, so it runs outside any transaction; the
  ride is found by its key on a repeat, and Stripe receives the same key, so neither side does its work twice.
- **Failures told apart.** A declined card is final and replayed to every repeat; a lost connection to Stripe
  frees the key for a retry; a failure Stripe would repeat to every retry is final as well.
- **One receipt per ride.** The receipt job is staged in the transaction of the charge through the
  transactional outbox of [oncebox](https://github.com/dmitriy-iliyov/oncebox) and consumed through its
  inbox, so a redelivered job never sends a second receipt.
- **Problem details everywhere.** Every refusal is an RFC 9457 problem with a stable `type` URI.
- **Observable.** Structured JSON logs with a correlation id per request, Prometheus metrics of every
  idempotent outcome and of the response cache, Grafana dashboards.

## 🤔 Why idempotency keys?

A network call has three outcomes, not two: it worked, it failed, or nobody knows. A client that hears nothing
cannot tell "the server never got it" from "the server did it and the answer was lost", and the only way out is
to send the request again. Every write that costs money or cannot be undone has to survive that repeat.

| Approach | ✅ Pros | ⚠️ Cons |
|----------|---------|---------|
| **No retries** | ✅ Nothing is ever done twice | ❌ A lost answer is a lost booking; the client has to ask a human what happened |
| **Retries without a key** | ✅ The client recovers from lost answers | ❌ Every retry of a request that did go through books and charges again |
| **A unique constraint on the business data** | ✅ No duplicate rows | ❌ Needs a natural key the request may not have; a repeat gets an error instead of its answer; does nothing for the charge already made |
| **Idempotency-Key with a recorded answer** *(this project)* | ✅ Retries are safe by construction<br>✅ A repeat gets the original answer<br>✅ A reused key with another body is caught | ⚠️ A store of keys and answers to keep and clean up; the client has to send a new key per operation and the same key per retry |

The key wins wherever a client retries a write that is not naturally idempotent: payments, bookings, orders,
anything that sends money or messages to a third party. It is the scheme Stripe, Adyen and PayPal use on their
own APIs, and the one this service uses on its booking endpoint.

## 🏗️ Architecture

![Architecture: ride-service charges through Stripe once per key and stages receipt jobs; receipt-service consumes them once](docs/images/architecture.png)

| Service | Listens to | Does | Built on |
|---------|------------|------|----------|
| `ride-service` | HTTP `POST /api/v1/rides` | Books a ride, charges it through Stripe, stages the receipt job | idempify (`@Idempotent`, PostgreSQL store, Redis cache, metrics), oncebox outbox |
| `receipt-service` | Kafka `ride-receipts` | Keeps and sends the receipt of a charged ride, once per ride | oncebox inbox |
| `ride-simulator` | - | Books rides the way a mobile client on a bad network does | - |

PostgreSQL holds a database per service, Redis the recorded answers, Kafka the receipt jobs, and
[stripe-mock](https://github.com/stripe/stripe-mock) stands in for Stripe locally and in tests.

## 🔄 How it works

### Booking a ride - `ride-service`

A booking is `POST /api/v1/rides` with an `Authorization` header naming the rider and an `Idempotency-Key`
header holding a UUID. A filter resolves the rider first, so an unknown caller is answered `401` before any
recorded answer can be replayed to it. idempify then reads the key, fingerprints the request (path, method and
the JSON body with its keys sorted) and claims the key in PostgreSQL with one atomic statement. If the key
already has an answer and the fingerprint matches, that answer goes back as it was, without the handler being
called; if the fingerprint differs, the request is refused `422`; if another request still holds the claim, it
is refused `409`.

The booking itself runs in three steps. The ride and its audit record are stored in one transaction. Stripe is
then charged outside any transaction - a charge cannot be rolled back - under the request's own Idempotency-Key,
with a 5 s connect timeout, a 20 s read timeout and two network retries. Finally the charge is recorded on the
ride, and in the same transaction the receipt job is staged in the outbox. The fare is a fixed 2000 cents in USD
(`rides.fare`).

The claim is a lease of 2 minutes, longer than the slowest booking the configuration allows, and the service
refuses to start if the Stripe and connection timeouts could add up to more. If the lease runs out anyway, a
repeat takes the key over and runs the booking again, and every step is safe to run again: the ride is found by
its key, Stripe answers the same key with the same charge, and a charge already recorded is not recorded twice.
A completed answer stays replayable for 24 hours.

## 🛡️ Guarantees

| Hop | Guarantee | How |
|-----|-----------|-----|
| Client → `ride-service` | **effectively-once** per key | The booking may run again after a lost lease; its effects do not: ride found by key, Stripe idempotency key, charge recorded once |
| Repeat → answer | **the first answer, replayed** for 24 h | The answer is recorded on the claim's row, cached in Redis until the row expires |
| `ride-service` → Stripe | **at most one charge** per key | Stripe deduplicates by the forwarded Idempotency-Key; an uncharged ride older than 23 h is never charged again |
| Charge → receipt job | **atomic** | The job is staged in the transaction that records the charge |
| Outbox → Kafka | **at-least-once** | oncebox polls the outbox and marks a job sent only after Kafka acknowledged it from every in-sync replica |
| Kafka → `receipt-service` | **effectively-once** | The inbox records the event id in the transaction of the receipt, and a ride has at most one receipt |

Receipts carry no ordering guarantee across rides; each ride has exactly one.

## 🚦 When a booking fails

| What happened | Answer | What a repeat under the same key gets |
|---------------|--------|----------------------------------------|
| Missing or malformed `Idempotency-Key` | `400` `invalid-idempotency-key` | Nothing was claimed; send a valid key |
| Body that is not JSON, or fails validation | `400`, with the invalid fields | The key stays free; fix the body and send it again |
| Unknown rider | `401` `unauthorized` | The same, before any replay |
| Another request under the key is still running | `409` `operation-in-process` | Retry after a moment; it gets the first request's answer |
| Same key, different body | `422` `idempotency-key-reuse` | The same refusal; a new operation needs a new key |
| Card declined | `402` `payment-declined` | The same `402`, replayed; no second charge attempt |
| Stripe failed the charge for good | `502` `payment-failed` | The same `502`, replayed |
| Stripe unreachable, rate limited or still busy with the key | `503` `payment-provider-unavailable` | A new attempt, charged under the same Stripe key |
| A ride left uncharged for more than 23 hours | `409` `booking-expired` | The same; book again under a new key |

On the receipt side a job that fails is retried three times, a second apart, and then moved to the
`ride-receipts-dlt` topic with nothing kept, so it can be inspected and replayed. On the ride side a job Kafka
does not take is retried by oncebox with backoff and, when its retries run out, moved to the outbox dead letter
table.

## 🧹 Keeping the tables small

| Table | Service | Kept for | Cleaned by |
|-------|---------|----------|------------|
| `idempotent_operations` | `ride-service` | 24 h after the operation completed | The reaper, every 5 minutes, 1000 rows a batch |
| `outbox_events` | `ride-service` | 24 h after the job was sent | oncebox |
| `inbox_events` | `receipt-service` | 24 h after the job was consumed | oncebox |

A record still in process has no expiry and is never deleted by the reaper. The rides themselves keep their key
for good, which is what lets a repeat that arrives after its record expired find its ride instead of booking a
new one.

## 🚀 Quick start

You need **Java 21** and **Docker**. The Maven wrapper brings Maven.

**1. Start the infrastructure and both services** (built from source):

```bash
docker compose --profile apps up -d --build --wait
```

| | URL |
|---|---|
| `ride-service` | http://localhost:8080 |
| `receipt-service` | http://localhost:8081 |
| Health | http://localhost:8080/actuator/health |

The `docker` profile seeds three riders: `local.user@example.com`, `second.user@example.com` and
`third.user@example.com`.

**2. Book a ride:**

```bash
curl -i -X POST http://localhost:8080/api/v1/rides \
  -H "Content-Type: application/json" \
  -H "Authorization: local.user@example.com" \
  -H "Idempotency-Key: 4b8e9d2a-6c1f-4e7b-9a3d-5f2c8e1b7a60" \
  -d '{"origin": {"lat": 52.5200066, "lon": 13.4049540}, "target": {"lat": 52.3675734, "lon": 4.9041389}}'
# HTTP/1.1 201
# {"id":1,"origin":{...},"target":{...},"amount":2000,"currency":"usd","stripeChargeId":"ch_RjJ9mqa93jq2L2l",...}
```

Latitudes run from -90 to 90 and longitudes from -180 to 180, with at most ten fractional digits; an optional
`client` object takes `appVersion` and `platform` of up to 32 characters each.

**3. Send it again:** the same command answers the same `201` with the same ride and the same charge, and nothing
new is booked.

**4. Reuse the key for another route:**

```bash
curl -i -X POST http://localhost:8080/api/v1/rides \
  -H "Content-Type: application/json" \
  -H "Authorization: local.user@example.com" \
  -H "Idempotency-Key: 4b8e9d2a-6c1f-4e7b-9a3d-5f2c8e1b7a60" \
  -d '{"origin": {"lat": 52.5200066, "lon": 13.4049540}, "target": {"lat": 48.8566140, "lon": 2.3522219}}'
```

Errors come back as RFC 9457 problem details, with a `type` a client can branch on:

```json
{
  "type": "https://idempify.io/errors/idempotency-key-reuse",
  "title": "Idempotency-Key reuse",
  "status": 422,
  "detail": "This Idempotency-Key was already used with a different request body.",
  "instance": "/api/v1/rides",
  "timestamp": "2026-11-19T02:14:06.535851728Z",
  "idempotencyKey": "4b8e9d2a-6c1f-4e7b-9a3d-5f2c8e1b7a60"
}
```

**5. Read the receipt:**

```bash
docker compose exec postgres psql -U receipts -d receipts -c "SELECT ride_id, stripe_charge_id, sent_at FROM receipts"
```

[`requests.http`](requests.http) holds the same requests for the IntelliJ HTTP client and VS Code's REST Client.

## 🔁 See it at work

The simulator books rides the way a phone on a bad network does: every booking gets a new key, roughly every
third one sends its request and hangs up before the answer, and the client retries it under the same key, backing
off, until the answer is definitive.

```bash
./mvnw -pl ride-simulator -am package -DskipTests
java -jar ride-simulator/target/ride-simulator-0.1.0-SNAPSHOT.jar
# Booking 7d2c... for second.user@example.com attempt 1 got no answer, retrying under the same key
# Booking 7d2c... for second.user@example.com answered 201 after 2 attempt(s): {"id":12,...}
```

Stop it after a while and count: there are as many rides as charges and as receipts, however many attempts the
log shows.

```bash
docker compose exec postgres psql -U rides -d rides -c "SELECT count(*) AS rides, count(DISTINCT stripe_charge_id) AS charges FROM rides"
docker compose exec postgres psql -U receipts -d receipts -c "SELECT count(*) AS receipts FROM receipts"
```

To watch it on dashboards, start the observability profile as well and open Grafana at http://localhost:3000
(`admin` / `admin`), Prometheus at http://localhost:9090:

```bash
docker compose --profile apps --profile observability up -d --build --wait
```

## 🧪 Tests

`./mvnw verify` runs every test against real PostgreSQL, Redis, Kafka and stripe-mock in Testcontainers (Docker
required) and keeps each core module at **80%+ line coverage** with JaCoCo. The end-to-end modules start the
services as they run in production - ride-service from its application class, receipt-service from its
executable jar - and drive them over HTTP.

- ✅ A first booking - one ride, one charge, one audit record, one receipt job
- ✅ A repeat under the same key - the first answer replayed byte for byte, from Redis or from PostgreSQL
- ✅ A reformatted body under the same key - recognised as the same request
- ✅ A changed body under the same key - `422`, nothing booked
- ✅ Ten concurrent duplicates, twenty times over - one charge, the rest refused as in flight or replayed
- ✅ A retry after the client hung up - one definitive answer for the key
- ✅ A declined card, a final Stripe failure, a Stripe outage - replayed, replayed, retried and charged once
- ✅ A repeat after the lease expired mid-charge - the charged ride, not a failure
- ✅ An unknown rider, with and without a recorded key - `401` before any replay
- ✅ A receipt job delivered twice, failing once, failing for good, unreadable - one receipt, redelivery, dead letter
- ✅ A booked ride end to end - its receipt in the receipt service's database
- ✅ The reaper - expired records deleted, live and in-process ones kept

`./mvnw -pl :load-tests gatling:test` runs the Gatling simulation against a running stack: ten bookings a second
for a minute, a fifth of them repeated under the same key and checked against their first answer.

## 📦 Modules

| Module | Purpose |
|--------|---------|
| `rides-contract` | The receipt job event and the shared log field names |
| `ride-service` | The booking API, Stripe, the idempotency store, the outbox, the reaper |
| `tests/ride-service-e2e-tests` | End-to-end tests of ride-service |
| `receipt-service` | Receipts, consumed through the inbox |
| `tests/receipt-service-e2e-tests` | End-to-end tests of a booking through to its receipt |
| `ride-simulator` | Bookings with lost answers and retries under the same key |
| `tests/load-tests` | Gatling simulation of bookings with repeated keys |

## 🤝 Contributing

Contributions are welcome - from a version bump to a new endpoint charged once per key. Issues labelled
[`good first issue`](https://github.com/dmitriy-iliyov/spring-idempotency/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
are self-contained and come with a clear definition of done. Start with [CONTRIBUTING.md](CONTRIBUTING.md).

## 📚 Learn more

- [idempify](https://github.com/dmitriy-iliyov/idempify) - idempotency keys for Spring Boot: atomic claims,
  recorded answers, fingerprints, conflict handling
- [oncebox](https://github.com/dmitriy-iliyov/oncebox) - transactional outbox and inbox for Spring Boot
- [spring-transactional-outbox](https://github.com/dmitriy-iliyov/spring-transactional-outbox) - orders and
  notifications delivered once through oncebox
- [Implementing Stripe-like Idempotency Keys in Postgres](https://brandur.org/idempotency-keys) - the design
  this kind of service descends from

## ⭐ Support

If you found this project interesting or useful, consider giving it a star. It helps other developers find
[idempify](https://github.com/dmitriy-iliyov/idempify) and keeps the project growing.

## 📄 License

[Apache License 2.0](LICENSE)
