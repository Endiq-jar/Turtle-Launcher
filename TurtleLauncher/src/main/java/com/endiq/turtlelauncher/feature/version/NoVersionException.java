package com.endiq.turtlelauncher.feature.version;

public class NoVersionException extends RuntimeException {
    public NoVersionException(String message) {
        super(message);
    }
}
