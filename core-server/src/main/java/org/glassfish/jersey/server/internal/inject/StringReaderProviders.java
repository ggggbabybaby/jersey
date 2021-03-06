/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.internal.inject;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.util.Date;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;

import org.glassfish.hk2.inject.Injector;
import org.glassfish.jersey.internal.ExtractorException;
import org.glassfish.jersey.internal.ProcessingException;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.message.internal.HttpDateFormat;
import org.glassfish.jersey.spi.StringValueReader;
import org.glassfish.jersey.spi.StringValueReaderProvider;

/**
 * Container of several different {@link StringValueReaderProvider string reader provider}
 * implementations. The nested provider implementations encapsulate various different
 * strategies of constructing an instance from a {@code String} value.
 *
 * @author Paul Sandoz
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
class StringReaderProviders {

    private static abstract class AbstractStringReader<T> implements StringValueReader<T> {

        @Override
        public T fromString(String value) {
            try {
                return _fromString(value);
            } catch (InvocationTargetException ex) {
                // if the value is an empty string, return null
                if (value.length() == 0) {
                    return null;
                }
                Throwable target = ex.getTargetException();
                if (target instanceof WebApplicationException) {
                    throw (WebApplicationException) target;
                } else {
                    throw new ExtractorException(target);
                }
            } catch (Exception ex) {
                throw new ProcessingException(ex);
            }
        }

        protected abstract T _fromString(String value) throws Exception;
    }

    /**
     * Provider of string readers that produce the target Java type instance
     * by invoking a single {@code String} parameter constructor on the target type.
     */
    public static class StringConstructor implements StringValueReaderProvider {

        @Override
        public <T> StringValueReader<T> getStringReader(final Class<T> type, Type genericType, Annotation[] annotations) {
            final Constructor constructor = ReflectionHelper.getStringConstructor(type);

            return (constructor == null) ? null : new AbstractStringReader<T>() {

                @Override
                protected T _fromString(String value) throws Exception {
                    return type.cast(constructor.newInstance(value));
                }
            };
        }
    }

    /**
     * Provider of string readers that produce the target Java type instance
     * by invoking a static {@code valueOf(String)} method on the target type.
     */
    public static class TypeValueOf implements StringValueReaderProvider {

        @Override
        public <T> StringValueReader<T> getStringReader(final Class<T> type, Type genericType, Annotation[] annotations) {
            final Method valueOf = ReflectionHelper.getValueOfStringMethod(type);

            return (valueOf == null) ? null : new AbstractStringReader<T>() {

                @Override
                public T _fromString(String value) throws Exception {
                    return type.cast(valueOf.invoke(null, value));
                }
            };
        }
    }

    /**
     * Provider of string readers that produce the target Java type instance
     * by invoking a static {@code fromString(String)} method on the target type.
     */
    public static class TypeFromString implements StringValueReaderProvider {

        @Override
        public <T> StringValueReader<T> getStringReader(final Class<T> type, Type genericType, Annotation[] annotations) {
            final Method fromStringMethod = ReflectionHelper.getFromStringStringMethod(type);

            return (fromStringMethod == null) ? null : new AbstractStringReader<T>() {

                @Override
                public T _fromString(String value) throws Exception {
                    return type.cast(fromStringMethod.invoke(null, value));
                }
            };
        }
    }

    /**
     * Provider of string readers that produce the target Java {@link Enum enum} type instance
     * by invoking a static {@code fromString(String)} method on the target enum type.
     */
    public static class TypeFromStringEnum extends TypeFromString {

        @Override
        public <T> StringValueReader<T> getStringReader(Class<T> type, Type genericType, Annotation[] annotations) {

            return (!Enum.class.isAssignableFrom(type)) ? null : super.getStringReader(type, genericType, annotations);
        }
    }

    /**
     * Provider of string readers that convert the supplied string into a Java
     * {@link Date} instance using conversion method from the
     * {@link HttpDateFormat http date formatter} utility class.
     */
    public static class DateProvider implements StringValueReaderProvider {

        @Override
        public <T> StringValueReader<T> getStringReader(final Class<T> type, Type genericType, Annotation[] annotations) {
            return (type != Date.class) ? null : new StringValueReader<T>() {

                @Override
                public T fromString(String value) {
                    try {
                        return type.cast(HttpDateFormat.readDate(value));
                    } catch (ParseException ex) {
                        throw new ExtractorException(ex);
                    }
                }
            };
        }
    }

    public static class AggregatedProvider implements StringValueReaderProvider {

        final StringValueReaderProvider[] providers;

        public AggregatedProvider(@Context Injector injector) {
            providers = new StringValueReaderProvider[]{
                injector.inject(TypeFromStringEnum.class),
                injector.inject(TypeValueOf.class),
                injector.inject(TypeFromString.class),
                injector.inject(StringConstructor.class),
                injector.inject(DateProvider.class),
                injector.inject(JaxbStringReaderProvider.RootElementProvider.class)
            };
        }

        @Override
        public <T> StringValueReader<T> getStringReader(Class<T> type, Type genericType, Annotation[] annotations) {
            for (StringValueReaderProvider p : providers) {
                final StringValueReader<T> reader = p.getStringReader(type, genericType, annotations);
                if (reader != null) {
                    return reader;
                }
            }
            return null;
        }
    }
}
