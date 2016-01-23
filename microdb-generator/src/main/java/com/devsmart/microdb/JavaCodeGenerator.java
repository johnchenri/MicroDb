package com.devsmart.microdb;


import com.devsmart.microdb.ast.Nodes;
import com.devsmart.ubjson.UBObject;
import com.devsmart.ubjson.UBString;
import com.devsmart.ubjson.UBValue;
import com.devsmart.ubjson.UBValueFactory;
import com.squareup.javapoet.*;

import javax.lang.model.element.*;

import java.util.ArrayList;

public class JavaCodeGenerator {

    private static final String MICRODB_PACKAGE = "com.devsmart.microdb";

    private final Nodes.DBONode mDBO;
    private final Nodes.FileNode mFileCtx;

    public JavaCodeGenerator(Nodes.DBONode dbo, Nodes.FileNode ctx) {
        mDBO = dbo;
        mFileCtx = ctx;
    }

    public JavaFile createJavaFile() {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(mDBO.name)
                .addModifiers(Modifier.PUBLIC);
        if(mDBO.extend != null) {
            classBuilder.superclass(ClassName.get(mFileCtx.packageName, mDBO.extend));
        } else {
            classBuilder.superclass(ClassName.get(MICRODB_PACKAGE, "DBObject"));
        }

        classBuilder.addField(
                FieldSpec.builder(UBString.class, "TYPE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.createString($S)", UBValueFactory.class, mDBO.name)
                        .build());

        ArrayList<FieldCodeGen> fieldCodeGane = new ArrayList<FieldCodeGen>();

        for(Nodes.FieldNode field : mDBO.fields) {
            TypeName fieldType = getTypeName(field.type);
            if(TypeName.BOOLEAN == fieldType) {
                fieldCodeGane.add(new BoolFieldCodeGen(field));
            } else if(TypeName.BYTE == fieldType){
                fieldCodeGane.add(new ByteFieldCodeGen(field));
            } else if(TypeName.SHORT == fieldType){
                fieldCodeGane.add(new ShortFieldCodeGen(field));
            } else if(TypeName.CHAR == fieldType){
                fieldCodeGane.add(new CharFieldCodeGen(field));
            } else if(TypeName.INT == fieldType) {
                fieldCodeGane.add(new IntFieldCodeGen(field));
            } else if(TypeName.LONG == fieldType) {
                fieldCodeGane.add(new LongFieldCodeGen(field));
            } else if(TypeName.FLOAT == fieldType){
                fieldCodeGane.add(new FloatFieldCodeGen(field));
            } else if(TypeName.DOUBLE == fieldType){
                fieldCodeGane.add(new DoubleFieldCodeGen(field));
            } else if(field.type.type == Nodes.TypeNode.STRING) {
                fieldCodeGane.add(new StringFieldCodeGen(field));
            } else if(field.type.type == Nodes.TypeNode.DBO) {
                fieldCodeGane.add(new DBOFieldCodeGen(field));
            }

        }

        classBuilder.addMethod(generateWriteToUBObjectMethod(fieldCodeGane));
        classBuilder.addMethod(generateReadFromUBObjectMethod(fieldCodeGane));

        for(FieldCodeGen codeGen : fieldCodeGane) {
            classBuilder.addField(codeGen.genField());
            classBuilder.addMethod(codeGen.genGetterMethod());
            classBuilder.addMethod(codeGen.genSetterMethod());
        }

        JavaFile proxySourceFile = JavaFile.builder(mFileCtx.packageName, classBuilder.build())
                .skipJavaLangImports(true)
                .build();

        return proxySourceFile;
    }

