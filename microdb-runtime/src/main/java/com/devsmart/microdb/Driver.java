package com.devsmart.microdb;


import com.devsmart.ubjson.UBValue;

import java.io.IOException;
import java.util.UUID;

public interface Driver {

    void close();

    /**
     * loads the database value with {@code key}
     * @param key
     * @return the value stored in the database or null if it does not exist
     * @throws IOException
     */
    UBValue get(UUID key) throws IOException;

    /**
     * inserts a new value into the the database. If the value is an object and contains
     * a string value for "id" that value will be used as the primary key, else a new
     * unique key will be automatically generated and returned.
     * @param value
     * @return the unique key for {@code value}
     * @throws IOException
     */
    UUID insert(UBValue value) throws IOException;

    /**
     * delete the database object with key {@code key}
     * @param key
     * @throws IOException
     */
    void delete(UUID key) throws IOException;

    <T extends Comparable<?>> KeyIterator<T> queryIndex(String indexName) throws IOException;
    <T extends Comparable<?>> void addIndex(String indexName, MapFunction<T> mapFunction) throws IOException;
    void deleteIndex(String indexName);

    void beginTransaction() throws IOException;
    void commitTransaction() throws IOException;
    void rollbackTransaction() throws IOException;
}
