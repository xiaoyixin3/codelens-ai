package ai.codelens.migration;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("api")
public class MigrationSchemaHealthIndicator implements HealthIndicator {
    private final MigrationSchemaVerifier verifier;

    public MigrationSchemaHealthIndicator(MigrationSchemaVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Health health() {
        MigrationSchemaVerifier.Status status = verifier.status();
        return status.ready() ? Health.up().withDetail("schema", "ready").build()
                : Health.down().withDetail("schema", status.reason()).build();
    }
}