    private static MethodSpec generateWriteToUBObjectMethod(ArrayList<FieldCodeGen> fieldCodeGane) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("writeToUBObject");
        builder.addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UBObject.class, "obj");

        builder.addStatement("super.writeToUBObject(obj)");
        builder.addStatement("final $T db = getDB()", MicroDB.class);

        for(FieldCodeGen fieldCodeGen : fieldCodeGane) {
            fieldCodeGen.genWriteToUBObject(builder);
        }

        return builder.build();
    }

    private static MethodSpec generateReadFromUBObjectMethod(ArrayList<FieldCodeGen> fieldCodeGens) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("readFromUBObject");
        builder.addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UBObject.class, "obj");

        builder.addStatement("super.readFromUBObject(obj)");
        builder.addStatement("final $T db = getDB()", MicroDB.class);
        builder.addStatement("$T value = null", UBValue.class);

        for(FieldCodeGen fieldCodeGen : fieldCodeGens) {
            fieldCodeGen.genReadFromUBObject(builder);
        }

        return builder.build();
    }

    static TypeName getTypeName(Nodes.TypeNode type) {

        switch(type.type) {
            case Nodes.TypeNode.BOOL:
                return TypeName.BOOLEAN;

            case Nodes.TypeNode.CHAR:
                return TypeName.CHAR;

            case Nodes.TypeNode.STRING:
                return ClassName.get(String.class);

            case Nodes.TypeNode.INT: {
                Nodes.NumberType numberType = (Nodes.NumberType) type;
                switch(numberType.size) {
                    case 8:
                        return TypeName.BYTE;
                    case 16:
                        return TypeName.SHORT;
                    case 32:
                        return TypeName.INT;
                    case 64:
                        return TypeName.LONG;
                }
            }

            case Nodes.TypeNode.FLOAT: {
                Nodes.NumberType numberType = (Nodes.NumberType) type;
                switch (numberType.size) {
                    case 32:
                        return TypeName.FLOAT;
                    case 64:
                        return TypeName.DOUBLE;
                }
            }

            case Nodes.TypeNode.DBO:
                return ((Nodes.ObjType)type).getClassName();


        }

        return null;

    }

    private abstract class FieldCodeGen {
        Nodes.FieldNode mField;

        FieldCodeGen(Nodes.FieldNode field) {
            mField = field;
        }

        String createGetterName() {
            final String methodName = String.format("get%s%s", mField.name.substring(0, 1).toUpperCase(),
                    mField.name.substring(1));
            return methodName;
        }

        String createSetterName() {
            final String methodName = String.format("set%s%s", mField.name.substring(0, 1).toUpperCase(),
                    mField.name.substring(1));
            return methodName;
        }

        FieldSpec genField() {
            FieldSpec retval = FieldSpec.builder(getTypeName(mField.type), mField.name, Modifier.PRIVATE)
                    .build();
            return retval;
        }
        abstract void genReadFromUBObject(MethodSpec.Builder builder);
        abstract void genWriteToUBObject(MethodSpec.Builder builder);
        MethodSpec genGetterMethod() {
            final String getterName = createGetterName();
            return MethodSpec.methodBuilder(getterName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(getTypeName(mField.type))
                    .addStatement("return $L", mField.name)
                    .build();
        }
        MethodSpec genSetterMethod() {
            final String setterName = createSetterName();
            return MethodSpec.methodBuilder(setterName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.VOID)
                    .addParameter(getTypeName(mField.type), "value")
                    .addStatement("this.$L = value", mField.name)
                    .addStatement("setDirty()")
                    .build();
        }

    }

    class BoolFieldCodeGen extends FieldCodeGen {

        BoolFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asBool()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createBool($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class ByteFieldCodeGen extends FieldCodeGen {

        ByteFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asByte()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class ShortFieldCodeGen extends FieldCodeGen {

        ShortFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asShort()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class CharFieldCodeGen extends FieldCodeGen {

        CharFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asChar()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class IntFieldCodeGen extends FieldCodeGen {

        IntFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asInt()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class LongFieldCodeGen extends FieldCodeGen {

        LongFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asLong()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class FloatFieldCodeGen extends FieldCodeGen {

        FloatFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asFloat32()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createFloat32($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class DoubleFieldCodeGen extends FieldCodeGen {

        DoubleFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = value.asFloat64()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createFloat64($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class StringFieldCodeGen extends FieldCodeGen {

        StringFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.beginControlFlow("if (value.isString())");
            methodBuilder.addStatement("this.$L = value.asString()", mField.name);
            methodBuilder.nextControlFlow("else");
            methodBuilder.addStatement("this.$L = null", mField.name);
            methodBuilder.endControlFlow();
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createStringOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class DBOFieldCodeGen extends FieldCodeGen {

        private final ClassName mClassName;

        DBOFieldCodeGen(Nodes.FieldNode field) {
            super(field);
            mClassName = ((Nodes.ObjType)field.type).getClassName();
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null)");
            methodBuilder.addStatement("this.$L = new $T()", mField.name, mClassName);
            methodBuilder.addStatement("this.$L = db != null ? db.readObject(value, this.$L) : $T.readDBObj(value, this.$L)",
                    mField.name, mField.name, Utils.class, mField.name);
            methodBuilder.nextControlFlow("else");
            methodBuilder.addStatement("this.$L = null", mField.name);
            methodBuilder.endControlFlow();



        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("obj.put($S, db != null ? db.writeObject($L) : $T.writeDBObj($L))",
                    mField.name, mField.name, Utils.class, mField.name);

        }
    }



}