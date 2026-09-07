package com.nocobase.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Initializes the Collection Runtime Registry on application startup.
 */
@Component
public class RuntimeInitializer {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInitializer.class);

    private final CollectionRuntimeService runtimeService;

    public RuntimeInitializer(CollectionRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Initializing collection runtime registry...");
        runtimeService.loadAll();
        log.info("Collection runtime registry initialized with {} collections",
                runtimeService.getCollectionNames().size());
    }
}