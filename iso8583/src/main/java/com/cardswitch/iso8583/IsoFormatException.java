package com.cardswitch.iso8583;

/**
 * A message could not be packed or unpacked.
 * <p>
 * Messages name the offending field and lengths but never include field values, so the exception is safe to log
 * without leaking card data.
 */
public class IsoFormatException extends RuntimeException {

    public IsoFormatException(String message) {
        super(message);
    }
}
