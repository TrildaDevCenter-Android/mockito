/*
 * Copyright (c) 2007 Mockito contributors
 * This program is made available under the terms of the MIT License.
 */
package org.mockito.internal.util.reflection;

import org.mockito.exceptions.base.MockitoException;
import org.mockito.internal.configuration.plugins.Plugins;
import org.mockito.internal.util.MockUtil;
import org.mockito.plugins.MemberAccessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static java.lang.reflect.Modifier.isStatic;

/**
 * Initialize a field with type instance if a default constructor can be found.
 *
 * <p>
 * If the given field is already initialized, then <strong>the actual instance is returned</strong>.
 * This initializer doesn't work with inner classes, local classes, interfaces or abstract types.
 * </p>
 *
 */
public class FieldInitializer {

    private final Object fieldOwner;
    private final Field field;
    private final ConstructorInstantiator instantiator;

    /**
     * Prepare initializer with the given field on the given instance.
     *
     * <p>
     * This constructor fail fast if the field type cannot be handled.
     * </p>
     *
     * @param fieldOwner Instance of the test.
     * @param field Field to be initialize.
     */
    public FieldInitializer(Object fieldOwner, Field field) {
        this(fieldOwner, field, new NoArgConstructorInstantiator(fieldOwner, field));
    }

    /**
     * Prepare initializer with the given field on the given instance.
     *
     * <p>
     * This constructor fail fast if the field type cannot be handled.
     * </p>
     *
     * @param fieldOwner Instance of the test.
     * @param field Field to be initialize.
     * @param argResolver Constructor parameters resolver
     */
    public FieldInitializer(
            Object fieldOwner, Field field, ConstructorArgumentResolver argResolver) {
        this(
                fieldOwner,
                field,
                new ParameterizedConstructorInstantiator(fieldOwner, field, argResolver));
    }

    private FieldInitializer(Object fieldOwner, Field field, ConstructorInstantiator instantiator) {
        if (new FieldReader(fieldOwner, field).isNull()) {
            checkNotLocal(field);
            checkNotInner(field);
            checkNotInterface(field);
            checkNotEnum(field);
            checkNotAbstract(field);
        }
        this.fieldOwner = fieldOwner;
        this.field = field;
        this.instantiator = instantiator;
    }

    /**
     * Initialize field if not initialized and return the actual instance.
     *
     * @return Actual field instance.
     */
    public FieldInitializationReport initialize() {
        try {
            return acquireFieldInstance();
        } catch (IllegalAccessException e) {
            throw new MockitoException(
                    "Problems initializing field '"
                            + field.getName()
                            + "' of type '"
                            + field.getType().getSimpleName()
                            + "'",
                    e);
        }
    }

    private void checkNotLocal(Field field) {
        if (field.getType().isLocalClass()) {
            throw new MockitoException(
                    "the type '" + field.getType().getSimpleName() + "' is a local class.");
        }
    }

    private void checkNotInner(Field field) {
        Class<?> type = field.getType();
        if (type.isMemberClass() && !isStatic(type.getModifiers())) {
            throw new MockitoException(
                    "the type '" + type.getSimpleName() + "' is an inner non static class.");
        }
    }

    private void checkNotInterface(Field field) {
        if (field.getType().isInterface()) {
            throw new MockitoException(
                    "the type '" + field.getType().getSimpleName() + "' is an interface.");
        }
    }

    private void checkNotAbstract(Field field) {
        if (Modifier.isAbstract(field.getType().getModifiers())) {
            throw new MockitoException(
                    "the type '" + field.getType().getSimpleName() + "' is an abstract class.");
        }
    }

    private void checkNotEnum(Field field) {
        if (field.getType().isEnum()) {
            throw new MockitoException(
                    "the type '" + field.getType().getSimpleName() + "' is an enum.");
        }
    }

    private FieldInitializationReport acquireFieldInstance() throws IllegalAccessException {
        final MemberAccessor accessor = Plugins.getMemberAccessor();
        Object fieldInstance = accessor.get(field, fieldOwner);
        if (fieldInstance != null) {
            return new FieldInitializationReport(fieldInstance, false, false);
        }

        return instantiator.instantiate();
    }

    /**
     * Represents the strategy used to resolve actual instances
     * to be given to a constructor given the argument types.
     */
    public interface ConstructorArgumentResolver {

        /**
         * Try to resolve instances from types.
         *
         * <p>
         * Checks on the real argument type or on the correct argument number
         * will happen during the field initialization {@link FieldInitializer#initialize()}.
         * I.e the only responsibility of this method, is to provide instances <strong>if possible</strong>.
         * </p>
         *
         * @param argTypes Constructor argument types, should not be null.
         * @return The argument instances to be given to the constructor, should not be null.
         */
        Object[] resolveTypeInstances(Class<?>... argTypes);
    }

    private interface ConstructorInstantiator {
        FieldInitializationReport instantiate();
    }

    /**
     * Constructor instantiating strategy for no-arg constructor.
     *
     * <p>
     * If a no-arg constructor can be found then the instance is created using
     * this constructor.
     * Otherwise a technical MockitoException is thrown.
     * </p>
     */
    static class NoArgConstructorInstantiator implements ConstructorInstantiator {
        private final Object testClass;
        private final Field field;

