package br.com.archflow.api.storage;

/** Blob storage boundary. Metadata and tenant authorization live outside it. */
public interface ObjectStorage {

    void put(String key, byte[] content);

    byte[] get(String key);

    void delete(String key);
}
