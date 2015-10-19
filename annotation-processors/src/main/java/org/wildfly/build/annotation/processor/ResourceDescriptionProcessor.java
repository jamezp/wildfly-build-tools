/*
 * Copyright 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.build.annotation.processor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.wildfly.annotations.Description;
import org.wildfly.annotations.Descriptions;
import org.wildfly.annotations.ResourceDescriptions;
import org.wildfly.annotations.ResourcePath;
import org.wildfly.annotations.ResourcePaths;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class ResourceDescriptionProcessor extends AbstractProcessor {

    public ResourceDescriptionProcessor() {
        super(Description.class, ResourcePath.class);
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        // Find all types with the @ResourcePath annotation
        final Set<TypeElement> resourcePathTypes = ElementFilter.typesIn(roundEnv.getElementsAnnotatedWith(ResourcePath.class));
        final Map<String, String> properties = new LinkedHashMap<>();
        // TODO (jrp) currently multiple @ResourceDescriptions are not supported, but should be in theory
        // TODO (jrp) this needs to be removed
        TypeElement first = null;
        for (TypeElement resourcePath : resourcePathTypes) {
            if (first == null) first = resourcePath;
            properties.putAll(processResourcePathType(resourcePath));
        }
        writeDescriptions(first, properties);

        return false;
    }


    private Map<String, String> processResourcePathType(final TypeElement type) {
        final Map<String, String> properties = new LinkedHashMap<>();

        // Get the path from the top level element that children should inherit
        final ResourcePath rootResourcePath = type.getAnnotation(ResourcePath.class);

        // Check for descriptions on the type
        processTypeDescription(rootResourcePath, type, properties);

        final List<VariableElement> fields = ElementFilter.fieldsIn(type.getEnclosedElements());
        // Process each field
        for (VariableElement field : fields) {
            if (field.getAnnotation(ResourcePath.class) != null) {
                processFieldDescription(rootResourcePath, field.getAnnotation(ResourcePath.class), field, properties);
            } else if (field.getAnnotation(ResourcePaths.class) != null) {
                final ResourcePaths resourcePaths = field.getAnnotation(ResourcePaths.class);
                for (ResourcePath resourcePath : resourcePaths.value()) {
                    processFieldDescription(rootResourcePath, resourcePath, field, properties);
                }
            } else if (field.getAnnotation(Description.class) != null) {
                processFieldDescription(rootResourcePath, null, field, properties);
            } else if (field.getAnnotation(Descriptions.class) != null) {
                processFieldDescription(rootResourcePath, null, field, properties);
            }
        }
        return properties;
    }

    private void processFieldDescription(final ResourcePath rootResourcePath, final ResourcePath resourcePath, final VariableElement field, final Map<String, String> properties) {
        processFieldDescription(rootResourcePath, resourcePath, field.getAnnotation(Description.class), field.getSimpleName(), properties);
        final Descriptions descriptions = field.getAnnotation(Descriptions.class);
        if (descriptions != null) {
            for (Description description : descriptions.value()) {
                processFieldDescription(rootResourcePath, resourcePath, description, field.getSimpleName(), properties);
            }
        }
    }

    private void processFieldDescription(final ResourcePath rootResourcePath, final ResourcePath resourcePath, final Description description, final CharSequence name, final Map<String, String> properties) {
        if (description != null) {
            if (description.key() == null || description.key().isEmpty()) {
                final StringBuilder key = new StringBuilder(64);
                key.append(rootResourcePath.value());
                if (resourcePath != null) {
                    appendSegment(key, resourcePath.value());
                }
                appendName(key, description, name);
                properties.put(key.toString(), description.value());
            } else {
                properties.put(description.key(), description.value());
            }
        }
    }

    private void processTypeDescription(final ResourcePath resourcePath, final TypeElement type, final Map<String, String> properties) {
        processTypeDescription(resourcePath, type.getAnnotation(Description.class), properties);
        final Descriptions descriptions = type.getAnnotation(Descriptions.class);
        if (descriptions != null) {
            for (Description desc : descriptions.value()) {
                processTypeDescription(resourcePath, desc, properties);
            }
        }
    }

    private void processTypeDescription(final ResourcePath resourcePath, final Description description, final Map<String, String> properties) {
        if (description != null) {
            final StringBuilder key = new StringBuilder(64);
            if (description.key() == null || description.key().isEmpty()) {
                key.append(resourcePath.value());
                if (!description.name().isEmpty()) {
                    appendSegment(key, description.name());
                }
                properties.put(key.toString(), description.value());
            } else {
                properties.put(description.key(), description.value());
            }
        }
    }

    private void writeDescriptions(final TypeElement type, final Map<String, String> properties) {
        if (type == null) {
            return;
        }
        ResourceDescriptions resourceDescriptions = type.getAnnotation(ResourceDescriptions.class);
        final CharSequence packageName;
        final String fileName;
        if (resourceDescriptions != null) {
            packageName = resourceDescriptions.packageName();
            fileName = resourceDescriptions.bundleName();
        } else {
            packageName = elementUtil.getPackageOf(type).getQualifiedName();
            fileName = "LocalDescriptions.properties";
        }
        final Properties props = new Properties();

        // TODO (jrp) these should be written out in alphabetical order. Keys and values are need to be cleaned

        // Check for an existing file
        try {
            final FileObject fileObject = filer.getResource(StandardLocation.CLASS_OUTPUT, packageName, fileName);
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(fileObject.openInputStream(), StandardCharsets.UTF_8))) {
                props.load(reader);
            }
        } catch (FileNotFoundException ignore) {
            // File was not found, we can ignore this
        } catch (IOException e) {
            printError(e);
        }

        try {
            final FileObject fileObject = filer.createResource(StandardLocation.CLASS_OUTPUT, packageName, fileName);
            try (final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
                props.putAll(properties);
                props.store(writer, "Generated description file");
            }
        } catch (IOException e) {
            printError(e);
        }
    }

    private static void appendSegment(final StringBuilder sb, final String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.charAt(sb.length() - 1) == '.') {
            sb.append(value);
        } else {
            sb.append('.').append(value);
        }
    }

    private static void appendName(final StringBuilder sb, final Description description, final CharSequence name) {
        String value = description.name();
        if (value == null || value.isEmpty()) {
            value = name.toString().toLowerCase(Locale.ROOT).replace('_', '-');
        }
        appendSegment(sb, value);
    }
}
