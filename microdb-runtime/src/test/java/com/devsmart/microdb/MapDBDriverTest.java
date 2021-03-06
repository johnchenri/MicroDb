package com.devsmart.microdb;


import com.devsmart.ubjson.UBObject;
import com.devsmart.ubjson.UBValueFactory;
import org.junit.Test;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.IOException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class MapDBDriverTest {

    private static UUID insert(String type, String name, MapDBDriver driver) throws IOException {
        UBObject obj;
        obj = UBValueFactory.createObject();
        obj.put("type", UBValueFactory.createString(type));
        obj.put("name", UBValueFactory.createString(name));

        final UUID id = driver.genId();
        driver.insert(id, obj);
        return id;
    }


    @Test
    public void indexTest() throws IOException {

        DB mapdb = DBMaker.newMemoryDB()
                .make();

        MapDBDriver dbDriver = new MapDBDriver(mapdb);
        dbDriver.addIndex("type", MicroDB.INDEX_OBJECT_TYPE);

        insert("dog", "fido", dbDriver);
        insert("cat", "whiskers", dbDriver);
        insert("cat", "symo", dbDriver);
        insert("cat", "thuggy", dbDriver);
        insert("dog", "mondo", dbDriver);
        insert("dog", "bolt", dbDriver);

        Cursor rows = dbDriver.queryIndex("type", "dog", true, "dog", true);
        int dogCount = 0;
        do {
            Row r = rows.get();
            assertEquals("dog", r.getSecondaryKey());
            dogCount++;
        } while(rows.next());
        assertEquals(3, dogCount);

        rows = dbDriver.queryIndex("type", "cat", true, "cat", true);
        int catCount = 0;
        do {
            Row r = rows.get();
            assertEquals("cat", r.getSecondaryKey());
            catCount++;
        } while(rows.next());
        assertEquals(3, catCount);

    }
}
