# Spring Boot 4.1 Lazy JDBC Connections Demo

A database transaction lives on one physical connection. The `BEGIN`, the `COMMIT`, and the isolation level all belong to a single connection from the pool, so every query in the transaction runs on that same one. Spring grabs a connection the moment a transaction opens and holds it until commit.

The catch is that Spring grabs it before you run any query because it can't know what the method will do. Usually that's fine. But plenty of transactional methods do slow, non-database work first: call a partner API, wait on a pricing service, verify a token, *then* write one row. Under load that connection sits idle for the whole remote call while it could have been serving another request.

The feature comes down to one gap. "Transaction opened" and "first query runs" are two different moments. By default Spring acts on the first when it only needs the second:

```
eager:  [acquire] -> remote call (~2s, connection idle) -> save -> commit -> [release]
lazy:               remote call (~2s, no connection)    -> [acquire] save -> commit -> [release]
```

Spring Boot 4.1 adds one property:

```properties
spring.datasource.connection-fetch=lazy
```

Now a connection isn't pulled from the pool until a statement runs. We'll prove it with metrics.

## How we did this before

This isn't new. The class behind it, `LazyConnectionDataSourceProxy`, has been in Spring Framework for years. Before 4.1 you wrapped your DataSource by hand:

```java
@Configuration
class DataSourceConfig {

    @Bean
    DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource target = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new LazyConnectionDataSourceProxy(target);
    }
}
```

It works, but you had to know the class existed, and you took DataSource creation away from Boot's autoconfiguration. The behavior in 4.1 is the same. The difference is that an obscure bean became a single property, and Boot keeps building the pool for you. (This project ships that old approach in `DataSourceConfig`, disabled behind `demo.legacy-lazy=true`, just to show the contrast.)

## What we're building

One Spring Boot app and one endpoint: `/register/{userId}`. It models the shape that actually bites people in production — a `@Transactional` method that does slow work *before* it touches the database:

```
GET /register/1 -> call a slow remote service (~2s) -> save one row
```

Postgres is started by Boot's Docker Compose support, Spring Data JDBC handles the single insert, and Actuator plus Micrometer expose HikariCP metrics. The plan: hit `/register` under `eager`, read how long the connection was held, flip the property to `lazy`, and watch that time collapse.

## Why Spring Data JDBC and not JPA

With JPA, Hibernate has its own connection modes and a session lifecycle that grabs connections at times that are hard to predict, so the metrics get noisy. Spring Data JDBC has none of that. A repository call maps straight to a JDBC statement, so "a connection is acquired when a statement runs" is literally true. The feature is clearest where the persistence layer is simplest.

## Before you start

Java 21+ (Boot 4 requires it), Maven, and Docker running. No local database to install; Compose handles it.

## Step 0: Generate the project

