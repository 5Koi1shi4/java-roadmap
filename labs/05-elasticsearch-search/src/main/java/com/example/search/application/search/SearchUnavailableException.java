package com.example.search.application.search;

public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(Throwable cause) { super("search service unavailable", cause); }
}
