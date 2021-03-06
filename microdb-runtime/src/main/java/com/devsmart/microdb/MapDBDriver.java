package com.devsmart.microdb;


import com.devsmart.ubjson.*;
import org.mapdb.*;

import java.io.*;
import java.util.*;

public class MapDBDriver implements Driver {

    final DB mMapDB;
    final Atomic.Var<UBObject> mMetadata;
    BTreeMap<UUID, UBValue> mObjects;
    private Map<String, IndexObject> mIndicies = new HashMap<String, IndexObject>();

    public static class UBValueSerializer implements Serializer<UBValue>, Serializable {

        @Override
        public void serialize(DataOutput out, UBValue value) throws IOException {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            UBWriter writer = new UBWriter(bout);
            writer.write(value);
            writer.close();

            byte[] buff = bout.toByteArray();
            out.writeInt(buff.length);
            out.write(buff);
        }

        @Override
        public UBValue deserialize(DataInput in, int available) throws IOException {
            final int size = in.readInt();
            byte[] buff = new byte[size];
            in.readFully(buff);

            UBReader reader = new UBReader(new ByteArrayInputStream(buff));
            UBValue retval = reader.read();
            reader.close();
            return retval;
        }

        @Override
        public int fixedSize() {
            return -1;
        }
    }

    public static final Serializer<UBValue> SERIALIZER_UBVALUE = new UBValueSerializer();

    public MapDBDriver(DB mapdb) {
        mMapDB = mapdb;
        mObjects = mMapDB.createTreeMap("objects")
                .keySerializerWrap(Serializer.UUID)
                .valueSerializer(SERIALIZER_UBVALUE)
                .valuesOutsideNodesEnable()
                .comparator(BTreeMap.COMPARABLE_COMPARATOR)
                .makeOrGet();

        if (mMapDB.exists("metadata")) {
            mMetadata = mMapDB.getAtomicVar("metadata");
        } else {
            Atomic.Var<? extends UBValue> metadata = mMapDB.createAtomicVar("metadata", UBValueFactory.createObject(), SERIALIZER_UBVALUE);
            mMetadata = (Atomic.Var<UBObject>) metadata;
        }
    }

    public DB getDB() {
        return mMapDB;
    }

    @Override
    public void close() {
        mMapDB.close();
    }

    @Override
    public UBObject getMeta() throws IOException {
        return mMetadata.get().asObject();
    }

    @Override
    public void saveMeta(UBObject obj) throws IOException {
        mMetadata.set(obj);
    }

    @Override
    public UBValue get(UUID key) throws IOException {
        return mObjects.get(key);
    }

    @Override
    public UUID genId() {
        UUID key = UUID.randomUUID();
        while (mObjects.containsKey(key)) {
            key = UUID.randomUUID();
        }
        return key;
    }

    @Override
    public void insert(UUID id, UBValue value) throws IOException {
        mObjects.put(id, value);
    }

    @Override
    public void update(UUID id, UBValue value) throws IOException {
        mObjects.put(id, value);
    }

    @Override
    public void delete(UUID key) throws IOException {
        mObjects.remove(key);
    }

    private static final UUID MAX_UUID = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
    private static final UUID MIN_UUID = new UUID(Long.MIN_VALUE, Long.MIN_VALUE);

    @Override
    public <T extends Comparable<T>> Cursor queryIndex(String indexName, T min, boolean minInclusive, T max, boolean maxInclusive) throws IOException {
        MapDBCursor<T> retval = new MapDBCursor<T>();
        retval.mDriver = this;

        NavigableSet<Fun.Tuple2<T, UUID>> index = mMapDB.getTreeSet(indexName);

        if (max != null && min != null) {
            retval.min = Fun.t2(min, minInclusive ? MIN_UUID : MAX_UUID);
            retval.max = Fun.t2(max, maxInclusive ? MAX_UUID : MIN_UUID);
            retval.index = index.subSet(retval.min, minInclusive, retval.max, maxInclusive);

        } else if (min != null && max == null) {
            retval.min = Fun.t2(min, minInclusive ? MIN_UUID : MAX_UUID);
            retval.index = index.tailSet(retval.min, minInclusive);

        } else if (min == null && max != null) {
            retval.max = Fun.t2(max, maxInclusive ? MAX_UUID : MIN_UUID);
            retval.index = index.headSet(retval.max, maxInclusive);
        } else {
            retval.index = index;
        }

        retval.seekToBegining();

        return retval;
    }

