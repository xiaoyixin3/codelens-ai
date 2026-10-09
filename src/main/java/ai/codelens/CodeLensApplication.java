package ai.codelens;

import ai.codelens.config.DotEnv;
import ai.codelens.config.RuntimeConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CodeLensApplication {
    public static void main(String[] args) {
        DotEnv.load(".env");
        String mode = System.getProperty("codelens.mode", DotEnv.get("CODELENS_MODE", "api")).trim().toLowerCase();
        if (mode.equals("publication-inspect")) {
            ai.codelens.review.PublicationInspectionRunner.runId(new org.springframework.boot.DefaultApplicationArguments(args));
        }
        SpringApplication application = new SpringApplication(CodeLensApplication.class);
        application.addInitializers(context -> {
            if (context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("publication-inspect"))) {
                ai.codelens.review.PublicationInspectionRunner.requireReadOnlyProfiles(context.getEnvironment().getActiveProfiles());
            }
        });
        if (!mode.equals("api")) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.setAdditionalProfiles(mode);
        application.run(args);
    }

    @Bean
    RuntimeConfig runtimeConfig() {
        return RuntimeConfig.fromEnvironment();
    }
}
