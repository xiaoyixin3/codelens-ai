package ai.codelens.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RepositoryProxyStartupTest {
    @Test
    void reuseDecisionRepositorySupportsTheProductionExceptionTranslationProxy() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("api");
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class);
            context.registerBean(DataSource.class, () -> mock(DataSource.class));
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ReuseDecisionStore.class);
            context.refresh();
            assertTrue(AopUtils.isCglibProxy(context.getBean(ReuseDecisionStore.class)));
        }
    }
}
