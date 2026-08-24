package com.example.search.application.maintenance;

public class RebuildAlreadyRunningException extends RuntimeException {
    public RebuildAlreadyRunningException(String message) { super(message); }
}
