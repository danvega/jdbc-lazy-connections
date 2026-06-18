package dev.danvega.lazyjdbc;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// A slow downstream dependency. Fetches a user from JSONPlaceholder, then sleeps to make the
// slow response repeatable. None of it touches the database.
@Component
class SlowRemoteClient {

    private static final long DELAY_MS = 2000;

    private final RestClient restClient;

    SlowRemoteClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("https://jsonplaceholder.typicode.com").build();
    }

    String fetchName(long userId) {
        User user = restClient.get()
                .uri("/users/{id}", userId)
                .retrieve()
                .body(User.class);

        sleep(DELAY_MS); // pretend the downstream is slow

        return user != null ? user.name() : "Unknown user " + userId;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating a slow remote call", e);
        }
    }

    // Only the fields we care about; JSONPlaceholder returns more.
    record User(Long id, String name) {}
}
