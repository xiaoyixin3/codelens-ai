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
        SpringApplication application = new SpringApplication(CodeLensApplication.class);
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
