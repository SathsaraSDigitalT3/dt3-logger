package com.digitalt3.commons.api;

/**
 * Schema validation modes.
 *
 * @since 0.1.0
 */
public enum ValidationMode {
    /** Invalid events throw validation errors. */
    STRICT,
    /** Invalid events continue with errors attached. */
    LENIENT,
    /** Skip validation completely. */
    OFF
}
