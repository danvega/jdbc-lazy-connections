package dev.danvega.lazyjdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

/**
 * The "before Spring Boot 4.1" way to get lazy JDBC connections.
 *
 * <p>Before the {@code spring.datasource.connection-fetch} property existed, the only way to get
 * lazy connections was to take over DataSource creation yourself and wrap Boot's pool in a
 * {@link LazyConnectionDataSourceProxy} by hand. It works, but you have to know the class exists,
 * you take DataSource creation away from Boot's auto-config, and you give up some of the niceties
 * of letting Boot build the pool for you.
 *
 * <p>This config is here purely to show the old approach. It is <strong>disabled by default</strong>
 * so it doesn't interfere with the property-based demo. To run the app the old way instead, set
 * {@code demo.legacy-lazy=true}.
 *
 * <p>When this bean is active, defining a {@code DataSource} bean makes Boot's
 * {@code DataSourceAutoConfiguration} back off, so the {@code spring.datasource.connection-fetch}
 * property no longer has any effect — the wrapping done here is what makes connections lazy.
 * That's the whole point of the comparison: same behavior, but in 4.1 it's one property instead
 * of this class.
 *
 * <p><strong>Heads up — this is exactly the "you lose Boot's niceties" caveat in action.</strong>
 * This demo gets its database from Docker Compose, and Compose support feeds the
 * <em>auto-configured</em> DataSource through a {@code JdbcConnectionDetails} bean, not through
 * {@code spring.datasource.url}. Because this hand-rolled bean reads from {@link DataSourceProperties}
 * (i.e. {@code spring.datasource.*}), it never sees those Compose-provided details and fails with
 * "url attribute is not specified". To run the old way against this project you have to supply the
 * coordinates yourself, e.g.:
 *
 * <pre>{@code
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="\
 *   --demo.legacy-lazy=true \
 *   --spring.datasource.url=jdbc:postgresql://localhost:5432/demo \
 *   --spring.datasource.username=demo \
 *   --spring.datasource.password=demo"
 * }</pre>
 *
 * <p>(If you wanted the hand-rolled bean to cooperate with Compose, you'd inject
 * {@code JdbcConnectionDetails} instead of {@code DataSourceProperties} and copy the url/username/
 * password/driver across by hand — more code for the same result the 4.1 property gives you for free.)
 */
@Configuration
@ConditionalOnProperty(name = "demo.legacy-lazy", havingValue = "true")
class DataSourceConfig {

    @Bean
    DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource target = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new LazyConnectionDataSourceProxy(target);
    }
}
