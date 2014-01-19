/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.tomitribe.crest;


import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Required;
import org.tomitribe.crest.util.Converter;
import org.tomitribe.crest.val.BeanValidation;
import org.tomitribe.util.Join;
import org.tomitribe.util.collect.ObjectMap;
import org.tomitribe.util.reflect.Generics;
import org.tomitribe.util.reflect.Parameter;
import org.tomitribe.util.reflect.Reflection;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * @version $Revision$ $Date$
 */
public class CmdMethod implements Cmd {

    private static final String LIST_SEPARATOR = "\u0000";
    private static final String LIST_TYPE = "\uFFFF￿\uFFFF￿";
    private final Target target;
    private final Method method;
    private final String name;
    private final List<OptionParameter> optionParameters;
    private final List<Parameter> argumentParameters;

    public CmdMethod(Object bean, Method method) {
        this(method, new SimpleBean(bean));
    }

    public CmdMethod(Method method, Target target) {
        this.target = target;
        this.method = method;
        this.name = name(method);

        final List<OptionParameter> options = new ArrayList<OptionParameter>();
        final List<Parameter> arguments = new ArrayList<Parameter>();
        for (Parameter parameter : Reflection.params(method)) {
            if (parameter.isAnnotationPresent(Option.class)){
                options.add(new OptionParameter(parameter));
            } else {
                arguments.add(parameter);
            }
        }

        this.optionParameters = Collections.unmodifiableList(options);
        this.argumentParameters = Collections.unmodifiableList(arguments);

        validate();
    }

    public List<Parameter> getArgumentParameters() {
        return argumentParameters;
    }

    public static class OptionParameter extends Parameter {
        public OptionParameter(Parameter parameter) {
            super(parameter.getAnnotations(), parameter.getType(), parameter.getGenericType());
        }


    }

    private void validate() {
        validateOptions();
        validateArgs();
    }

    private void validateArgs() {
        for (Parameter param : argumentParameters) {
            if (param.isAnnotationPresent(Default.class)) {
                throw new IllegalArgumentException("@Default only usable with @Option parameters.");
            }
            if (param.isAnnotationPresent(Required.class)) {
                throw new IllegalArgumentException("@Required only usable with @Option parameters.");
            }
        }
    }

    private void validateOptions() {
        final Set<String> names = new HashSet<String>();
        for (Parameter param : optionParameters) {
            final Option option = param.getAnnotation(Option.class);
            if (!names.add(option.value())) throw new IllegalArgumentException("Duplicate option: " + option.value());
        }
    }

    private static String name(Method method) {
        final Command command = method.getAnnotation(Command.class);
        if (command == null) return method.getName();
        return value(command.value(), method.getName());
    }

    public CmdMethod(Method method) {
        this(null, method);
    }

    /**
     * Returns a single line description of the command
     * @return
     */
    @Override
    public String getUsage() {
        final String usage = usage();

        if (usage != null) {
            if (!usage.startsWith(name)) {
                return name + " " + usage;
            } else {
                return usage;
            }
        }

        final List<Object> args = new ArrayList<Object>();

        for (Parameter parameter : argumentParameters) {
            args.add(parameter.getType().getSimpleName());
        }

        return String.format("%s %s %s", name, args.size() == method.getParameterTypes().length ? "" : "[options]", Join.join(" ", args)).trim();
    }

    private String usage() {
        final Command command = method.getAnnotation(Command.class);
        if (command == null) return null;
        if ("".equals(command.usage())) return null;
        return command.usage();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object exec(String... rawArgs) {
        final List<Object> list;
        try {
            list = parse(rawArgs);
        } catch (Exception e) {
            reportWithHelp(e);
            throw toRuntimeException(e);
        }

        return exec(list);
    }

    public Object exec(List<Object> list) {
        final Object[] args;
        try {
            args = list.toArray();
            BeanValidation.validateParameters(method.getDeclaringClass(), method, args);
        } catch (Exception e) {
            reportWithHelp(e);
            throw toRuntimeException(e);
        }

        try {
            return target.invoke(method, args);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                reportWithHelp(e);
            }
            throw new CommandFailedException(cause);
        } catch (Throwable e) {
            throw toRuntimeException(e);
        }
    }

    private void reportWithHelp(Exception e) {
        if (e instanceof ConstraintViolationException) {
            final ConstraintViolationException cve = (ConstraintViolationException) e;
            for (ConstraintViolation<?> violation : cve.getConstraintViolations()) {
                System.err.println(violation.getMessage());
            }
        } else {
            System.err.println(e.getMessage());
        }
        help(System.err);
    }

