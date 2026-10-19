package com.houqing.tools.video_compressor_backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fails fast, with a readable message, when the production profile is started without its mandatory settings —
 * instead of a driver error about an unresolved "${LUMEN_DB_URL}" deep inside the data source set-up.
 */
public class ProductionGuard implements EnvironmentPostProcessor {
    static final List<String> REQUIRED = List.of("LUMEN_DB_URL", "LUMEN_DB_USER", "LUMEN_DB_PASSWORD", "APP_JWT_SECRET");
    static final int MIN_SECRET = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!Arrays.asList(env.getActiveProfiles()).contains("prod")) return;
        List<String> problems = new ArrayList<>();
        for (String k : REQUIRED) {
            String v = env.getProperty(k);
            if (v == null || v.isBlank()) problems.add(k + " 未设置");
        }
        String secret = env.getProperty("APP_JWT_SECRET");
        if (secret != null && !secret.isBlank()) {
            if (secret.length() < MIN_SECRET) problems.add("APP_JWT_SECRET 太短（至少 " + MIN_SECRET + " 个字符）");
            if (secret.contains("change-me")) problems.add("APP_JWT_SECRET 仍是示例值，请换成随机字符串");
        }
        String url = env.getProperty("LUMEN_DB_URL");
        if (url != null && !url.isBlank() && !url.startsWith("jdbc:postgresql:")) problems.add("LUMEN_DB_URL 需为 jdbc:postgresql:// 开头");
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产配置（prod）缺少必要设置，服务未启动：" + String.join("；", problems)
                    + "。见 docs/deployment.md 第 9 节。");
        }
    }
}
