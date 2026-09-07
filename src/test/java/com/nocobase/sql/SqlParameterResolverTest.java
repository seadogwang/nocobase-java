package com.nocobase.sql;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.web.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SqlParameterResolver.
 * Tests parameter resolution logic independent of Spring context.
 */
@DisplayName("SqlParameterResolver")
@ExtendWith(MockitoExtension.class)
class SqlParameterResolverTest {

    @Mock
    private CurrentUserContext currentUserContext;

    private SqlParameterResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SqlParameterResolver(currentUserContext);
    }

    // ========== Static source ==========

    @Nested
    @DisplayName("static source resolution")
    class StaticSourceTests {

        @Test
        @DisplayName("resolves static string parameter")
        void resolvesStaticString() {
            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "status", "type", "string", "source", "static", "defaultValue", "active")
            ), "SELECT * FROM t WHERE status = :status");

            List<Object> values = resolver.resolve(meta, List.of("status"));
            assertEquals(1, values.size());
            assertEquals("active", values.get(0));
        }

        @Test
        @DisplayName("resolves static number parameter")
        void resolvesStaticNumber() {
            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "age", "type", "number", "source", "static", "defaultValue", 42)
            ), "SELECT * FROM t WHERE age = :age");

            List<Object> values = resolver.resolve(meta, List.of("age"));
            assertEquals(1, values.size());
            assertEquals(42, values.get(0));
        }

        @Test
        @DisplayName("resolves static boolean parameter")
        void resolvesStaticBoolean() {
            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "active", "type", "boolean", "source", "static", "defaultValue", true)
            ), "SELECT * FROM t WHERE active = :active");

            List<Object> values = resolver.resolve(meta, List.of("active"));
            assertEquals(1, values.size());
            assertEquals(true, values.get(0));
        }

        @Test
        @DisplayName("resolves static parameter with null defaultValue")
        void resolvesStaticNullDefault() {
            Map<String, Object> paramEntry = new HashMap<>();
            paramEntry.put("name", "status");
            paramEntry.put("type", "string");
            paramEntry.put("source", "static");
            paramEntry.put("defaultValue", null);
            SqlParameterMetadata meta = buildMeta(List.of(paramEntry),
                    "SELECT * FROM t WHERE status = :status");

            List<Object> values = resolver.resolve(meta, List.of("status"));
            assertEquals(1, values.size());
            assertNull(values.get(0));
        }

        @Test
        @DisplayName("resolves multiple static parameters in order")
        void resolvesMultipleStaticParams() {
            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "status", "type", "string", "source", "static", "defaultValue", "active"),
                    Map.of("name", "minAge", "type", "number", "source", "static", "defaultValue", 18)
            ), "SELECT * FROM t WHERE status = :status AND age > :minAge");

            List<Object> values = resolver.resolve(meta, List.of("status", "minAge"));
            assertEquals(2, values.size());
            assertEquals("active", values.get(0));
            assertEquals(18, values.get(1));
        }
    }

    // ========== currentUser source ==========

    @Nested
    @DisplayName("currentUser source resolution")
    class CurrentUserSourceTests {

        @Test
        @DisplayName("resolves currentUser with path=id")
        void resolvesCurrentUserId() {
            when(currentUserContext.getCurrentUserId()).thenReturn(Optional.of(99L));

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "userId", "type", "number", "source", "currentUser", "path", "id", "required", true)
            ), "SELECT * FROM t WHERE owner_id = :userId");

            List<Object> values = resolver.resolve(meta, List.of("userId"));
            assertEquals(1, values.size());
            assertEquals(99L, values.get(0));
        }

        @Test
        @DisplayName("resolves currentUser with path=email")
        void resolvesCurrentUserEmail() {
            when(currentUserContext.getCurrentUserEmail()).thenReturn(Optional.of("user@test.com"));

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "userEmail", "type", "string", "source", "currentUser", "path", "email", "required", true)
            ), "SELECT * FROM t WHERE email = :userEmail");

            List<Object> values = resolver.resolve(meta, List.of("userEmail"));
            assertEquals(1, values.size());
            assertEquals("user@test.com", values.get(0));
        }

        @Test
        @DisplayName("required currentUser throws UnauthorizedException when anonymous")
        void requiredCurrentUserThrowsWhenAnonymous() {
            when(currentUserContext.getCurrentUserId()).thenReturn(Optional.empty());

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "userId", "type", "number", "source", "currentUser", "path", "id", "required", true)
            ), "SELECT * FROM t WHERE owner_id = :userId");

            UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                    () -> resolver.resolve(meta, List.of("userId")));
            assertTrue(ex.getMessage().contains("userId"));
            assertTrue(ex.getMessage().contains("anonymous"));
        }

        @Test
        @DisplayName("non-required currentUser returns null when anonymous")
        void nonRequiredCurrentUserReturnsNullWhenAnonymous() {
            when(currentUserContext.getCurrentUserId()).thenReturn(Optional.empty());

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "userId", "type", "number", "source", "currentUser", "path", "id", "required", false)
            ), "SELECT * FROM t WHERE owner_id = :userId");

            List<Object> values = resolver.resolve(meta, List.of("userId"));
            assertEquals(1, values.size());
            assertNull(values.get(0));
        }

        @Test
        @DisplayName("currentUser id resolves to Long")
        void currentUserIdResolvesToLong() {
            when(currentUserContext.getCurrentUserId()).thenReturn(Optional.of(99L));

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "userId", "type", "number", "source", "currentUser", "path", "id", "required", true)
            ), "SELECT * FROM t WHERE owner_id = :userId");

            List<Object> values = resolver.resolve(meta, List.of("userId"));
            assertEquals(1, values.size());
            assertInstanceOf(Long.class, values.get(0));
            assertEquals(99L, values.get(0));
        }

        @Test
        @DisplayName("currentUser email resolves to String")
        void currentUserEmailResolvesToString() {
            when(currentUserContext.getCurrentUserEmail()).thenReturn(Optional.of("user@test.com"));

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "userEmail", "type", "string", "source", "currentUser", "path", "email", "required", true)
            ), "SELECT * FROM t WHERE email = :userEmail");

            List<Object> values = resolver.resolve(meta, List.of("userEmail"));
            assertEquals(1, values.size());
            assertInstanceOf(String.class, values.get(0));
            assertEquals("user@test.com", values.get(0));
        }
    }

    // ========== Mixed sources ==========

    @Nested
    @DisplayName("mixed source resolution")
    class MixedSourceTests {

        @Test
        @DisplayName("resolves static and currentUser parameters together")
        void resolvesMixedSources() {
            when(currentUserContext.getCurrentUserId()).thenReturn(Optional.of(77L));

            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "status", "type", "string", "source", "static", "defaultValue", "active"),
                    Map.of("name", "userId", "type", "number", "source", "currentUser", "path", "id", "required", true)
            ), "SELECT * FROM t WHERE status = :status AND owner_id = :userId");

            List<Object> values = resolver.resolve(meta, List.of("status", "userId"));
            assertEquals(2, values.size());
            assertEquals("active", values.get(0));
            assertEquals(77L, values.get(1));
        }
    }

    // ========== Error handling ==========

    @Nested
    @DisplayName("error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("throws when parameter name is not found in metadata")
        void throwsForUnknownParameter() {
            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "status", "type", "string", "source", "static", "defaultValue", "active")
            ), "SELECT * FROM t WHERE status = :status");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resolver.resolve(meta, List.of("unknown")));
            assertTrue(ex.getMessage().contains("unknown"));
            assertTrue(ex.getMessage().contains("not defined"));
        }

        @Test
        @DisplayName("default source is static when not specified")
        void defaultSourceIsStatic() {
            SqlParameterMetadata meta = buildMeta(List.of(
                    Map.of("name", "status", "type", "string", "defaultValue", "active")
            ), "SELECT * FROM t WHERE status = :status");

            List<Object> values = resolver.resolve(meta, List.of("status"));
            assertEquals(1, values.size());
            assertEquals("active", values.get(0));
        }
    }

    // ========== Helpers ==========

    private static SqlParameterMetadata buildMeta(List<Map<String, Object>> params, String sql) {
        CollectionDefinition def = CollectionDefinition.builder("test")
                .type("sql")
                .sql(sql)
                .options(Map.of("parameters", params))
                .build();
        return SqlParameterMetadata.from(def);
    }
}