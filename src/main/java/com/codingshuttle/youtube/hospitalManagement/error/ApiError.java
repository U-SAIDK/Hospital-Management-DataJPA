package com.codingshuttle.youtube.hospitalManagement.error;

import lombok.Data;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

// The single JSON error shape returned by every handler in GlobalExceptionHandler - keeping it
// one class means every failure across the whole app (auth, JWT, access-denied, 500s) looks
// the same to a client, regardless of which exception triggered it.

@Data
public class ApiError {

    private LocalDateTime timeStamp;
    private String error;
    private HttpStatus statusCode;

    // Timestamp is stamped at construction time (when the error is built, i.e. right as it's
    // handled) rather than left for the caller to set - guarantees it always reflects the actual failure moment.
    public ApiError() {
        this.timeStamp = LocalDateTime.now();
    }

    public ApiError(String error, HttpStatus statusCode) {
        this();
        this.error = error;
        this.statusCode = statusCode;
    }
}
