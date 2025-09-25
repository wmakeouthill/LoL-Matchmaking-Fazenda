package br.com.lolmatchmaking.backend.exception;

public class MatchCreationException extends RuntimeException {
    public MatchCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
