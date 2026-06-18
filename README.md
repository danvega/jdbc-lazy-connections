# Spring Boot 4.1 Lazy JDBC Connections Demo

A database transaction lives on one physical connection. The `BEGIN`, the `COMMIT`, and the isolation level all belong to a single connection from the pool, so every query in the transaction runs on that same one. Spring grabs a connection the moment a transaction opens and holds it for the whole method.

The catch is that Spring grabs it before you run any query, because it can't know whether you'll run ten queries or none. Usually that's fine. But some methods open a transaction and return early: a cache hit, a failed validation, a permission check that bails out. They take a connection, do no database work, and hand it back. Under load, that's a connection another request could have used.

The feature comes down to one gap. "Transaction opened" and "first query runs" are two different moments. By default Spring acts on the first when it only needs the second:

```
eager:  [acquire connection] -> begin tx -> (no query) -> commit -> [release]
lazy:   begin tx -> (no query) -> commit          (connection never acquired)
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

It works, but you had to know the class existed, and you took DataSource creation away from Boot's autoconfiguration. The behavior in 4.1 is the same. The difference is that an obscure bean became a single property, and Boot keeps building the pool for you.

## What we're building

One Spring Boot app: Postgres started by Boot's Docker Compose support, Spring Data JDBC for a trivial repository, and Actuator plus Micrometer exposing HikariCP metrics. Two endpoints, one that queries the database and one that doesn't.

The plan: hit the endpoint that does no database work a hundred times, read the connection acquire count, flip the property, and watch the count drop.

## Why Spring Data JDBC and not JPA

With JPA, Hibernate has its own connection modes and a session lifecycle that grabs connections at times that are hard to predict, so the metrics get noisy. Spring Data JDBC has none of that. A repository call maps straight to a JDBC statement, so "a connection is acquired when a statement runs" is literally true. The feature is clearest where the persistence layer is simplest.

## Before you start

Java 21+ (Boot 4 requires it), Maven, and Docker running. No local database to install; Compose handles it.

## Step 0: Generate the project

On [start.spring.io](https://start.spring.io), add **Spring Web**, **Spring Data JDBC**, **PostgreSQL Driver**, **Docker Compose Support**, and **Spring Boot Actuator**. Java 21+, Maven.

> Spring Web serves the two REST endpoints we hit with `curl`. The other dependencies come straight from the feature.

Docker Compose Support saves you the setup. Boot finds a `compose.yaml` at startup, starts the container, and wires the datasource to it. No connection URLs by hand.

## Step 1: Add a Micrometer registry

Actuator exposes the metrics endpoint, but Hikari metrics only bind when a Micrometer registry is on the classpath. Add one:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Prometheus is the usual pick even if you never run Prometheus. With Actuator and a registry present, Hikari metrics bind automatically.

Why not `spring-boot-starter-opentelemetry`? It exports signals over OTLP to a backend like Grafana, which is great in production but overkill here. This demo curls one metric and watches it drop from ~100 to ~0, so a local registry is easier to read. It's the same metric either way. If your app already uses the OpenTelemetry starter, `hikaricp.connections.acquire` flows to your OTLP backend and you get the same view in production.

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
      - '5432:5432'
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

## Step 4: Two endpoints that tell the story

Two methods, both `@Transactional`. One queries the database, one doesn't.

```java
package dev.danvega.lazyjdbc;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {

    private final CustomerRepository repository;

    CustomerController(CustomerRepository repository) {
        this.repository = repository;
    }

    // Opens a transaction, runs a real query. The control case.
    @GetMapping("/with-db")
    @Transactional
    public long withDb() {
        return repository.count();
    }

    // Opens a transaction, returns without touching the database.
    // Stands in for a cache hit or an early return.
    @GetMapping("/no-db")
    @Transactional
    public String noDb() {
        return "did no database work";
    }
}
```

`/no-db` is the point. It's transactional, so by default it grabs a connection the moment it starts and gives it back having done nothing. That's the waste we're removing.

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

Start the app and hit `/no-db` a hundred times:

```shell
for i in $(seq 1 100); do curl -s localhost:8080/no-db > /dev/null; done
```

Then read how many times a connection was actually borrowed:

```shell
curl -s localhost:8080/actuator/metrics/hikaricp.connections.acquire | jq '.measurements'
```

`hikaricp.connections.acquire` is a timer, so it carries a `COUNT`. After 100 calls it's around 100. Every request grabbed a connection it never used. Note the number.

## Step 6: Flip to lazy

One change:

```yaml
spring:
  datasource:
    connection-fetch: lazy
