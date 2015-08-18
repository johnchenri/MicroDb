package com.devsmart.microdb.ubjson;


import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class UBString extends UBValue {

    public static final Charset UTF_8 = Charset.forName("UTF-8");

    private byte[] mData;

    UBString(byte[] string) {
        mData = string;
    }

    @Override
    public Type getType() {
        return Type.String;
    }

    @Override
    public void write(OutputStream out) throws IOException {
        out.write(MARKER_STRING);
        UBValue intValue = UBValueFactory.createInt(mData.length);
        intValue.write(out);
        out.write(mData);
    }

    public String getString() {
        return new String(mData, UTF_8);
    }
}