On [start.spring.io](https://start.spring.io), add **Spring Web**, **Spring Data JDBC**, **PostgreSQL Driver**, **Docker Compose Support**, and **Spring Boot Actuator**. Java 21+, Maven.

> Spring Web serves the REST endpoint we hit with `curl`. The other dependencies come straight from the feature.

Docker Compose Support saves you the setup. Boot finds a `compose.yaml` at startup, starts the container, and wires the datasource to it. No connection URLs by hand.

## Step 1: Metrics come with Actuator

No extra dependency needed. The Actuator starter pulls in Micrometer (`micrometer-core`), and when no other registry is on the classpath Boot auto-configures an in-memory `SimpleMeterRegistry`. With a registry present, HikariCP's metrics — including `hikaricp.connections.usage` — bind automatically and show up under `/actuator/metrics`. That's all this demo needs, since it just curls one metric and reads the value.

In production, you'd usually add a real registry — `micrometer-registry-prometheus` for a scrape endpoint, or the OpenTelemetry starter for OTLP export — so the same `hikaricp.connections.usage` reaches your backend. But that's an *export* concern. The metric exists either way.

## Step 2: Let Compose bring up Postgres

You should already have a `compose.yaml` in the project root. If not:

```yaml
services:
  postgres:
    image: 'postgres:17'
    environment:
      POSTGRES_DB: 'demo'
      POSTGRES_USER: 'demo'
      POSTGRES_PASSWORD: 'demo'
    ports:
      - '5432'
```

No datasource URL in your config. Boot reads the Compose file, starts the container, and points the datasource at it.

## Step 3: A trivial schema and repository

Keep the model boring. The feature is about connections, not domain design.

`src/main/resources/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS customer (
    id   SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);
```

In `application.yaml`, tell Boot to run it:

```yaml
spring:
  sql:
    init:
      mode: always
```

The record and repository:

```java
package dev.danvega.lazyjdbc;

import org.springframework.data.annotation.Id;

public record Customer(@Id Long id, String name) {}
```

```java
package dev.danvega.lazyjdbc;

import org.springframework.data.repository.CrudRepository;

public interface CustomerRepository extends CrudRepository<Customer, Long> {}
```

## Step 4: A slow call before the save

This is the whole demo. A `@Transactional` service that calls a slow remote service, then saves one row. The transaction opens before the remote call, but the only statement runs at the very end.

```java
package dev.danvega.lazyjdbc;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationService {

    private final SlowRemoteClient remoteClient;
    private final CustomerRepository repository;

    RegistrationService(SlowRemoteClient remoteClient, CustomerRepository repository) {
        this.remoteClient = remoteClient;
        this.repository = repository;
    }

    @Transactional
    public Customer register(long userId) {
        // Slow external call. Touches no database. No connection held under lazy.
        String name = remoteClient.fetchName(userId);

        // First (and only) statement. Lazy borrows the connection here.
        return repository.save(new Customer(null, name));
    }
}
```

`SlowRemoteClient` calls JSONPlaceholder and then sleeps a couple of seconds to stand in for a slow downstream. None of it touches the database:

```java
String fetchName(long userId) {
    User user = restClient.get().uri("/users/{id}", userId).retrieve().body(User.class);
    sleep(DELAY_MS); // pretend the downstream is slow
    return user != null ? user.name() : "Unknown user " + userId;
}
```

And a thin controller that delegates to the service so the transaction lives on the service:

```java
@RestController
class RegistrationController {

    private final RegistrationService registrationService;

    RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/register/{userId}")
    Customer register(@PathVariable long userId) {
        return registrationService.register(userId);
    }
}
```

## Step 5: See the waste (eager)

Expose the metric and set the default explicitly so the demo reads clearly. In `application.yaml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics
spring:
  datasource:
    connection-fetch: eager
```

This method runs exactly one statement, so it borrows a connection exactly once under both eager and lazy. What changes isn't *how often* a connection is borrowed, it's *how long* it's held. The metric for that is `hikaricp.connections.usage`, the time between borrow and return.

Start the app and hit the endpoint once:

```shell
curl -s localhost:8080/register/1 > /dev/null
curl -s localhost:8080/actuator/metrics/hikaricp.connections.usage | jq '.measurements'
```

The measurements come back in seconds (Micrometer's base time unit), so the `MAX` reads around `2.2` — the connection was held for the whole ~2-second remote call:

```json
[
  { "statistic": "COUNT",      "value": 1.0 },
  { "statistic": "TOTAL_TIME", "value": 2.228 },
  { "statistic": "MAX",        "value": 2.228 }
]
```

Under eager the connection was borrowed the instant the transaction opened and held across the entire remote call. (Read it right after the request; Micrometer's max decays over a couple of minutes.)

## Step 6: Flip to lazy

One change:

```yaml
spring:
  datasource:
    connection-fetch: lazy
```

Restart and run the same request:

```shell
curl -s localhost:8080/register/1 > /dev/null
curl -s localhost:8080/actuator/metrics/hikaricp.connections.usage | jq '.measurements'
```

Now the `MAX` drops to around `0.015` — about 15ms. The connection was borrowed only for the save. Same request, same one query, but eager pinned a connection for ~2 seconds and lazy pinned it for a few milliseconds. That difference is the feature.

## See it under load (optional)

Shrink the pool to one connection:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 1
```

Then fire a few `/register` calls at once:

```shell
for i in 1 2 3; do curl -s localhost:8080/register/$i > /dev/null & done; wait
```

Under eager they serialize, because each holds the single connection through its whole remote call, so `hikaricp.connections.pending` climbs and total time is roughly the number of requests times the delay. Under lazy they overlap, because the connection is free during every remote call, and they all finish in about 2s.

## When this actually helps

Don't flip this on everywhere expecting a free win. It pays off in specific shapes: transactional methods that do slow remote work before a write, mixed workloads where many paths return before touching the database, and services where most reads come from a cache.

If every transactional method queries right away, lazy adds a thin proxy and buys you almost nothing. It's a tool for a problem, not a default.

## Running this demo

```shell
# 1. Make sure Docker is running, then start the app (Compose brings up Postgres for you)
./mvnw spring-boot:run

# 2. In another terminal, register a user and read how long the connection was held
curl -s localhost:8080/register/1 > /dev/null
curl -s localhost:8080/actuator/metrics/hikaricp.connections.usage | jq '.measurements'

# 3. Stop the app, set spring.datasource.connection-fetch=lazy in application.yaml, restart, repeat step 2
```

This project ships with `connection-fetch: eager` so you can walk Step 5 into Step 6 in order. Flip it to `lazy` in `src/main/resources/application.yaml` when you're ready to see the held time drop.

## Links

- [Spring Boot 4.1 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [LazyConnectionDataSourceProxy javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/LazyConnectionDataSourceProxy.html)
- [Spring Boot Docker Compose support](https://docs.spring.io/spring-boot/reference/features/dev-services.html)
- [Spring Data JDBC](https://docs.spring.io/spring-data/relational/reference/)
```