        /**
         * Internal, checks are done by FieldInitializer.
         * Fields are assumed to be accessible.
         */
        NoArgConstructorInstantiator(Object testClass, Field field) {
            this.testClass = testClass;
            this.field = field;
        }

        @Override
        public FieldInitializationReport instantiate() {
            final MemberAccessor invoker = Plugins.getMemberAccessor();
            try {
                Constructor<?> constructor = field.getType().getDeclaredConstructor();

                final Object[] noArg = new Object[0];
                Object newFieldInstance = invoker.newInstance(constructor, noArg);
                invoker.set(field, testClass, newFieldInstance);

                return new FieldInitializationReport(invoker.get(field, testClass), true, false);
            } catch (NoSuchMethodException e) {
                throw new MockitoException(
                        "the type '"
                                + field.getType().getSimpleName()
                                + "' has no default constructor",
                        e);
            } catch (InvocationTargetException e) {
                throw new MockitoException(
                        "the default constructor of type '"
                                + field.getType().getSimpleName()
                                + "' has raised an exception (see the stack trace for cause): "
                                + e.getTargetException(),
                        e);
            } catch (InstantiationException e) {
                throw new MockitoException(
                        "InstantiationException (see the stack trace for cause): " + e, e);
            } catch (IllegalAccessException e) {
                throw new MockitoException(
                        "IllegalAccessException (see the stack trace for cause): " + e, e);
            }
        }
    }

    /**
     * Constructor instantiating strategy for parameterized constructors.
     *
     * <p>
     * Choose the constructor with the highest number of parameters, then
     * call the ConstructorArgResolver to get actual argument instances.
     * If the argResolver fail, then a technical MockitoException is thrown is thrown.
     * Otherwise the instance is created with the resolved arguments.
     * </p>
     */
    static class ParameterizedConstructorInstantiator implements ConstructorInstantiator {
        private final Object testClass;
        private final Field field;
        private final ConstructorArgumentResolver argResolver;
        private final Comparator<Constructor<?>> byParameterNumber =
                new Comparator<Constructor<?>>() {
                    @Override
                    public int compare(Constructor<?> constructorA, Constructor<?> constructorB) {
                        int argLengths =
                                constructorB.getParameterTypes().length
                                        - constructorA.getParameterTypes().length;
                        if (argLengths == 0) {
                            int constructorAMockableParamsSize = countMockableParams(constructorA);
                            int constructorBMockableParamsSize = countMockableParams(constructorB);
                            return constructorBMockableParamsSize - constructorAMockableParamsSize;
                        }
                        return argLengths;
                    }

                    private int countMockableParams(Constructor<?> constructor) {
                        int constructorMockableParamsSize = 0;
                        for (Class<?> aClass : constructor.getParameterTypes()) {
                            // The argResolver already knows the concrete types it can provide.
                            // Instead of checking for mockability, I think it would be better to
                            // ask the argResolver whether it can resolve this type.
                            // Anyway, I keep it for now to avoid breaking any existing code.
                            if (MockUtil.typeMockabilityOf(aClass, null).mockable()) {
                                constructorMockableParamsSize++;
                            }
                        }
                        return constructorMockableParamsSize;
                    }
                };

        /**
         * Internal, checks are done by FieldInitializer.
         * Fields are assumed to be accessible.
         */
        ParameterizedConstructorInstantiator(
                Object testClass, Field field, ConstructorArgumentResolver argumentResolver) {
            this.testClass = testClass;
            this.field = field;
            this.argResolver = argumentResolver;
        }

        @Override
        public FieldInitializationReport instantiate() {
            final MemberAccessor accessor = Plugins.getMemberAccessor();
            Constructor<?> constructor = biggestConstructor(field.getType());
            final Object[] args = argResolver.resolveTypeInstances(constructor.getParameterTypes());
            try {
                Object newFieldInstance = accessor.newInstance(constructor, args);
                accessor.set(field, testClass, newFieldInstance);

                return new FieldInitializationReport(accessor.get(field, testClass), false, true);
            } catch (IllegalArgumentException e) {
                throw new MockitoException(
                        "internal error : argResolver provided incorrect types for constructor "
                                + constructor
                                + " of type "
                                + field.getType().getSimpleName(),
                        e);
            } catch (InvocationTargetException e) {
                throw new MockitoException(
                        "the constructor of type '"
                                + field.getType().getSimpleName()
                                + "' has raised an exception (see the stack trace for cause): "
                                + e.getTargetException(),
                        e);
            } catch (InstantiationException e) {
                throw new MockitoException(
                        "InstantiationException (see the stack trace for cause): " + e, e);
            } catch (IllegalAccessException e) {
                throw new MockitoException(
                        "IllegalAccessException (see the stack trace for cause): " + e, e);
            }
        }

        private void checkParameterized(Constructor<?> constructor, Field field) {
            if (constructor.getParameterTypes().length == 0) {
                throw new MockitoException(
                        "the field "
                                + field.getName()
                                + " of type "
                                + field.getType()
                                + " has no parameterized constructor");
            }
        }

        private Constructor<?> biggestConstructor(Class<?> clazz) {
            final List<? extends Constructor<?>> constructors =
                    Arrays.asList(clazz.getDeclaredConstructors());
            Collections.sort(constructors, byParameterNumber);

            Constructor<?> constructor = constructors.get(0);
            checkParameterized(constructor, field);
            return constructor;
        }
    }
}