package org.identigon.incognito.api;

/**
 * Base runtime exception for all errors originating within Incognito.
 */
public class IncognitoException extends RuntimeException {
    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public IncognitoException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public IncognitoException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Signals an invalid or unsupported run configuration (e.g. a target role that cannot defer FKs). */
    public static class ConfigException extends IncognitoException {
        /**
         * Creates a config exception with a message.
         *
         * @param message the detail message
         */
        public ConfigException(String message) { super(message); }

        /**
         * Creates a config exception with a message and cause.
         *
         * @param message the detail message
         * @param cause   the underlying cause
         */
        public ConfigException(String message, Throwable cause) { super(message, cause); }
    }

    /** Signals a schema-discovery or classification failure (e.g. an unclassified column). */
    public static class SchemaException extends IncognitoException {
        /**
         * Creates a schema exception with a message.
         *
         * @param message the detail message
         */
        public SchemaException(String message) { super(message); }

        /**
         * Creates a schema exception with a message and cause.
         *
         * @param message the detail message
         * @param cause   the underlying cause
         */
        public SchemaException(String message, Throwable cause) { super(message, cause); }
    }

    /** Signals a relational-integrity failure that must fail closed (e.g. an unsupported cyclic-FK shape). */
    public static class ConstraintException extends IncognitoException {
        /**
         * Creates a constraint exception with a message.
         *
         * @param message the detail message
         */
        public ConstraintException(String message) { super(message); }

        /**
         * Creates a constraint exception with a message and cause.
         *
         * @param message the detail message
         * @param cause   the underlying cause
         */
        public ConstraintException(String message, Throwable cause) { super(message, cause); }
    }

    /** Signals a failure in a key-translation or attribute-cascade store. */
    public static class StoreException extends IncognitoException {
        /**
         * Creates a store exception with a message.
         *
         * @param message the detail message
         */
        public StoreException(String message) { super(message); }

        /**
         * Creates a store exception with a message and cause.
         *
         * @param message the detail message
         * @param cause   the underlying cause
         */
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }
}
