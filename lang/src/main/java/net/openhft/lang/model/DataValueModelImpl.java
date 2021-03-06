/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.lang.model;

import net.openhft.lang.io.serialization.BytesMarshallable;
import net.openhft.lang.model.constraints.Digits;
import net.openhft.lang.model.constraints.MaxSize;
import net.openhft.lang.model.constraints.Range;

import java.io.Externalizable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * User: peter.lawrey
 * Date: 06/10/13
 * Time: 17:23
 */
public class DataValueModelImpl<T> implements DataValueModel<T> {
    static final Map<Class, Integer> HEAP_SIZE_MAP = new HashMap<Class, Integer>();

    static {
        HEAP_SIZE_MAP.put(boolean.class, 1);
        HEAP_SIZE_MAP.put(byte.class, 8);
        HEAP_SIZE_MAP.put(char.class, 16);
        HEAP_SIZE_MAP.put(short.class, 16);
        HEAP_SIZE_MAP.put(int.class, 32);
        HEAP_SIZE_MAP.put(float.class, 32);
        HEAP_SIZE_MAP.put(long.class, 64);
        HEAP_SIZE_MAP.put(double.class, 64);
    }

    private final Map<String, FieldModelImpl> fieldModelMap = new TreeMap<String, FieldModelImpl>();
    private final Class<T> type;
    private final Map<Class, DataValueModel> nestedMap = new HashMap<Class, DataValueModel>();

