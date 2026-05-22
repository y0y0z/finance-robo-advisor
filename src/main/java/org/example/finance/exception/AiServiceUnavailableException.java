package org.example.finance.exception;

/**
 * AI service is unavailable because all configured model calls failed or no
 * candidate model is configured.
 */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(String message) {
        super(message);
    }
}
