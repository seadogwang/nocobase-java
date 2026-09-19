package com.nocobase;

import com.nocobase.entity.Role;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency test for the bootstrap endpoint.
 * Ensures that two concurrent bootstrap requests only create one admin user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BootstrapConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // Use TransactionTemplate to commit immediately so executor threads
        // can see the empty database.
        transactionTemplate.executeWithoutResult(status -> {
            userRoleRepository.deleteAll();
            userRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("Concurrent bootstrap requests: only one admin user is created")
    void concurrentBootstrapCreatesExactlyOneUser() throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Runnable bootstrapTask = () -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                mockMvc.perform(post("/api/bootstrap/setup")
                        .contentType(MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // One of the requests will get 409 — that's expected
            }
        };

        executor.submit(bootstrapTask);
        executor.submit(bootstrapTask);

        // Wait for both threads to be ready
        readyLatch.await();
        // Release both threads simultaneously
        startLatch.countDown();

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS),
                "Threads should complete within timeout");

        // Verify exactly ONE user exists
        long userCount = userRepository.count();
        assertEquals(1, userCount, "Exactly one user should exist after concurrent bootstrap");

        // Verify that user has exactly TWO userRole entries (admin + root)
        var user = userRepository.findAll().get(0);
        List<UserRole> userRoles = userRoleRepository.findByUserId(user.getId());
        assertEquals(2, userRoles.size(), "Admin user should have exactly 2 roles (admin + root)");

        // Verify role names are "admin" and "root"
        Set<String> roleNames = userRoles.stream()
                .map(ur -> roleRepository.findById(ur.getRoleId()).orElseThrow().getName())
                .collect(Collectors.toSet());
        assertTrue(roleNames.contains("admin"), "User should have admin role");
        assertTrue(roleNames.contains("root"), "User should have root role");
        assertEquals(2, roleNames.size(), "Should have exactly 2 distinct roles");

        // Verify no duplicate userRole entries (same userId + roleId)
        long distinctUserRoleCount = userRoles.stream()
                .map(ur -> ur.getUserId() + ":" + ur.getRoleId())
                .distinct()
                .count();
        assertEquals(userRoles.size(), distinctUserRoleCount,
                "No duplicate userRole entries should exist");
    }
}