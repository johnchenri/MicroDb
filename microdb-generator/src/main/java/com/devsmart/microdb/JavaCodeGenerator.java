package com.devsmart.microdb;


import com.devsmart.microdb.ast.Nodes;
import com.devsmart.ubjson.*;
import com.squareup.javapoet.*;

import javax.lang.model.element.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class JavaCodeGenerator {

    private static final String MICRODB_PACKAGE = "com.devsmart.microdb";
    private static final ClassName UBOBJECT_CLASSNAME = ClassName.get(UBObject.class);
    private static final String NO_SERIALIZE = "NoSerialize";
    private static final String AUTOINCREMENT = "AutoIncrement";
    private static final String INDEX = "Index";

    private final Nodes.DBONode mDBO;
    private final Nodes.FileNode mFileCtx;

    public JavaCodeGenerator(Nodes.DBONode dbo, Nodes.FileNode ctx) {
        mDBO = dbo;
        mFileCtx = ctx;
    }

    public String generateCode() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();

        JavaFile javaFile = createJavaFile();
        javaFile.writeTo(stringBuilder);


        StringBuilder extraCode = new StringBuilder();
        for(String codeblock : mDBO.codeblocks) {
            extraCode.append(codeblock);
        }
        extraCode.append("}");

        final int closePos = stringBuilder.lastIndexOf("}");
        stringBuilder.replace(closePos, stringBuilder.length(), extraCode.toString());

        String finalCode = stringBuilder.toString();
        return finalCode;

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


        CodeBlock.Builder subtypesInitBuilder = CodeBlock.builder();
        subtypesInitBuilder.add("new UBString[]{ ");
        for(Nodes.DBONode extend : mDBO.dboThatExtend){
            subtypesInitBuilder.add("$T.createString($S), ", UBValueFactory.class, extend.name);
        }
        subtypesInitBuilder.add("$T.TYPE }", getThisClassName());


        classBuilder.addField(
                FieldSpec.builder(UBString[].class, "SUBTYPES", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer(subtypesInitBuilder.build())
                        .build()
        );

        ArrayList<FieldCodeGen> fieldCodeGane = new ArrayList<FieldCodeGen>();

        for(Nodes.FieldNode field : mDBO.fields) {

            if(field.type.isArray) {
                ArrayTypeName fieldType = (ArrayTypeName)getTypeName(field.type);
                if(field.type.type == Nodes.TypeNode.BOOL) {
                    fieldCodeGane.add(new BoolArrayFieldCodeGen(field));
                } else if(TypeName.BYTE == fieldType.componentType) {
                    fieldCodeGane.add(new ByteArrayFieldCodeGen(field));
                } else if(TypeName.SHORT == fieldType.componentType) {
                    fieldCodeGane.add(new ShortArrayFieldCodeGen(field));
                } else if(TypeName.INT == fieldType.componentType) {
                    fieldCodeGane.add(new IntArrayFieldCodeGen(field));
                } else if(TypeName.LONG == fieldType.componentType) {
                    fieldCodeGane.add(new LongArrayFieldCodeGen(field));
                } else if(TypeName.FLOAT == fieldType.componentType) {
                    fieldCodeGane.add(new FloatArrayFieldCodeGen(field));
                } else if(TypeName.DOUBLE == fieldType.componentType) {
                    fieldCodeGane.add(new DoubleArrayFieldCodeGen(field));
                } else if(field.type.type == Nodes.TypeNode.STRING){
                    fieldCodeGane.add(new StringArrayFieldCodeGen(field));
                } else if(field.type.type == Nodes.TypeNode.DBO) {
                    fieldCodeGane.add(new DBOArrayFieldCodeGen(field));
                }
            } else {
                TypeName fieldType = getTypeName(field.type);
                if (TypeName.BOOLEAN == fieldType) {
                    fieldCodeGane.add(new BoolFieldCodeGen(field));
                } else if (TypeName.BYTE == fieldType) {
                    fieldCodeGane.add(new ByteFieldCodeGen(field));
                } else if (TypeName.SHORT == fieldType) {
                    fieldCodeGane.add(new ShortFieldCodeGen(field));
                } else if (TypeName.CHAR == fieldType) {
                    fieldCodeGane.add(new CharFieldCodeGen(field));
                } else if (TypeName.INT == fieldType) {
                    fieldCodeGane.add(new IntFieldCodeGen(field));
                } else if (TypeName.LONG == fieldType) {
                    fieldCodeGane.add(new LongFieldCodeGen(field));
                } else if (TypeName.FLOAT == fieldType) {
                    fieldCodeGane.add(new FloatFieldCodeGen(field));
                } else if (TypeName.DOUBLE == fieldType) {
                    fieldCodeGane.add(new DoubleFieldCodeGen(field));
                } else if (field.type.type == Nodes.TypeNode.STRING) {
                    fieldCodeGane.add(new StringFieldCodeGen(field));
                } else if (field.type.type == Nodes.TypeNode.DBO) {
                    if(UBOBJECT_CLASSNAME.equals(fieldType)) {
                        fieldCodeGane.add(new UBObjectFieldCodeGen(field));
                    } else {
                        fieldCodeGane.add(new DBOFieldCodeGen(field));
                    }
                }
            }

        }

        classBuilder.addMethod(generateWriteToUBObjectMethod(fieldCodeGane));
        classBuilder.addMethod(generateReadFromUBObjectMethod(fieldCodeGane));

        for(FieldCodeGen codeGen : fieldCodeGane) {
            classBuilder.addField(codeGen.genField());
            classBuilder.addMethod(codeGen.genGetterMethod());

            if(!codeGen.mField.type.annotations.contains(AUTOINCREMENT)) {
                classBuilder.addMethod(codeGen.genSetterMethod());
            }
        }

        generateInstallMethod(classBuilder, fieldCodeGane);
        generateOtherMethods(classBuilder, fieldCodeGane);

        JavaFile proxySourceFile = JavaFile.builder(mFileCtx.packageName, classBuilder.build())
                .skipJavaLangImports(true)
                .build();

        return proxySourceFile;
    }

    private ClassName getThisClassName() {
        return ClassName.get(mFileCtx.packageName, mDBO.name);
    }

    private static MethodSpec generateWriteToUBObjectMethod(ArrayList<FieldCodeGen> fieldCodeGane) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("writeToUBObject");
        builder.addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED)
                .returns(TypeName.VOID)
                .addParameter(UBObject.class, "obj");

        builder.addStatement("super.writeToUBObject(obj)");
        builder.addStatement("final $T db = getDB()", MicroDB.class);
        builder.addStatement("obj.put($S, TYPE)", "type");

        for(FieldCodeGen fieldCodeGen : fieldCodeGane) {
            if(!fieldCodeGen.mField.type.annotations.contains(NO_SERIALIZE)) {
                fieldCodeGen.genWriteToUBObject(builder);
            }
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
            if(!fieldCodeGen.mField.type.annotations.contains(NO_SERIALIZE)) {
                fieldCodeGen.genReadFromUBObject(builder);
            }
        }

        return builder.build();
    }

    private void generateInstallMethod(TypeSpec.Builder classBuilder, ArrayList<FieldCodeGen> fields) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("install")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(MicroDB.class, "db");

        Set<Class<? extends Exception>> excptionsTypes = new HashSet<Class<? extends Exception>>();

        for(FieldCodeGen fieldCodeGen : fields) {
            CodeBlock.Builder blockBuilder = CodeBlock.builder();
            excptionsTypes.addAll(fieldCodeGen.genInstallCode(blockBuilder));
            builder.addCode(blockBuilder.build());
        }

        for(Class execType : excptionsTypes) {
            builder.addException(execType);
        }

        classBuilder.addMethod(builder.build());

    }

    private void generateOtherMethods(TypeSpec.Builder classBuilder, ArrayList<FieldCodeGen> fields) {
        for(FieldCodeGen fieldCodeGen : fields) {
            if(fieldCodeGen.mField.type.annotations.contains(INDEX)) {
                fieldCodeGen.genOtherMethods(classBuilder);
            }
        }
    }

    static TypeName getTypeName(Nodes.TypeNode type) {

        TypeName retval = null;

        switch(type.type) {
            case Nodes.TypeNode.BOOL:
                retval = TypeName.BOOLEAN;
                break;

            case Nodes.TypeNode.CHAR:
                retval = TypeName.CHAR;
                break;

            case Nodes.TypeNode.STRING:
                retval = ClassName.get(String.class);
                break;

            case Nodes.TypeNode.INT: {
                Nodes.NumberType numberType = (Nodes.NumberType) type;
                switch(numberType.size) {
                    case 8:
                        retval = TypeName.BYTE;
                        break;
                    case 16:
                        retval = TypeName.SHORT;
                        break;
                    case 32:
                        retval = TypeName.INT;
                        break;
                    case 64:
                        retval = TypeName.LONG;
                        break;
                }
            } break;

            case Nodes.TypeNode.FLOAT: {
                Nodes.NumberType numberType = (Nodes.NumberType) type;
                switch (numberType.size) {
                    case 32:
                        retval = TypeName.FLOAT;
                        break;
                    case 64:
                        retval = TypeName.DOUBLE;
                        break;
                }
            } break;

            case Nodes.TypeNode.DBO: {
                Nodes.ObjType objType = ((Nodes.ObjType)type);
                if("UBObject".equals(objType.mSimpleName)){
                    retval = UBOBJECT_CLASSNAME;
                } else {
                    retval = ((Nodes.ObjType) type).getClassName();
                }
            } break;


        }

        if(type.isArray) {
            retval = ArrayTypeName.of(retval);
        }

        return retval;
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
                    .addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED)
                    .returns(TypeName.VOID)
                    .addParameter(getTypeName(mField.type), "value")
                    .addStatement("this.$L = value", mField.name)
                    .addStatement("setDirty()")
                    .build();
        }

        public Set<Class<? extends Exception>> genInstallCode(CodeBlock.Builder codeBuilder) {
            return Collections.emptySet();
        }

        public void genOtherMethods(TypeSpec.Builder classBuilder) {

        }
    }

    class BoolFieldCodeGen extends FieldCodeGen {

        BoolFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isBool())");
            methodBuilder.addStatement("this.$L = value.asBool()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createBool($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class BoolArrayFieldCodeGen extends FieldCodeGen {

        BoolArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asBoolArray()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class ByteFieldCodeGen extends FieldCodeGen {

        ByteFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isNumber())");
            methodBuilder.addStatement("this.$L = value.asByte()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class ByteArrayFieldCodeGen extends FieldCodeGen {

        ByteArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asByteArray()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class ShortFieldCodeGen extends FieldCodeGen {

        ShortFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isNumber())");
            methodBuilder.addStatement("this.$L = value.asShort()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class ShortArrayFieldCodeGen extends FieldCodeGen {

        ShortArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asShortArray()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class CharFieldCodeGen extends FieldCodeGen {

        CharFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isChar())");
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
            methodBuilder.beginControlFlow("if (value != null && value.isNumber())");
            methodBuilder.addStatement("this.$L = value.asInt()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }

        @Override
        public Set<Class<? extends Exception>> genInstallCode(CodeBlock.Builder codeBuilder) {
            Set<Class<? extends Exception>> retval = new HashSet<Class<? extends Exception>>();

            if(mField.type.annotations.contains(INDEX)) {
                ClassName thisClassName = getThisClassName();
                final String indexName = String.format("%s.%s_idx", thisClassName.simpleName(), mField.name);
                codeBuilder.add("db.addIndex($S, new $T<$T>() {\n", indexName, MapFunction.class, Integer.class);
                codeBuilder.indent();
                codeBuilder.add("@$T\npublic void map($T value, $T<$T> emitter) {\n", Override.class, UBValue.class, Emitter.class, Integer.class);
                codeBuilder.indent();
                codeBuilder.beginControlFlow("if ($T.isValidObject(value, $T.SUBTYPES))", Utils.class, thisClassName);
                codeBuilder.addStatement("$T v = value.asObject().get($S)", UBValue.class, mField.name);
                codeBuilder.beginControlFlow("if (v != null && v.isInteger())");
                codeBuilder.addStatement("emitter.emit(v.asInt())");
                codeBuilder.endControlFlow();
                codeBuilder.endControlFlow();
                codeBuilder.unindent();
                codeBuilder.add("}\n");
                codeBuilder.unindent();
                codeBuilder.addStatement("})");
                codeBuilder.build();
                retval.add(IOException.class);

            }

            return retval;
        }

        public void genOtherMethods(TypeSpec.Builder classBuilder) {

            ClassName thisClassName = getThisClassName();
            final String indexName = String.format("%s.%s_idx", thisClassName.simpleName(), mField.name);

            String queryByIndexMethodName = String.format("queryBy%s%sIndex", mField.name.substring(0, 1).toUpperCase(),
                    mField.name.substring(1));
            classBuilder.addMethod(MethodSpec.methodBuilder(queryByIndexMethodName)
                            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                            .returns(Cursor.class)
                            .addParameter(MicroDB.class, "db")
                            .addParameter(Integer.class, "min")
                            .addParameter(boolean.class, "includeMin")
                            .addParameter(Integer.class, "max")
                            .addParameter(boolean.class, "includeMax")
                            .addException(IOException.class)
                            .addStatement("return db.queryIndex($S, min, includeMin, max, includeMax)", indexName)
                            .build()
            );

        }
    }

    class IntArrayFieldCodeGen extends FieldCodeGen {

        IntArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asInt32Array()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class LongFieldCodeGen extends FieldCodeGen {

        LongFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isNumber())");
            methodBuilder.addStatement("this.$L = value.asLong()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createInt($L))", mField.name, UBValueFactory.class, mField.name);

        }

        @Override
        public Set<Class<? extends Exception>> genInstallCode(CodeBlock.Builder codeBuilder) {
            Set<Class<? extends Exception>> retval = new HashSet<Class<? extends Exception>>();

            if(mField.type.annotations.contains(AUTOINCREMENT)) {

                ClassName thisClassName = getThisClassName();
                final String incrementField = String.format("%s.%s_var", thisClassName.simpleName(), mField.name);

                codeBuilder.add("db.addChangeListener(new $T() {\n", DefaultChangeListener.class);
                codeBuilder.indent();
                codeBuilder.add("@$T\npublic void onBeforeInsert($T driver, $T value) {\n", Override.class, Driver.class, UBValue.class);
                codeBuilder.indent();
                codeBuilder.beginControlFlow("if($T.isValidObject(value, $T.SUBTYPES))", Utils.class, thisClassName);
                codeBuilder.addStatement("final long longValue = driver.incrementLongField($S)", incrementField);
                codeBuilder.addStatement("value.asObject().put($S, $T.createInt(longValue))", mField.name, UBValueFactory.class);
                codeBuilder.endControlFlow();
                codeBuilder.unindent();
                codeBuilder.add("}\n");
                codeBuilder.unindent();
                codeBuilder.addStatement("})");
                codeBuilder.build();
                retval.add(IOException.class);
            }

            if(mField.type.annotations.contains(INDEX)) {
                ClassName thisClassName = getThisClassName();
                final String indexName = String.format("%s.%s_idx", thisClassName.simpleName(), mField.name);
                codeBuilder.add("db.addIndex($S, new $T<$T>() {\n", indexName, MapFunction.class, Long.class);
                codeBuilder.indent();
                codeBuilder.add("@$T\npublic void map($T value, $T<$T> emitter) {\n", Override.class, UBValue.class, Emitter.class, Long.class);
                codeBuilder.indent();
                codeBuilder.beginControlFlow("if ($T.isValidObject(value, $T.SUBTYPES))", Utils.class, thisClassName);
                codeBuilder.addStatement("$T v = value.asObject().get($S)", UBValue.class, mField.name);
                codeBuilder.beginControlFlow("if (v != null && v.isInteger())");
                codeBuilder.addStatement("emitter.emit(v.asLong())");
                codeBuilder.endControlFlow();
                codeBuilder.endControlFlow();
                codeBuilder.unindent();
                codeBuilder.add("}\n");
                codeBuilder.unindent();
                codeBuilder.addStatement("})");
                codeBuilder.build();
                retval.add(IOException.class);

            }

            return retval;

        }

        public void genOtherMethods(TypeSpec.Builder classBuilder) {

            ClassName thisClassName = getThisClassName();
            final String indexName = String.format("%s.%s_idx", thisClassName.simpleName(), mField.name);

            String queryByIndexMethodName = String.format("queryBy%s%sIndex", mField.name.substring(0, 1).toUpperCase(),
                    mField.name.substring(1));
            classBuilder.addMethod(MethodSpec.methodBuilder(queryByIndexMethodName)
                            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                            .returns(Cursor.class)
                            .addParameter(MicroDB.class, "db")
                            .addParameter(Long.class, "min")
                            .addParameter(boolean.class, "includeMin")
                            .addParameter(Long.class, "max")
                            .addParameter(boolean.class, "includeMax")
                            .addException(IOException.class)
                            .addStatement("return db.queryIndex($S, min, includeMin, max, includeMax)", indexName)
                            .build()
            );

        }
    }

    class LongArrayFieldCodeGen extends FieldCodeGen {

        LongArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asInt64Array()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class FloatFieldCodeGen extends FieldCodeGen {

        FloatFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isNumber())");
            methodBuilder.addStatement("this.$L = value.asFloat32()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createFloat32($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class FloatArrayFieldCodeGen extends FieldCodeGen {

        FloatArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asFloat32Array()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class DoubleFieldCodeGen extends FieldCodeGen {

        DoubleFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isNumber())");
            methodBuilder.addStatement("this.$L = value.asFloat64()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createFloat64($L))", mField.name, UBValueFactory.class, mField.name);

        }
    }

    class DoubleArrayFieldCodeGen extends FieldCodeGen {

        DoubleArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asFloat64Array()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

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

        @Override
        public Set<Class<? extends Exception>> genInstallCode(CodeBlock.Builder codeBuilder) {
            Set<Class<? extends Exception>> retval = new HashSet<Class<? extends Exception>>();

            if(mField.type.annotations.contains(INDEX)) {
                ClassName thisClassName = getThisClassName();
                final String indexName = String.format("%s.%s_idx", thisClassName.simpleName(), mField.name);
                codeBuilder.add("db.addIndex($S, new $T<$T>() {\n", indexName, MapFunction.class, String.class);
                codeBuilder.indent();
                codeBuilder.add("@$T\npublic void map($T value, $T<$T> emitter) {\n", Override.class, UBValue.class, Emitter.class, String.class);
                codeBuilder.indent();
                codeBuilder.beginControlFlow("if ($T.isValidObject(value, $T.SUBTYPES))", Utils.class, thisClassName);
                codeBuilder.addStatement("$T v = value.asObject().get($S)", UBValue.class, mField.name);
                codeBuilder.beginControlFlow("if (v != null && v.isString())");
                codeBuilder.addStatement("emitter.emit(v.asString())");
                codeBuilder.endControlFlow();
                codeBuilder.endControlFlow();
                codeBuilder.unindent();
                codeBuilder.add("}\n");
                codeBuilder.unindent();
                codeBuilder.addStatement("})");
                codeBuilder.build();
                retval.add(IOException.class);

            }
            return retval;
        }

        public void genOtherMethods(TypeSpec.Builder classBuilder) {

            ClassName thisClassName = getThisClassName();
            final String indexName = String.format("%s.%s_idx", thisClassName.simpleName(), mField.name);

            String queryByIndexMethodName = String.format("queryBy%s%sIndex", mField.name.substring(0, 1).toUpperCase(),
                    mField.name.substring(1));
            classBuilder.addMethod(MethodSpec.methodBuilder(queryByIndexMethodName)
                            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                            .returns(Cursor.class)
                            .addParameter(MicroDB.class, "db")
                            .addParameter(String.class, "min")
                            .addParameter(boolean.class, "includeMin")
                            .addParameter(String.class, "max")
                            .addParameter(boolean.class, "includeMax")
                            .addException(IOException.class)
                            .addStatement("return db.queryIndex($S, min, includeMin, max, includeMax)", indexName)
                            .build()
            );

        }
    }

    class StringArrayFieldCodeGen extends FieldCodeGen {

        StringArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("this.$L = value.asStringArray()", mField.name);
            methodBuilder.endControlFlow();


        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder
                    .addStatement("obj.put($S, $T.createArrayOrNull($L))", mField.name, UBValueFactory.class, mField.name);

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
            methodBuilder.addStatement("this.$L = $T.readDBObj(db, value, new $T())",
                    mField.name, Utils.class, mClassName);
            methodBuilder.nextControlFlow("else");
            methodBuilder.addStatement("this.$L = null", mField.name);
            methodBuilder.endControlFlow();
        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("obj.put($S, $T.writeDBObj(db, $L))",
                    mField.name, Utils.class, mField.name);

        }
    }

    class DBOArrayFieldCodeGen extends FieldCodeGen {

        private final ClassName mClassName;

        DBOArrayFieldCodeGen(Nodes.FieldNode field) {
            super(field);
            mClassName = ((Nodes.ObjType)field.type).getClassName();
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("value = obj.get($S)", mField.name);
            methodBuilder.beginControlFlow("if (value != null && value.isArray())");
            methodBuilder.addStatement("$T array = value.asArray()", UBArray.class);
            methodBuilder.addStatement("final int size = array.size()");
            methodBuilder.addStatement("this.$L = new $T[size]", mField.name, mClassName);
            methodBuilder.beginControlFlow("for (int i=0;i<size;i++)");
            methodBuilder.addStatement("this.$L[i] = $T.readDBObj(db, array.get(i), new $T())", mField.name, Utils.class, mClassName);
            methodBuilder.endControlFlow();
            methodBuilder.nextControlFlow("else");
            methodBuilder.addStatement("this.$L = null", mField.name);
            methodBuilder.endControlFlow();
        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder methodBuilder) {
            methodBuilder.addStatement("obj.put($S, $T.createArrayOrNull(db, $L))",
                    mField.name, Utils.class, mField.name);

        }
    }

    class UBObjectFieldCodeGen extends FieldCodeGen {

        UBObjectFieldCodeGen(Nodes.FieldNode field) {
            super(field);
        }

        @Override
        void genReadFromUBObject(MethodSpec.Builder builder) {
            builder.addStatement("value = obj.get($S)", mField.name);
            builder.beginControlFlow("if (value != null && value.isObject())");
            builder.addStatement("this.$L = value.asObject()", mField.name);
            builder.endControlFlow();
        }

        @Override
        void genWriteToUBObject(MethodSpec.Builder builder) {
            builder.addStatement("obj.put($S, $L != null ? $L : $T.createNull())",
                    mField.name, mField.name, mField.name, UBValueFactory.class);

        }
    }



}
