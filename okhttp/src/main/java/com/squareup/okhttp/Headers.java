/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.squareup.okhttp;

import com.squareup.okhttp.internal.http.HttpDate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The header fields of a single HTTP message. Values are uninterpreted strings;
 * use {@code Request} and {@code Response} for interpreted headers. This class
 * maintains the order of the header fields within the HTTP message.
 * <p/>
 * <p>This class tracks header values line-by-line. A field with multiple comma-
 * separated values on the same line will be treated as a field with a single
 * value by this class. It is the caller's responsibility to detect and split
 * on commas if their field permits multiple values. This simplifies use of
 * single-valued fields whose values routinely contain commas, such as cookies
 * or dates.
 * <p/>
 * <p>This class trims whitespace from values. It never returns values with
 * leading or trailing whitespace.
 * <p/>
 * <p>Instances of this class are immutable. Use {@link Builder} to create
 * instances.
 */
public final class Headers {
    private final String[] namesAndValues;

    private Headers(Builder builder) {
        this.namesAndValues = builder.namesAndValues.toArray(new String[builder.namesAndValues.size()]);
    }

    private Headers(String[] namesAndValues) {
        this.namesAndValues = namesAndValues;
    }

    /**
     * Returns the last value corresponding to the specified field, or null.
     */
    public String get(String name) {
        return get(namesAndValues, name);
    }

    /**
     * Returns the last value corresponding to the specified field parsed as an
     * HTTP date, or null if either the field is absent or cannot be parsed as a
     * date.
     */
    public Date getDate(String name) {
        String value = get(name);
        return value != null ? HttpDate.parse(value) : null;
    }

    /**
     * Returns the number of field values.
     */
    public int size() {
        return namesAndValues.length / 2;
    }

    /**
     * Returns the field at {@code position} or null if that is out of range.
     */
    public String name(int index) {
        int nameIndex = index * 2;
        if (nameIndex < 0 || nameIndex >= namesAndValues.length) {
            return null;
        }
        return namesAndValues[nameIndex];
    }

    /**
     * Returns the value at {@code index} or null if that is out of range.
     */
    public String value(int index) {
        int valueIndex = index * 2 + 1;
        if (valueIndex < 0 || valueIndex >= namesAndValues.length) {
            return null;
        }
        return namesAndValues[valueIndex];
    }

    /**
     * Returns an immutable case-insensitive set of header names.
     */
    public Set<String> names() {
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0, size = size(); i < size; i++) {
            result.add(name(i));
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Returns an immutable list of the header values for {@code name}.
     */
    public List<String> values(String name) {
        List<String> result = null;
        for (int i = 0, size = size(); i < size; i++) {
            if (name.equalsIgnoreCase(name(i))) {
                if (result == null) result = new ArrayList<>(2);
                result.add(value(i));
            }
        }
        return result != null
                ? Collections.unmodifiableList(result)
                : Collections.<String>emptyList();
    }

    public Builder newBuilder() {
        Builder result = new Builder();
        Collections.addAll(result.namesAndValues, namesAndValues);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = size(); i < size; i++) {
            result.append(name(i)).append(": ").append(value(i)).append("\n");
        }
        return result.toString();
    }

