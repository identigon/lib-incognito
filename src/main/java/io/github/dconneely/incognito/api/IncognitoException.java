package io.github.dconneely.incognito.api;

/**
 * Base runtime exception for all errors originating within Incognito.
 */
public class IncognitoException extends RuntimeException {
    public IncognitoException(String message) {
        super(message);
    }

    public IncognitoException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class ConfigException extends IncognitoException {
        public ConfigException(String message) { super(message); }
        public ConfigException(String message, Throwable cause) { super(message, cause); }
    }

    public static class SchemaException extends IncognitoException {
        public SchemaException(String message) { super(message); }
        public SchemaException(String message, Throwable cause) { super(message, cause); }
    }

    public static class ConstraintException extends IncognitoException {
        public ConstraintException(String message) { super(message); }
        public ConstraintException(String message, Throwable cause) { super(message, cause); }
    }

    public static class StoreException extends IncognitoException {
        public StoreException(String message) { super(message); }
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }
}
