package com.nocobase.web;

/**
 * Exception thrown when a user is not authenticated.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}