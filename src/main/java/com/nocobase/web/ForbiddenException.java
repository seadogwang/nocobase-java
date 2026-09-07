package com.nocobase.web;

/**
 * Exception thrown when a user lacks permission for an action.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}