    private static class MapDBCursor<T extends Comparable<T>> implements Cursor {

        MapDBDriver mDriver;
        NavigableSet<Fun.Tuple2<T, UUID>> index;
        Fun.Tuple2<T, UUID> min;
        Fun.Tuple2<T, UUID> max;
        private Fun.Tuple2<T, UUID> mCurrentValue;
        private int mPosition;


        @Override
        public void seekToBegining() {
            if(!index.isEmpty()) {
                mCurrentValue = index.first();
                mPosition = 0;
            }
        }

        @Override
        public void seekToEnd() {
            mCurrentValue = index.last();
            mPosition = getCount();
        }

        @Override
        public int getPosition() {
            return mPosition;
        }

        @Override
        public boolean moveToPosition(int pos) {
            int currentPos;
            while( (currentPos = getPosition()) != pos) {
              if(currentPos < pos) {
                  next();
              } else {
                  prev();
              }
            }
            return true;
        }

        @Override
        public boolean next() {
            mCurrentValue = index.higher(mCurrentValue);
            mPosition++;
            return mCurrentValue != null;
        }

        @Override
        public boolean prev() {
            mCurrentValue = index.lower(mCurrentValue);
            mPosition--;
            return mCurrentValue != null;
        }

        @Override
        public Row get() {
            if(mCurrentValue == null) {
                return null;
            } else {
                return new MapDBRow<T>(mDriver, mCurrentValue);
            }
        }

        @Override
        public int getCount() {
            return index.size();
        }
    }

    private static class MapDBRow<T extends Comparable<T>> implements Row {

        private final MapDBDriver mDriver;
        final Fun.Tuple2<T, UUID> mTuple;
        UBValue mValue;

        public MapDBRow(MapDBDriver driver, Fun.Tuple2<T, UUID> tuple) {
            mDriver = driver;
            mTuple = tuple;
        }

        @Override
        public UUID getPrimaryKey() {
            return mTuple.b;
        }

        @Override
        public T getSecondaryKey() {
            return mTuple.a;
        }

