package br.com.archflow.api.storage;

/** Unchecked failure raised by the configured blob storage implementation. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
