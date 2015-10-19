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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.wildfly.annotations.ServiceProvider;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class ServiceProviderProcessor extends AbstractProcessor {

    public ServiceProviderProcessor() {
        super(ServiceProvider.class);
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {

        // We only want to process @ServiceProvider types
        final TypeElement annotation = elementUtil.getTypeElement(ServiceProvider.class.getName());
        if (annotations.contains(annotation)) {
            final Map<CharSequence, Set<CharSequence>> servicesMap = new HashMap<>();
            // Get all the classes annotated with @ServiceProvider
            final Set<TypeElement> implementations = ElementFilter.typesIn(roundEnv.getElementsAnnotatedWith(annotation));
            for (TypeElement impl : implementations) {
                // Get the annotation
                final TypeElement contract = resolveClass(impl);
                if (isValid(impl, contract)) {
                    final CharSequence contractName = elementUtil.getBinaryName(contract);
                    final Set<CharSequence> impls;
                    if (servicesMap.containsKey(contractName)) {
                        impls = servicesMap.get(contractName);
                    } else {
                        impls = new LinkedHashSet<>();
                        servicesMap.put(contractName, impls);
                    }
                    impls.add(elementUtil.getBinaryName(impl));
                }
            }

            // Check for an existing file
            for (Entry<CharSequence, Set<CharSequence>> entry : servicesMap.entrySet()) {
                try {
                    final FileObject fileObject = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + entry.getKey());
                    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(fileObject.openInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            entry.getValue().add(line);
                        }
                    }
                } catch (FileNotFoundException ignore) {
                    // File was not found, we can ignore this
                } catch (IOException e) {
                    printError(e);
                }
            }

            for (Entry<CharSequence, Set<CharSequence>> entry : servicesMap.entrySet()) {
                try {
                    final FileObject fileObject = filer.createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + entry.getKey());
                    try (final PrintWriter writer = new PrintWriter(new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
                        for (CharSequence s : entry.getValue()) {
                            writer.println(s.toString());
                        }
                    }
                } catch (IOException e) {
                    printError(e);
                }
            }
        }
        return false;
    }

    private boolean isValid(final TypeElement impl, final TypeElement contract) {
        if (impl.getKind() != ElementKind.CLASS || impl.getModifiers().contains(Modifier.ABSTRACT)) {
            printError(impl, "%s must be a concrete class", impl.getQualifiedName());
            return false;
        } else if (contract == null) {
            printError(impl, "Missing required value argument");
            return false;
            // Validate the class implements or extends the service
        } else if (!typeUtil.isAssignable(impl.asType(), contract.asType())) {
            printError(impl, "Type %s is not assignable from %s", elementUtil.getBinaryName(impl), elementUtil.getBinaryName(contract));
            return false;
        }
        return true;
    }

    private TypeElement resolveClass(final Element type) {
        final AnnotationMirror mirror = getAnnotation(ServiceProvider.class, type);
        if (mirror != null) {
            final AnnotationValue value = getAnnotationValue(mirror);
            if (value != null) {
                return toElement(value);
            }
        }
        return null;
    }
}
