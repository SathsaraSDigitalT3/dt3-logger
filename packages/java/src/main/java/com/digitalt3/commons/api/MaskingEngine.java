package com.digitalt3.commons.api;

import java.util.List;
import java.util.Map;

/**
 * Engine for recursive sensitive data masking.
 *
 * @since 0.1.0
 */
public interface MaskingEngine {

    /**
     * Mask sensitive fields in the given data.
     * Returns a new object with sensitive fields replaced.
     * Does not mutate the original.
     *
     * @param data The data to mask
     * @return A masked copy of the data
     */
    Map<String, Object> mask(Map<String, Object> data);

    /**
     * Get the list of field names considered sensitive.
     *
     * @return List of sensitive field names
     */
    List<String> getSensitiveFields();
}