    public DataValueModelImpl(Class<T> type) {
        this.type = type;
        if (!type.isInterface())
            throw new IllegalArgumentException("type must be an interface, was " + type);

        Method[] methods = type.getMethods();
        for (Method method : methods) {
            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass == Object.class
                    || declaringClass == Externalizable.class
                    || declaringClass == BytesMarshallable.class
                    || declaringClass == Copyable.class
                    || declaringClass == Byteable.class)
                continue;
            String name = method.getName();
            Class<?>[] parameterTypes = method.getParameterTypes();
            final Class<?> returnType = method.getReturnType();
            switch (parameterTypes.length) {
                case 0: {
                    String name5 = getUnlock(name);
                    if (name5 != null && returnType == void.class) {
                        FieldModelImpl fm = acquireField(name5);
                        fm.unlock(method);
                        break;
                    }
                    String name4 = getBusyLock(name);
                    if (name4 != null && returnType == void.class) {
                        FieldModelImpl fm = acquireField(name4);
                        fm.busyLock(method);
                        break;
                    }
                    String name3 = getTryLock(name);
                    if (name3 != null && returnType == boolean.class) {
                        FieldModelImpl fm = acquireField(name3);
                        fm.tryLock(method);
                        break;
                    }
                    if (returnType == void.class)
                        throw new IllegalArgumentException("void () not supported " + method);
                    String name2 = getGetter(name, returnType);
                    FieldModelImpl fm = acquireField(name2);
                    fm.getter(method);
                    break;
                }
                case 1: {
                    String name5 = getTryLockNanos(name);
                    if (name5 != null && returnType == boolean.class) {
                        FieldModelImpl fm = acquireField(name5);
                        fm.tryLockNanos(method);
                        break;
                    }

                    String name4 = getAtomicAdder(name);
                    if (name4 != null) {
                        FieldModelImpl fm = acquireField(name4);
                        fm.atomicAdder(method);
                        break;
                    }

                    String name3 = getAdder(name);
                    if (name3 != null) {
                        FieldModelImpl fm = acquireField(name3);
                        fm.adder(method);
                        break;
                    }

                    if (returnType != void.class)
                        throw new IllegalArgumentException("setter must be void " + method);
                    String name2 = getSetter(name);
                    FieldModelImpl fm = acquireField(name2);
                    fm.setter(method);
                    break;
                }
                case 2:
                    String name2 = getCAS(name);
                    if (name2 != null && returnType == boolean.class) {
                        FieldModelImpl fm = acquireField(name2);
                        fm.cas(method);
                        break;
                    }

                default: {
                    throw new IllegalArgumentException("method not supported " + method);
                }
            }
        }
        for (Map.Entry<String, FieldModelImpl> entry : fieldModelMap.entrySet()) {
            FieldModelImpl model = entry.getValue();
            if (model.getter() == null || (model.setter() == null && model.getter().getReturnType().isPrimitive()))
                throw new IllegalArgumentException("Field " + entry.getKey() + " must have a getter and setter.");
            Class ftype = model.type();
            if (!isScalar(ftype) && !nestedMap.containsKey(ftype))
                nestedMap.put(ftype, new DataValueModelImpl(ftype));
        }
    }

    private static String getCAS(String name) {
        final int len = 14;
        if (name.length() > len && name.startsWith("compareAndSwap") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private static String getAtomicAdder(String name) {
        final int len = 9;
        if (name.length() > len && name.startsWith("addAtomic") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private static String getAdder(String name) {
        final int len = 3;
        if (name.length() > len && name.startsWith("add") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private static String getSetter(String name) {
        final int len = 3;
        if (name.length() > len && name.startsWith("set") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return name;
    }

    private static String getGetter(String name, Class returnType) {
        if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3)))
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        if ((returnType == boolean.class || returnType == Boolean.class)
                && name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2)))
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        return name;
    }

    private String getBusyLock(String name) {
        final int len = 8;
        if (name.length() > len && name.startsWith("busyLock") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private String getUnlock(String name) {
        final int len = 6;
        if (name.length() > len && name.startsWith("unlock") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private String getTryLockNanos(String name) {
        final int len = 12;
        if (name.length() > len && name.startsWith("tryLockNanos") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private String getTryLock(String name) {
        final int len = 7;
        if (name.length() > len && name.startsWith("tryLock") && Character.isUpperCase(name.charAt(len)))
            return Character.toLowerCase(name.charAt(len)) + name.substring(len + 1);
        return null;
    }

    private FieldModelImpl acquireField(String name) {
        FieldModelImpl fieldModelImpl = fieldModelMap.get(name);
        if (fieldModelImpl == null)
            fieldModelMap.put(name, fieldModelImpl = new FieldModelImpl(name));

        return fieldModelImpl;
    }

    @Override
    public Map<String, ? extends FieldModel> fieldMap() {
        return fieldModelMap;
    }

    public boolean isScalar(Class type) {
        return type.isPrimitive() || CharSequence.class.isAssignableFrom(type);
    }

    @Override
    public Set<Class> nestedModels() {
        return nestedMap.keySet();
    }

    @Override
    public <N> DataValueModel<N> nestedModel(Class<N> nClass) {
        @SuppressWarnings("unchecked")
        DataValueModel<N> model = (DataValueModel<N>) (nClass == type ? this : nestedMap.get(nClass));
        return model;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    static class FieldModelImpl<T> implements FieldModel<T> {

        private final String name;
        private Method getter, setter;
        private Digits digits;
        private Range range;
        private MaxSize maxSize;
        private Method adder;
        private Method atomicAdder;
        private Method cas;
        private Method tryLockNanos;
        private Method tryLock;
        private Method busyLock;
        private Method unlock;

        public FieldModelImpl(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public void getter(Method getter) {
            this.getter = getter;
        }

        public Method getter() {
            return getter;
        }

        public void setter(Method setter) {
            this.setter = setter;
            for (Annotation a : setter.getParameterAnnotations()[0]) {
                if (a instanceof Digits)
                    digits = (Digits) a;
                if (a instanceof Range)
                    range = (Range) a;
                if (a instanceof MaxSize)
                    maxSize = (MaxSize) a;
            }
        }

        public Method setter() {
            return setter;
        }

        @Override
        public Class<T> type() {
            return (Class<T>) getter.getReturnType();
        }

        public void adder(Method method) {
            adder = method;
        }

        public Method adder() {
            return adder;
        }

        @Override
        public int heapSize() {
            Integer size = HEAP_SIZE_MAP.get(type());
            if (size == null) return -1;
            return size;
        }

        // maxSize in bits.
        @Override
        public int nativeSize() {
            Integer size = HEAP_SIZE_MAP.get(type());
            if (size != null)
                return size;
            MaxSize maxSize2 = size();
            if (maxSize2 == null)
                throw new AssertionError(type() + " without a @MaxSize not supported for native types");
            return maxSize2.value() << 3;
        }

        @Override
        public Digits digits() {
            return digits;
        }

        @Override
        public Range range() {
            return range;
        }

        @Override
        public MaxSize size() {
            return maxSize;
        }

        @Override
        public String toString() {
            return "FieldModel{" +
                    "name='" + name + '\'' +
                    ", getter=" + getter +
                    ", setter=" + setter +
                    (digits == null ? "" : ", digits= " + digits) +
                    (range == null ? "" : ", range= " + range) +
                    (maxSize == null ? "" : ", size= " + maxSize) +
                    '}';
        }

        public void atomicAdder(Method method) {
            atomicAdder = method;
        }

        public Method atomicAdder() {
            return atomicAdder;
        }

        public void cas(Method method) {
            cas = method;
        }

        public Method cas() {
            return cas;
        }

        public void tryLockNanos(Method method) {
            tryLockNanos = method;
        }

        public Method tryLockNanos() {
            return tryLockNanos;
        }

        public void tryLock(Method tryLock) {
            this.tryLock = tryLock;
        }

        public Method tryLock() {
            return tryLock;
        }

        public void busyLock(Method busyLock) {
            this.busyLock = busyLock;
        }

        public Method busyLock() {
            return busyLock;
        }

        public void unlock(Method unlock) {
            this.unlock = unlock;
        }

        public Method unlock() {
            return unlock;
        }
    }
}
