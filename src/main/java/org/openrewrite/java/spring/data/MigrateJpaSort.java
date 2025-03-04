/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring.data;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.stream.Collectors;

public class MigrateJpaSort extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use `JpaSort.of(..)`";
    }

    @Override
    public String getDescription() {
        return "Equivalent constructors in `JpaSort` were deprecated in Spring Data 2.3.";
    }

    @Nullable
    @Override
    protected TreeVisitor<?, ExecutionContext> getSingleSourceApplicableTest() {

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (cu.getPackageDeclaration() != null
                    && cu.getPackageDeclaration().getPackageName().equals("org.springframework.data.jpa.domain")) {
                    return cu;
                }

                doAfterVisit(new UsesType<>("org.springframework.data.jpa.domain.JpaSort"));
                return cu;
            }
        };

    }

    @Override
    protected JavaVisitor<ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                if (newClass.getClazz() != null && TypeUtils.isOfClassType(newClass.getClazz().getType(), "org.springframework.data.jpa.domain.JpaSort")) {
                    newClass.getArguments();
                    String template = newClass.getArguments().stream()
                            .map(arg -> TypeUtils.asFullyQualified(arg.getType()))
                            .map(type -> "#{any(" + (type == null? "" :  type.getFullyQualifiedName()) + ")}")
                            .collect(Collectors.joining(",", "JpaSort.of(", ")"));

                    return newClass.withTemplate(
                            JavaTemplate.builder(this::getCursor, template)
                                    .javaParser(() -> JavaParser.fromJavaVersion()
                                            .classpathFromResources(ctx, "spring-data-commons-2.*",
                                                    "spring-data-jpa-2.3.*", "javax.persistence-api-2.*")
                                            .build())
                                    .imports("org.springframework.data.jpa.domain.JpaSort")
                                    .build(),
                            newClass.getCoordinates().replace(),
                            newClass.getArguments().toArray());
                }

                return super.visitNewClass(newClass, ctx);
            }
        };
    }
}
