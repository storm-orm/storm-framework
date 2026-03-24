/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.metamodel;

import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.NOTE;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("*")
public class TypeIndexProcessor extends AbstractProcessor {

    private Types types;
    private Elements elements;
    private Filer filer;

    private static final Set<String> INDEXED_CLASS_TYPES = Set.of(
            "st.orm.Data",
            "st.orm.Converter"
    );
    private static final Set<String> INDEXED_INTERFACE_TYPES = Set.of(
            "st.orm.repository.Repository"
    );
    private static final Set<String> INDEXED_TYPES = new LinkedHashSet<>() {{
        addAll(INDEXED_CLASS_TYPES);
        addAll(INDEXED_INTERFACE_TYPES);
    }};

    private static final String INDEX_DIR = "META-INF/storm/";
    private final Map<String, TypeMirror> indexedTypeMirrors = new LinkedHashMap<>();
    private final Map<String, Set<String>> indexEntries = new LinkedHashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
        // Resolve all configured types to TypeMirrors (if present on the classpath).
        for (String typeFqName : INDEXED_TYPES) {
            TypeElement typeElement = elements.getTypeElement(typeFqName);
            if (typeElement != null) {
                indexedTypeMirrors.put(typeFqName, typeElement.asType());
            }
            // Always create an entry set, even if the type isn't present.
            indexEntries.put(typeFqName, new LinkedHashSet<>());
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        processingEnv.getMessager().printMessage(NOTE, "Storm Type Index Processor is running.");
        if (indexedTypeMirrors.isEmpty()) {
            return false;
        }
        for (Element element : roundEnv.getRootElements()) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            String fqcn = elements.getBinaryName(typeElement).toString();
            for (Map.Entry<String, TypeMirror> entry : indexedTypeMirrors.entrySet()) {
                String typeFqName = entry.getKey();
                TypeMirror targetType = entry.getValue();
                boolean allowInterfaces = INDEXED_INTERFACE_TYPES.contains(typeFqName);
                if (isSubtypeOf(typeElement, targetType, allowInterfaces)) {
                    indexEntries.get(typeFqName).add(fqcn);
                }
            }
        }
        // After all rounds, write the index files.
        if (roundEnv.processingOver()) {
            for (Map.Entry<String, Set<String>> entry : indexEntries.entrySet()) {
                String typeFqName = entry.getKey();
                Set<String> types = entry.getValue();
                writeIndex(typeFqName, types);
            }
        }
        return false; // Do not claim any annotations.
    }

    private boolean isSubtypeOf(TypeElement type, TypeMirror target, boolean allowInterfaces) {
        ElementKind kind = type.getKind();
        if (kind == ElementKind.CLASS) {
            // Classes are always allowed.
        } else if (kind == ElementKind.INTERFACE && allowInterfaces) {
            // Interfaces are allowed only for certain indexed types (e.g., Repository).
        } else {
            return false;
        }
        TypeMirror a = types.erasure(type.asType());
        TypeMirror b = types.erasure(target);
        if (types.isSameType(a, b)) {
            return false;
        }
        return types.isSubtype(a, b);
    }

    private void writeIndex(String typeFqName, Set<String> types) {
        if (types.isEmpty()) {
            return;
        }
        String resourceName = INDEX_DIR + typeFqName + ".idx";
        try {
            FileObject file = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    resourceName
            );
            try (Writer writer = file.openWriter()) {
                for (String fqcn : types) {
                    writer.write(fqcn);
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    ERROR,
                    "Failed to write index for " + typeFqName + ". Error: " + e
            );
        }
    }
}