    public Map<String, List<String>> toMultimap() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (int i = 0, size = size(); i < size; i++) {
            String name = name(i);
            List<String> values = result.get(name);
            if (values == null) {
                values = new ArrayList<>(2);
                result.put(name, values);
            }
            values.add(value(i));
        }
        return result;
    }

    private static String get(String[] namesAndValues, String name) {
        for (int i = namesAndValues.length - 2; i >= 0; i -= 2) {
            if (name.equalsIgnoreCase(namesAndValues[i])) {
                return namesAndValues[i + 1];
            }
        }
        return null;
    }

    /**
     * Returns headers for the alternating header names and values. There must be
     * an even number of arguments, and they must alternate between header names
     * and values.
     */
    public static Headers of(String... namesAndValues) {
        if (namesAndValues == null || namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected alternating header names and values");
        }

        // Make a defensive copy and clean it up.
        namesAndValues = namesAndValues.clone();
        for (int i = 0; i < namesAndValues.length; i++) {
            if (namesAndValues[i] == null)
                throw new IllegalArgumentException("Headers cannot be null");
            namesAndValues[i] = namesAndValues[i].trim();
        }

        // Check for malformed headers.
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String name = namesAndValues[i];
            String value = namesAndValues[i + 1];
            if (name.length() == 0 || name.indexOf('\0') != -1 || value.indexOf('\0') != -1) {
                throw new IllegalArgumentException("Unexpected header: " + name + ": " + value);
            }
        }

        return new Headers(namesAndValues);
    }

    /**
     * Returns headers for the header names and values in the {@link Map}.
     */
    public static Headers of(Map<String, String> headers) {
        if (headers == null) {
            throw new IllegalArgumentException("Expected map with header names and values");
        }

        // Make a defensive copy and clean it up.
        String[] namesAndValues = new String[headers.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() == null || header.getValue() == null) {
                throw new IllegalArgumentException("Headers cannot be null");
            }
            String name = header.getKey().trim();
            String value = header.getValue().trim();
            if (name.length() == 0 || name.indexOf('\0') != -1 || value.indexOf('\0') != -1) {
                throw new IllegalArgumentException("Unexpected header: " + name + ": " + value);
            }
            namesAndValues[i] = name;
            namesAndValues[i + 1] = value;
            i += 2;
        }

        return new Headers(namesAndValues);
    }

    public static final class Builder {
        private final List<String> namesAndValues = new ArrayList<>(20);

        /**
         * Add a header line without any validation. Only appropriate for headers from the remote peer
         * or cache.
         */
        Builder addLenient(String line) {
            int index = line.indexOf(":", 1);
            if (index != -1) {
                return addLenient(line.substring(0, index), line.substring(index + 1));
            } else if (line.startsWith(":")) {
                // Work around empty header names and header names that start with a
                // colon (created by old broken SPDY versions of the response cache).
                return addLenient("", line.substring(1)); // Empty header name.
            } else {
                return addLenient("", line); // No header name.
            }
        }

        /**
         * Add an header line containing a field name, a literal colon, and a value.
         */
        public Builder add(String line) {
            int index = line.indexOf(":");
            if (index == -1) {
                throw new IllegalArgumentException("Unexpected header: " + line);
            }
            return add(line.substring(0, index).trim(), line.substring(index + 1));
        }

        /**
         * Add a field with the specified value.
         */
        public Builder add(String name, String value) {
            if (name == null) throw new IllegalArgumentException("name == null");
            if (value == null) throw new IllegalArgumentException("value == null");
            if (name.length() == 0 || name.indexOf('\0') != -1 || value.indexOf('\0') != -1) {
                throw new IllegalArgumentException("Unexpected header: " + name + ": " + value);
            }
            return addLenient(name, value);
        }

        /**
         * Add a field with the specified value without any validation. Only
         * appropriate for headers from the remote peer or cache.
         */
        Builder addLenient(String name, String value) {
            namesAndValues.add(name);
            namesAndValues.add(value.trim());
            return this;
        }

        public Builder removeAll(String name) {
            for (int i = 0; i < namesAndValues.size(); i += 2) {
                if (name.equalsIgnoreCase(namesAndValues.get(i))) {
                    namesAndValues.remove(i); // name
                    namesAndValues.remove(i); // value
                    i -= 2;
                }
            }
            return this;
        }

        /**
         * Set a field with the specified value. If the field is not found, it is
         * added. If the field is found, the existing values are replaced.
         */
        public Builder set(String name, String value) {
            removeAll(name);
            add(name, value);
            return this;
        }

        /**
         * Equivalent to {@code build().get(name)}, but potentially faster.
         */
        public String get(String name) {
            for (int i = namesAndValues.size() - 2; i >= 0; i -= 2) {
                if (name.equalsIgnoreCase(namesAndValues.get(i))) {
                    return namesAndValues.get(i + 1);
                }
            }
            return null;
        }

        public Headers build() {
            return new Headers(this);
        }
    }
}