```

Restart and run the same hundred requests:

```shell
for i in $(seq 1 100); do curl -s localhost:8080/no-db > /dev/null; done
curl -s localhost:8080/actuator/metrics/hikaricp.connections.acquire | jq '.measurements'
```

The count barely moves. No statement ran, so no connection left the pool. That difference is the feature.

## Step 7: Prove the normal path still works

Lazy shouldn't penalize real work. With `lazy` still set, hit the querying endpoint:

```shell
for i in $(seq 1 100); do curl -s localhost:8080/with-db > /dev/null; done
curl -s localhost:8080/actuator/metrics/hikaricp.connections.acquire | jq '.measurements'
```

Now the count climbs, because each request runs a real statement and needs a connection. Lazy didn't take anything away. It just stopped handing connections to code that wasn't going to use them.

## A real example: a slow call before the save

The `/no-db` endpoint is easy to measure: zero queries means zero acquires, so the count drops from ~100 to ~0. But the shape that bites people in production is different. A transactional method that does slow work before it touches the database. Call a partner API, wait on a pricing service, verify a token, then write one row.

```java
@Service
class RegistrationService {

    @Transactional
    public Customer register(long userId) {
        // Slow external call. Touches no database.
        String name = remoteClient.fetchName(userId);

        // First (and only) statement. The connection is borrowed here.
        return repository.save(new Customer(null, name));
    }
}
```

`SlowRemoteClient` calls JSONPlaceholder and then sleeps a couple of seconds to stand in for a slow downstream:

```java
String fetchName(long userId) {
    User user = restClient.get().uri("/users/{id}", userId).retrieve().body(User.class);
    sleep(DELAY_MS); // pretend the downstream is slow
    return user.name();
}
```

Here's the trap. Under eager, the connection is borrowed the instant the transaction opens and held for the entire remote call. That's two seconds of a pooled connection sitting idle while you wait on the network, then a save that takes a fraction of a millisecond:

```
eager: [acquire] -> remote call (~2s, connection idle) -> save -> commit -> [release]
lazy:               remote call (~2s, no connection)   -> [acquire] save -> commit -> [release]
```

### Why the acquire count won't tell this story

This method runs exactly one statement, so it borrows a connection exactly once, under both eager and lazy. The Step 5 metric won't move. What changes isn't how often a connection is borrowed, it's how long it's held. The metric for that is `hikaricp.connections.usage`, the time between borrow and return:

```shell
# With connection-fetch: eager
curl -s localhost:8080/register/1 > /dev/null
curl -s localhost:8080/actuator/metrics/hikaricp.connections.usage | jq '.measurements'
```

The `MAX` is about 2000ms. The connection was held across the whole remote call. (Read it right after the request; Micrometer's max decays over a couple of minutes.)

```shell
# Flip to connection-fetch: lazy, restart, then:
curl -s localhost:8080/register/1 > /dev/null
curl -s localhost:8080/actuator/metrics/hikaricp.connections.usage | jq '.measurements'
```

Now the `MAX` is a few milliseconds. The connection was borrowed only for the save. Same request, same one query, but eager pinned a connection for two seconds and lazy pinned it for two milliseconds.

### See it under load (optional)

Shrink the pool to one connection (see the dramatic version below) and fire a few `/register` calls at once. Under eager they serialize, because each holds the single connection through its whole remote call. So `hikaricp.connections.pending` climbs and total time is roughly the number of requests times the delay. Under lazy they overlap, because the connection is free during every remote call, and they all finish in about 2s.

## When this actually helps

Don't flip this on everywhere expecting a free win. It pays off in specific shapes: mixed workloads where many paths return before touching the database, services where most reads come from a cache, and apps with broad `@Transactional` boundaries where plenty of calls never query.

If every transactional method queries anyway, lazy adds a thin proxy and buys you almost nothing. It's a tool for a problem, not a default.

## A more dramatic version (optional)

Want a bigger effect? Shrink the pool to one connection:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 1
```

Add a short sleep to `/no-db`, then fire two requests at it at once. Under eager, the second one blocks waiting for the single connection the first is holding but not using, and `hikaricp.connections.pending` climbs above zero. Under lazy, both go through. Same lesson, easier to see.

## Running this demo

```shell
# 1. Make sure Docker is running, then start the app (Compose brings up Postgres for you)
./mvnw spring-boot:run

# 2. In another terminal, hammer the no-db endpoint and read the metric
for i in $(seq 1 100); do curl -s localhost:8080/no-db > /dev/null; done
curl -s localhost:8080/actuator/metrics/hikaricp.connections.acquire | jq '.measurements'

# 3. Stop the app, set spring.datasource.connection-fetch=lazy in application.yaml, restart, repeat step 2
```

This project ships with `connection-fetch: eager` so you can walk Step 5 into Step 6 in order. Flip it to `lazy` in `src/main/resources/application.yaml` when you're ready to see the count drop.

## Links

- [Spring Boot 4.1 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [LazyConnectionDataSourceProxy javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/LazyConnectionDataSourceProxy.html)
- [Spring Boot Docker Compose support](https://docs.spring.io/spring-boot/reference/features/dev-services.html)
- [Spring Data JDBC](https://docs.spring.io/spring-data/relational/reference/)