        @Override
        public UBValue getValue() {
            if (mValue == null) {
                try {
                    mValue = mDriver.get(getPrimaryKey());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return mValue;
        }
    }

    private class IndexObject<T extends Comparable<T>> {
        public final String name;
        MapFunction<T> mapFunction;

        private Bind.MapListener mListener;

        public IndexObject(String name, final MapFunction<T> mapFunction) {
            this.name = name;
            this.mapFunction = mapFunction;
        }

        void install() {
            if(mListener != null) {
                mObjects.modificationListenerRemove(mListener);
            }

            NavigableSet<Fun.Tuple2<T, UUID>> index = mMapDB.createTreeSet(name)
                    .makeOrGet();

            Fun.Function2<T[], UUID, UBValue> microDBMapFunction = new Fun.Function2<T[], UUID, UBValue>() {

                final MapDBEmitter<T> mEmitter = new MapDBEmitter<T>();

                @Override
                public T[] run(UUID uuid, UBValue ubValue) {
                    synchronized (mEmitter) {
                        mEmitter.clear();
                        mapFunction.map(ubValue, mEmitter);
                        return mEmitter.getKeys();
                    }
                }
            };

            mListener = createIndexListener(mObjects, index, microDBMapFunction);
            mObjects.modificationListenerAdd(mListener);
        }

        void reindex() {
            NavigableSet<Fun.Tuple2<T, UUID>> index = mMapDB.createTreeSet(name)
                    .makeOrGet();
            index.clear();

            Fun.Function2<T[], UUID, UBValue> fun = createMapDBFunction();

            if(index.isEmpty()){
                for(Map.Entry<UUID,UBValue> e:mObjects.entrySet()){
                    T[] k2 = fun.run(e.getKey(), e.getValue());
                    if(k2 != null)
                        for(T k22 :k2)
                            index.add(Fun.t2(k22, e.getKey()));
                }
            }
        }

        private Fun.Function2<T[], UUID, UBValue> createMapDBFunction() {
            return new Fun.Function2<T[], UUID, UBValue>() {

                final MapDBEmitter<T> mEmitter = new MapDBEmitter<T>();

                @Override
                public T[] run(UUID uuid, UBValue ubValue) {
                    synchronized (mEmitter) {
                        mEmitter.clear();
                        mapFunction.map(ubValue, mEmitter);
                        return mEmitter.getKeys();
                    }
                }
            };
        }

        private <K,V, K2> Bind.MapListener<K, V> createIndexListener(Bind.MapWithModificationListener<K, V> map,
                                                                     final Set<Fun.Tuple2<K2, K>> secondary,
                                                                     final Fun.Function2<K2[], K, V> fun) {
            return new Bind.MapListener<K, V>() {
                @Override
                public void update(K key, V oldVal, V newVal) {
                    if (newVal == null) {
                        //removal
                        K2[] k2 = fun.run(key, oldVal);
                        if (k2 != null)
                            for (K2 k22 : k2)
                                secondary.remove(Fun.t2(k22, key));
                    } else if (oldVal == null) {
                        //insert
                        K2[] k2 = fun.run(key, newVal);
                        if (k2 != null)
                            for (K2 k22 : k2)
                                secondary.add(Fun.t2(k22, key));
                    } else {
                        //update, must remove old key and insert new
                        K2[] oldk = fun.run(key, oldVal);
                        K2[] newk = fun.run(key, newVal);
                        if (oldk == null) {
                            //insert new
                            if (newk != null)
                                for (K2 k22 : newk)
                                    secondary.add(Fun.t2(k22, key));
                            return;
                        }
                        if (newk == null) {
                            //remove old
                            for (K2 k22 : oldk)
                                secondary.remove(Fun.t2(k22, key));
                            return;
                        }

                        Set<K2> hashes = new HashSet<K2>();
                        Collections.addAll(hashes, oldk);

                        //add new non existing items
                        for (K2 k2 : newk) {
                            if (!hashes.contains(k2)) {
                                secondary.add(Fun.t2(k2, key));
                            }
                        }
                        //remove items which are in old, but not in new
                        for (K2 k2 : newk) {
                            hashes.remove(k2);
                        }
                        for (K2 k2 : hashes) {
                            secondary.remove(Fun.t2(k2, key));
                        }
                    }
                }
            };
        }
    }

    @Override
    public <T extends Comparable<T>> void addIndex(String indexName, final MapFunction<T> mapFunction) throws IOException {
        IndexObject index = mIndicies.get(indexName);
        if(index == null) {
            index = new IndexObject(indexName, mapFunction);
            mIndicies.put(indexName, index);
            index.install();
        }
    }

    @Override
    public void recomputeIndex(String indexName) {
        IndexObject index = mIndicies.get(indexName);
        if(index != null) {
            index.reindex();
        }
    }

    private static class MapDBEmitter<T extends Comparable<T>> implements Emitter<T> {

        ArrayList<T> mKeys = new ArrayList<T>(3);

        public void clear() {
            mKeys.clear();
        }


        @Override
        public void emit(T key) {
            mKeys.add(key);
        }


        public T[] getKeys() {
            if(mKeys.isEmpty()) {
                return null;
            }
            T[] retval = (T[]) new Comparable[mKeys.size()];
            retval = mKeys.toArray(retval);
            return retval;
        }
    }

    @Override
    public void deleteIndex(String indexName) {
        mMapDB.delete(indexName);

    }

    @Override
    public long incrementLongField(String fieldName) {
        return mMapDB.getAtomicLong(fieldName).getAndIncrement();
    }

    @Override
    public void beginTransaction() throws IOException {

    }

    @Override
    public void commitTransaction() throws IOException {
        mMapDB.commit();
    }

    @Override
    public void rollbackTransaction() throws IOException {
        mMapDB.rollback();
    }
}
