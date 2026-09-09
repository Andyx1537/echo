package com.echo.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaContractTest {

    @Test
    void requiredVersionIsRecordedInSchemaSource() throws Exception {
        try (var in = SchemaContractTest.class.getResourceAsStream("/sql/schema.sql")) {
            assertThat(in).as("schema.sql must be on the runtime classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_schema_version\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_onboarding_session\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_resource_cleanup_queue\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_onboarding_subject\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_onboarding_asset\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_onboarding_answer\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_pet_profile_fact\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_generation_anchor\"");
            assertThat(sql).contains("REFERENCES \"t_onboarding_session\"(\"onboardingId\") ON DELETE CASCADE");
            assertThat(sql).contains("(" + EchoDatabase.REQUIRED_SCHEMA_VERSION + ",");
            assertThat(sql).contains("USING hnsw (\"embedding\" vector_cosine_ops)");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_behavior_event\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_explicit_feedback\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_user_hypothesis\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_adaptation_decision\"");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS \"t_adaptation_profile_clear\"");
            assertThat(sql).contains("UNIQUE (\"accountId\", \"idempotencyKey\")");
            assertThat(sql).contains("\"status\" IN ('active','expired','rejected','cleared')");
        }
    }

    @Test
    void productionLikeDbConfigUsesValidateMode() throws Exception {
        var path = java.nio.file.Path.of("../deploy/echo-db.properties").normalize();
        assertThat(path).exists();
        var properties = new java.util.Properties();
        try (var in = java.nio.file.Files.newInputStream(path)) {
            properties.load(in);
        }
        assertThat(properties.getProperty("db.dialect")).isEqualTo("postgresql");
        assertThat(properties.getProperty("db.schemaMode")).isEqualTo("validate");
    }
}