    public static RuntimeException toRuntimeException(Throwable e) {
        if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        }
        return new IllegalArgumentException(e);
    }

    @Override
    public void help(PrintStream out) {
        out.println();
        out.print("Usage: ");
        out.println(getUsage());
        out.println();
        out.println("Options: ");
        out.printf("   %-20s   %s%n", "", "(default)");

        for (Map.Entry<String, String> entry : getDefaults().entrySet()) {
            if (entry instanceof ObjectMap.Member) {
                ObjectMap.Member<String, String> member = (ObjectMap.Member<String, String>) entry;
                out.printf("   --%-20s %s%n", entry.getKey() + "=<" + member.getType().getSimpleName() + ">", entry.getValue());
            } else {
                out.printf("   --%-20s %s%n", entry.getKey(), entry.getValue());
            }
        }
    }

    public List<Object> parse(String... rawArgs) {
        return convert(new Arguments(rawArgs));
    }

    private <T> List<Object> convert(Arguments args) {

        final Map<String, String> options = args.options;
        final List<String> list = args.list;

        final List<Object> converted = new ArrayList<Object>();

        /**
         * Here we iterate over the method's parameters and convert
         * strings into their equivalent Option or Arg value.
         *
         * The result is a List of objects that matches perfectly
         * the list of arguments required to pass into the
         * java.lang.reflect.Method.invoke() method.
         *
         * Thus, iteration order is very significant in this loop.
         */
        for (Parameter parameter : Reflection.params(method)) {
            final Option option = parameter.getAnnotation(Option.class);

            if (option != null) {

                final String value = options.remove(option.value());


                if (isListable(parameter)) {

                    converted.add(convert(parameter, getSeparatedValues(value), option.value()));

                } else {

                    converted.add(Converter.convert(value, parameter.getType(), option.value()));

                }

            } else if (list.size() > 0) {

                if (isListable(parameter)) {

                    converted.add(convert(parameter, list, null));

                    list.clear();

                } else {

                    final String value = list.remove(0);
                    converted.add(Converter.convert(value, parameter.getType(), "[" + parameter.getType().getSimpleName() + "]"));
                }

            } else {

                throw new IllegalArgumentException("Missing argument [" + parameter.getType().getSimpleName() + "]");
            }
        }

        if (list.size() > 0) {
            throw new IllegalArgumentException("Excess arguments: " + Join.join(", ", list));
        }

        if (options.size() > 0) {
            throw new IllegalArgumentException("Unknown arguments: " + Join.join(", ", new Join.NameCallback() {
                @Override
                public String getName(Object object) {
                    return "--" + object;
                }
            }, options.keySet()));
        }

        return converted;
    }

    private static Object convert(final Parameter parameter, final List<String> values, final String name) {
        final Class<?> type = getListableType(parameter);

        final String description = name == null ? "[" + type.getSimpleName() + "]" : name;

        if (parameter.getType().isArray()) {

            final Object array = Array.newInstance(type, values.size());
            int i = 0;
            for (String string : values) {
                Array.set(array, i++, Converter.convert(string, type, description));
            }

            return array;

        } else {

            final Collection<Object> collection = instantiate((Class<? extends Collection>) parameter.getType());

            for (String string : values) {

                collection.add(Converter.convert(string, type, description));

            }

            return collection;
        }
    }

    private static List<String> getSeparatedValues(String value) {
        final List<String> split = new ArrayList<String>(Arrays.asList(value.split(LIST_TYPE + "|" + LIST_SEPARATOR)));
        split.remove(0);
        return split;
    }

    private static Class<?> getListableType(Parameter parameter) {

        if (parameter.getType().isArray()) {

            return parameter.getType().getComponentType();

        } else {

            return (Class<?>) Generics.getType(parameter);
        }
    }

    public static Collection<Object> instantiate(Class<? extends Collection> aClass) {
        if (aClass.isInterface()) {
            // Sub iterfaces listed first

            // Sets
            if (NavigableSet.class.isAssignableFrom(aClass)) return new TreeSet<Object>();
            if (SortedSet.class.isAssignableFrom(aClass)) return new TreeSet<Object>();
            if (Set.class.isAssignableFrom(aClass)) return new LinkedHashSet<Object>();

            // Queues
            if (Deque.class.isAssignableFrom(aClass)) return new LinkedList<Object>();
            if (Queue.class.isAssignableFrom(aClass)) return new LinkedList<Object>();

            // Lists
            if (List.class.isAssignableFrom(aClass)) return new ArrayList<Object>();

            // Collection
            if (Collection.class.isAssignableFrom(aClass)) return new LinkedList<Object>();

            // Iterable
            if (Iterable.class.isAssignableFrom(aClass)) return new LinkedList<Object>();

            throw new IllegalStateException("Unsupported Collection type: " + aClass.getName());
        }

        if (Modifier.isAbstract(aClass.getModifiers())) {

            throw new IllegalStateException("Unsupported Collection type: " + aClass.getName() + " - Type is Abstract");
        }

        try {

            final Constructor<? extends Collection> constructor = aClass.getConstructor();

            return constructor.newInstance();

        } catch (NoSuchMethodException e) {

            throw new IllegalStateException("Unsupported Collection type: " + aClass.getName() + " - No default constructor");

        } catch (Exception e) {

            throw new IllegalStateException("Cannot construct java.util.Collection type: " + aClass.getName(), e);
        }
    }

    public Map<String, String> getDefaults() {
        final Map<String, String> options = new HashMap<String, String>();

        for (Parameter parameter : optionParameters) {
            final Option option = parameter.getAnnotation(Option.class);
            final Default def = parameter.getAnnotation(Default.class);

            if (def != null) {

                if (isListable(parameter)) {

                    options.put(option.value(), LIST_TYPE + normalize(def));

                } else {

                    options.put(option.value(), def.value());

                }

            } else if (isListable(parameter)) {

                options.put(option.value(), LIST_TYPE);

            } else if (parameter.getType().isPrimitive()) {

                final Class<?> type = parameter.getType();
                if (boolean.class.equals(type)) options.put(option.value(), "false");
                else if (byte.class.equals(type)) options.put(option.value(), "0");
                else if (char.class.equals(type)) options.put(option.value(), "\u0000");
                else if (short.class.equals(type)) options.put(option.value(), "0");
                else if (int.class.equals(type)) options.put(option.value(), "0");
                else if (long.class.equals(type)) options.put(option.value(), "0");
                else if (float.class.equals(type)) options.put(option.value(), "0");
                else if (double.class.equals(type)) options.put(option.value(), "0");
                else options.put(option.value(), null);

            } else {

                options.put(option.value(), null);

            }
        }

        return options;
    }

    public String normalize(Default def) {
        final String value = def.value();

        if (value.contains(LIST_SEPARATOR)) {
            return value;
        }

        if (value.contains("\t")) {
            final String[] split = value.split("\t");
            return Join.join(LIST_SEPARATOR, split);
        }

        if (value.contains(",")) {
            final String[] split = value.split(",");
            return Join.join(LIST_SEPARATOR, split);
        }

        return value;
    }

    public boolean isListable(Parameter parameter) {
        final Class<?> type = parameter.getType();

        return Collection.class.isAssignableFrom(type) || type.isArray();
    }


    public static String value(String value, String defaultValue) {
        return value == null || value.length() == 0 ? defaultValue : value;
    }

    private class Arguments {
        private final List<String> list = new ArrayList<String>();
        private final Map<String, String> options = new HashMap<String, String>();

        private Arguments(final String[] rawArgs) {

            final Map<String, String> defaults = getDefaults();
            final Map<String, String> supplied = new HashMap<String, String>();

            final List<String> invalid = new ArrayList<String>();
            final Set<String> repeated = new HashSet<String>();

            // Read in and apply the options specified on the command line
            for (String arg : rawArgs) {
                if (arg.startsWith("--")) {

                    final String name;
                    String value;

                    if (arg.indexOf("=") > 0) {
                        name = arg.substring(arg.indexOf("--") + 2, arg.indexOf("="));
                        value = arg.substring(arg.indexOf("=") + 1);
                    } else {
                        name = arg.substring(arg.indexOf("--") + 2);
                        value = "true";
                    }

                    if (defaults.containsKey(name)) {
                        final boolean isList = defaults.get(name) != null && defaults.get(name).startsWith(LIST_TYPE);
                        final String existing = supplied.get(name);

                        if (isList) {

                            if (existing == null) {

                                value = LIST_TYPE + value;

                            } else {

                                value = existing + LIST_SEPARATOR + value;

                            }

                        } else if (existing != null) {

                            repeated.add(name);
                        }

                        supplied.put(name, value);
                    } else {
                        invalid.add(name);
                    }
                } else {
                    this.list.add(arg);
                }
            }

            checkInvalid(invalid);
            checkRequired(supplied);
            checkRepeated(repeated);

            interpret(defaults);

            this.options.putAll(defaults);
            this.options.putAll(supplied);

        }

        private void interpret(final Map<String, String> map) {
            final Map<String, String> properties = Substitution.getSystemProperties();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getValue() == null) continue;
                final String value = Substitution.format(entry.getValue(), properties);
                map.put(entry.getKey(), value);
            }
        }

        private void checkInvalid(List<String> invalid) {
            if (invalid.size() > 0) {
                throw new IllegalArgumentException("Unknown options: " + Join.join(", ", new Join.NameCallback() {
                    @Override
                    public String getName(Object object) {
                        return "--" + object;
                    }
                }, invalid));
            }
        }

        private void checkRequired(Map<String, String> supplied) {
            final List<String> required = new ArrayList<String>();
            for (Parameter parameter : optionParameters) {
                if (!parameter.isAnnotationPresent(Required.class)) continue;

                final Option option = parameter.getAnnotation(Option.class);

                if (!supplied.containsKey(option.value())) {
                    required.add(option.value());
                }
            }

            if (required.size() > 0) {
                throw new IllegalArgumentException("Required: " + Join.join(", ", new Join.NameCallback() {
                    @Override
                    public String getName(Object object) {
                        return "--" + object;
                    }
                }, required));
            }
        }

        private void checkRepeated(Set<String> repeated) {
            if (repeated.size() > 0) {
                throw new IllegalArgumentException("Cannot be specified more than once: " + Join.join(", ", repeated));
            }
        }
    }
}