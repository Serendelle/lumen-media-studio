package com.houqing.tools.video_compressor_backend.db;

import com.houqing.tools.video_compressor_backend.config.ProductionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The prod profile refuses to start without its database and a real JWT secret, and says exactly what is missing. */
class ProductionGuardTest {
    private final ProductionGuard guard = new ProductionGuard();

    @Test
    void prodWithoutSettingsFailsWithEveryProblemNamed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("APP_JWT_SECRET", "change-me-to-a-long-random-string");
        env.setProperty("LUMEN_DB_URL", "jdbc:mysql://x/y");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
                .hasMessageContaining("LUMEN_DB_USER 未设置").hasMessageContaining("示例值").hasMessageContaining("jdbc:postgresql");
    }

    @Test
    void completeProdSettingsAndLocalProfilesPass() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("LUMEN_DB_URL", "jdbc:postgresql://127.0.0.1:5432/lumen");
        prod.setProperty("LUMEN_DB_USER", "lumen");
        prod.setProperty("LUMEN_DB_PASSWORD", "pw");
        prod.setProperty("APP_JWT_SECRET", "a".repeat(40));
        assertThatCode(() -> guard.postProcessEnvironment(prod, new SpringApplication())).doesNotThrowAnyException();
        assertThatCode(() -> guard.postProcessEnvironment(new MockEnvironment(), new SpringApplication())).doesNotThrowAnyException();
    }
}
