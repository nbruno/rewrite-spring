/*
 * Copyright 2022 the original author or authors.
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
package org.openrewrite.java.spring.boot2;

import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.format.AutoFormatVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author Alex Boyko
 */
public class WebSecurityConfigurerAdapter extends Recipe {

    private static final Collection<J.Modifier.Type> EXPLICIT_ACCESS_LEVELS = Arrays.asList(J.Modifier.Type.Public,
            J.Modifier.Type.Private, J.Modifier.Type.Protected);

    private static final String FQN_CONFIGURATION = "org.springframework.context.annotation.Configuration";
    private static final String FQN_WEB_SECURITY_CONFIGURER_ADAPTER = "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter";
    private static final String FQN_SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";
    private static final String FQN_OVERRIDE = "java.lang.Override";
    private static final String FQN_WEB_SECURITY_CUSTOMIZER = "org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer";
    private static final String FQN_INMEMORY_AUTH_CONFIG = "org.springframework.security.config.annotation.authentication.configurers.provisioning.InMemoryUserDetailsManagerConfigurer";
    private static final String FQN_INMEMORY_AUTH_MANAGER = "org.springframework.security.provisioning.InMemoryUserDetailsManager";
    private static final String FQN_JDBC_AUTH_CONFIG = "org.springframework.security.config.annotation.authentication.configurers.provisioning.JdbcUserDetailsManagerConfigurer";
    private static final String FQN_LDAP_AUTH_CONFIG = "org.springframework.security.config.annotation.authentication.configurers.ldap.LdapAuthenticationProviderConfigurer";
    private static final String FQN_AUTH_MANAGER_BUILDER = "org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder";
    private static final String FQN_USER = "org.springframework.security.core.userdetails.User";
    private static final String FQN_USER_DETAILS_BUILDER = "org.springframework.security.core.userdetails.User$UserBuilder";
    private static final String FQN_USER_DETAILS = "org.springframework.security.core.userdetails.UserDetails";
    private static final String BEAN_PKG = "org.springframework.context.annotation";
    private static final String BEAN_SIMPLE_NAME = "Bean";
    private static final String FQN_BEAN = BEAN_PKG + "." + BEAN_SIMPLE_NAME;
    private static final String BEAN_ANNOTATION = "@" + BEAN_SIMPLE_NAME;

    private static final MethodMatcher CONFIGURE_HTTP_SECURITY_METHOD_MATCHER =
            new MethodMatcher("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter configure(org.springframework.security.config.annotation.web.builders.HttpSecurity)", true);
    private static final MethodMatcher CONFIGURE_WEB_SECURITY_METHOD_MATCHER =
            new MethodMatcher("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter configure(org.springframework.security.config.annotation.web.builders.WebSecurity)", true);
    private static final MethodMatcher CONFIGURE_AUTH_MANAGER_SECURITY_METHOD_MATCHER =
            new MethodMatcher("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter configure(org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder)", true);

    private static final MethodMatcher USER_DETAILS_SERVICE_BEAN_METHOD_MATCHER =
            new MethodMatcher("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter  userDetailsServiceBean()", true);
    private static final MethodMatcher AUTHENTICATION_MANAGER_BEAN_METHOD_MATCHER =
            new MethodMatcher("org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter  authenticationManagerBean()", true);

    private static final MethodMatcher AUTH_INMEMORY_WITH_USER =
            new MethodMatcher("org.springframework.security.config.annotation.authentication.configurers.provisioning.UserDetailsManagerConfigurer withUser(..)");

    private static final String HAS_CONFLICT = "has-conflict";

    private static final String FLATTEN_CLASSES = "flatten-classes";

    private enum AuthType {
        NONE,
        LDAP,
        JDBC,
        INMEMORY
    }

    @Override
    public String getDisplayName() {
        return "Spring Security 5.4 introduces the ability to configure HttpSecurity by creating a SecurityFilterChain bean";
    }

    @Override
    public String getDescription() {
        return "The Spring-Security WebSecurityConfigurerAdapter was deprecated 5.7, this recipe will transform `WebSecurityConfigurerAdapter` classes by using a component based approach. Check out the [spring-security-without-the-websecurityconfigureradapter](https://spring.io/blog/2022/02/21/spring-security-without-the-websecurityconfigureradapter) blog for more details.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getApplicableTest() {
        return new UsesType<>(FQN_WEB_SECURITY_CONFIGURER_ADAPTER);
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext context) {
                boolean isWebSecurityConfigurerAdapterClass = TypeUtils.isAssignableTo(FQN_WEB_SECURITY_CONFIGURER_ADAPTER, classDecl.getType())
                        && isAnnotatedWith(classDecl.getLeadingAnnotations(), FQN_CONFIGURATION);
                boolean hasConflict = false;
                if (isWebSecurityConfigurerAdapterClass) {
                    for (Statement s : classDecl.getBody().getStatements()) {
                        if (s instanceof J.MethodDeclaration) {
                            J.MethodDeclaration method = (J.MethodDeclaration) s;
                            if (isConflictingMethod(method)) {
                                hasConflict = true;
                                break;
                            }
                        }
                    }
                    getCursor().putMessage(HAS_CONFLICT, hasConflict);
                    maybeRemoveImport(FQN_WEB_SECURITY_CONFIGURER_ADAPTER);
                }
                classDecl = super.visitClassDeclaration(classDecl, context);
                if (!isWebSecurityConfigurerAdapterClass) {
                    classDecl = processAnyClass(classDecl);
                } else if (!hasConflict) {
                    classDecl = processSecurityAdapterClass(classDecl);
                }
                return classDecl;
            }

            private J.ClassDeclaration processSecurityAdapterClass(J.ClassDeclaration classDecl) {
                classDecl = classDecl.withExtends(null);
                // Flatten configuration classes if applicable
                Cursor enclosingClassCursor = getCursor().getParent();
                while (enclosingClassCursor != null && !(enclosingClassCursor.getValue() instanceof J.ClassDeclaration)) {
                    enclosingClassCursor = enclosingClassCursor.getParent();
                }
                if (enclosingClassCursor != null && enclosingClassCursor.getValue() instanceof J.ClassDeclaration) {
                    J.ClassDeclaration enclosingClass = enclosingClassCursor.getValue();
                    if (isMetaAnnotated(enclosingClass.getType(), FQN_CONFIGURATION, new HashSet<>()) && canMergeClassDeclarations(enclosingClass, classDecl)) {
                        // can flatten. Outer class is annotated as configuration bean
                        List<J.ClassDeclaration> classesToFlatten = enclosingClassCursor.getMessage(FLATTEN_CLASSES);
                        if (classesToFlatten == null) {
                            classesToFlatten = new ArrayList<>();
                            enclosingClassCursor.putMessage(FLATTEN_CLASSES, classesToFlatten);
                        }
                        // only applicable to former subclasses of WebSecurityConfigurereAdapter - other classes won't be flattened
                        classesToFlatten.add(classDecl);
                        // Remove imports for annotations being removed together with class declaration
                        // It is impossible in the general case to tell whether some of these annotations might apply to the bean methods
                        // However, a set of hardcoded annotations can be moved in the future
                        for (J.Annotation a : classDecl.getLeadingAnnotations()) {
                            JavaType.FullyQualified type = TypeUtils.asFullyQualified(a.getType());
                            if (type != null) {
                                maybeRemoveImport(type);
                            }
                        }
                        classDecl = null; // remove class
                    }
                }
                return classDecl;
            }

            private boolean canMergeClassDeclarations(J.ClassDeclaration a, J.ClassDeclaration b) {
                Set<String> aVars = getAllVarNames(a);
                Set<String> bVars = getAllVarNames(b);
                for (String av : aVars) {
                    if (bVars.contains(av)) {
                        return false;
                    }
                }
                Set<String> aMethods = getAllMethodSignatures(a);
                Set<String> bMethods = getAllMethodSignatures(b);
                for (String am : aMethods) {
                    if (bMethods.contains(am)) {
                        return false;
                    }
                }
                return true;
            }

            private Set<String> getAllVarNames(J.ClassDeclaration c) {
                return c.getBody().getStatements().stream()
                        .filter(J.VariableDeclarations.class::isInstance)
                        .map(J.VariableDeclarations.class::cast)
                        .flatMap(vd -> vd.getVariables().stream())
                        .map(v -> v.getName().getSimpleName())
                        .collect(Collectors.toSet());
            }

            private Set<String> getAllMethodSignatures(J.ClassDeclaration c) {
                return c.getBody().getStatements().stream()
                        .filter(J.MethodDeclaration.class::isInstance)
                        .map(J.MethodDeclaration.class::cast)
                        .map(this::simpleMethodSignature)
                        .collect(Collectors.toSet());
            }

            private String simpleMethodSignature(J.MethodDeclaration method) {
                String fullSignature = MethodMatcher.methodPattern(method);
                int firstSpaceIdx = fullSignature.indexOf(' ');
                return firstSpaceIdx < 0 ? fullSignature : fullSignature.substring(firstSpaceIdx + 1);
            }

            private J.ClassDeclaration processAnyClass(J.ClassDeclaration classDecl) {
                // regular class case
                List<J.ClassDeclaration> toFlatten = getCursor().pollMessage(FLATTEN_CLASSES);
                if (toFlatten != null) {
                    // The message won't be 'null' for a configuration class
                    List<Statement> statements = new ArrayList<>(classDecl.getBody().getStatements().size() + toFlatten.size());
                    statements.addAll(classDecl.getBody().getStatements());
                    for (J.ClassDeclaration fc : toFlatten) {
                        for (Statement s : fc.getBody().getStatements()) {
                            if (s instanceof J.MethodDeclaration) {
                                J.MethodDeclaration m = (J.MethodDeclaration) s;
                                if (isAnnotatedWith(m.getLeadingAnnotations(), FQN_BEAN)) {
                                    JavaType.FullyQualified beanType = TypeUtils.asFullyQualified(m.getMethodType().getReturnType());
                                    String uniqueName = computeBeanNameFromClassName(fc.getSimpleName(), beanType.getClassName());
                                    s = m
                                            .withName(m.getName().withSimpleName(uniqueName))
                                            .withMethodType(m.getMethodType().withName(uniqueName));
                                }
                            }
                            statements.add(s);
                        }
                    }
                    classDecl = classDecl.withBody(classDecl.getBody().withStatements(statements));
                    //TODO: not sure how to autoformat only the statements added to the class declaration
                    doAfterVisit(new AutoFormatVisitor<>());
                }
                return classDecl;
            }

            private boolean isConflictingMethod(J.MethodDeclaration m) {
                String methodName = m.getSimpleName();
                JavaType.Method methodType = m.getMethodType();
                return (methodType == null && ("authenticationManagerBean".equals(methodName) || "userDetailsServiceBean".equals(methodName))) ||
                        (USER_DETAILS_SERVICE_BEAN_METHOD_MATCHER.matches(methodType)
                                || AUTHENTICATION_MANAGER_BEAN_METHOD_MATCHER.matches(methodType)
                                || (CONFIGURE_AUTH_MANAGER_SECURITY_METHOD_MATCHER.matches(methodType) && inConflictingAuthConfigMethod(m)));
            }

            private boolean inConflictingAuthConfigMethod(J.MethodDeclaration m) {
                AuthType authType = getAuthType(m);
                switch (authType) {
                    case INMEMORY:
                        return false;
                }
                return true;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext context) {
                Cursor classCursor = getCursor().dropParentUntil(J.ClassDeclaration.class::isInstance);
                if (isConflictingMethod(m)) {
                    m = SearchResult.found(m, "Migrate manually based on https://spring.io/blog/2022/02/21/spring-security-without-the-websecurityconfigureradapter");
                } else if (!classCursor.getMessage(HAS_CONFLICT, true)) {
                    J.ClassDeclaration c = classCursor.getValue();
                    if (CONFIGURE_HTTP_SECURITY_METHOD_MATCHER.matches(m, c)) {
                        m = changeToBeanMethod(m, c, FQN_SECURITY_FILTER_CHAIN, "filterChain", true);
                    } else if (CONFIGURE_WEB_SECURITY_METHOD_MATCHER.matches(m, c)) {
                        m = changeToBeanMethod(m, c, FQN_WEB_SECURITY_CUSTOMIZER, "webSecurityCustomizer", false);
                    } else if (CONFIGURE_AUTH_MANAGER_SECURITY_METHOD_MATCHER.matches(m, c)) {
                        AuthType authType = getAuthType(m);
                        switch (authType) {
                            case INMEMORY:
                                m = changeToBeanMethod(m, c, FQN_INMEMORY_AUTH_MANAGER, "inMemoryAuthManager", false);
                                break;
                            case JDBC:
                                //TODO: implement
                                break;
                            case LDAP:
                                //TODO: implement
                                break;
                            default:
                                throw new IllegalStateException();
                        }
                    }
                }
                return super.visitMethodDeclaration(m, context);
            }

            private J.MethodDeclaration changeToBeanMethod(J.MethodDeclaration m, J.ClassDeclaration c, String fqnReturnType, String newMethodName, boolean keepParams) {
                JavaType.FullyQualified inmemoryAuthConfigType = (JavaType.FullyQualified) JavaType.buildType(fqnReturnType);
                JavaType.Method type = m.getMethodType();
                if (type != null) {
                    type = type.withName(newMethodName).withReturnType(inmemoryAuthConfigType);
                    if (!keepParams) {
                        type = type
                                .withParameterTypes(Collections.emptyList())
                                .withParameterNames(Collections.emptyList());
                        for (JavaType pt : type.getParameterTypes()) {
                            JavaType.FullyQualified fqt = TypeUtils.asFullyQualified(pt);
                            if (fqt != null) {
                                maybeRemoveImport(fqt);
                            }
                        }
                    }
                }

                Space returnPrefix = m.getReturnTypeExpression() == null ? Space.EMPTY : m.getReturnTypeExpression().getPrefix();
                m = m.withLeadingAnnotations(ListUtils.map(m.getLeadingAnnotations(), anno -> {
                            if (TypeUtils.isOfClassType(anno.getType(), FQN_OVERRIDE)) {
                                maybeRemoveImport(FQN_OVERRIDE);
                                return null;
                            }
                            return anno;
                        }))
                        .withReturnTypeExpression(new J.Identifier(Tree.randomId(), returnPrefix, Markers.EMPTY, inmemoryAuthConfigType.getClassName(), inmemoryAuthConfigType, null))
                        .withName(m.getName().withSimpleName(newMethodName))
                        .withMethodType(type)
                        .withModifiers(ListUtils.map(m.getModifiers(), modifier -> EXPLICIT_ACCESS_LEVELS.contains(modifier.getType()) ? null : modifier));

                if (!keepParams) {
                    m = m.withParameters(Collections.emptyList());
                }

                maybeAddImport(inmemoryAuthConfigType);
                return addBeanAnnotation(m, getCursor());
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext context) {
                J.Block b = super.visitBlock(block, context);
                if (getCursor().getParent() != null && getCursor().getParent().getValue() instanceof J.MethodDeclaration) {
                    J.MethodDeclaration parentMethod = getCursor().getParent().getValue();
                    Cursor classDeclCursor = getCursor().dropParentUntil(J.ClassDeclaration.class::isInstance);
                    J.ClassDeclaration classDecl = classDeclCursor.getValue();
                    if (!classDeclCursor.getMessage(HAS_CONFLICT, true)) {
                        if (CONFIGURE_HTTP_SECURITY_METHOD_MATCHER.matches(parentMethod, classDecl)) {
                            b = handleHttpSecurity(b, parentMethod);
                        } else if (CONFIGURE_WEB_SECURITY_METHOD_MATCHER.matches(parentMethod, classDecl)) {
                            b = handleWebSecurity(b, parentMethod);
                        } else if (CONFIGURE_AUTH_MANAGER_SECURITY_METHOD_MATCHER.matches(parentMethod, classDecl)) {
                            AuthType authType = getAuthType(parentMethod);
                            switch (authType) {
                                case INMEMORY:
                                    b = handleAuthInMemory(b, parentMethod);
                                    break;
                                case LDAP:
                                    //TODO: implement
                                    break;
                                case JDBC:
                                    //TODO: implement
                                    break;
                            }
                        }
                    }
                }
                return b;
            }

            private J.Block handleHttpSecurity(J.Block b, J.MethodDeclaration parentMethod) {
                JavaTemplate template = JavaTemplate.builder(this::getCursor,  "return #{any(org.springframework.security.config.annotation.SecurityBuilder)}.build();")
                        .javaParser(() -> JavaParser.fromJavaVersion()
                                .dependsOn("package org.springframework.security.config.annotation;" +
                                        "public interface SecurityBuilder<O> {\n" +
                                        "    O build() throws Exception;" +
                                        "}")
                                .build()).imports("org.springframework.security.config.annotation.SecurityBuilder").build();
                return b.withTemplate(template, b.getCoordinates().lastStatement(),
                        ((J.VariableDeclarations)parentMethod.getParameters().get(0)).getVariables().get(0).getName());
            }

            private J.Block handleWebSecurity(J.Block b, J.MethodDeclaration parentMethod) {
                String t = "return (" + ((J.VariableDeclarations)parentMethod.getParameters().get(0)).getVariables().get(0).getName().getSimpleName() + ") -> #{any()};";
                JavaTemplate template = JavaTemplate.builder(this::getCursor, t).javaParser(() -> JavaParser.fromJavaVersion().build()).build();
                b = b.withTemplate(template, b.getCoordinates().firstStatement(), b);
                return b.withStatements(ListUtils.map(b.getStatements(), (index, stmt) -> {
                    if (index == 0){
                        return stmt;
                    }
                    return null;
                }));
            }

            private J.Block handleAuthInMemory(J.Block b, J.MethodDeclaration parentMethod) {
                Expression userExpr = findUserParameterExpression(b.getStatements().get(b.getStatements().size() - 1));
                JavaType.FullyQualified type = userExpr == null ? null : TypeUtils.asFullyQualified(userExpr.getType());
                String typeStr = "";
                if (userExpr.getType() instanceof JavaType.Primitive) {
                    typeStr = ((JavaType.Primitive) userExpr.getType()).getClassName();
                } else if (userExpr.getType() instanceof JavaType.FullyQualified) {
                    typeStr = ((JavaType.FullyQualified) userExpr.getType()).getFullyQualifiedName();
                }
                String t;
                Object[] templateParams = new Object[0];
                switch (typeStr) {
                    case FQN_USER_DETAILS_BUILDER:
                        t = "return new InMemoryUserDetailsManager(#{any()}.build());";
                        templateParams = new Object[]{userExpr};
                        break;
                    case FQN_USER_DETAILS:
                        t = "return new InMemoryUserDetailsManager(#{any()});";
                        templateParams = new Object[]{userExpr};
                        break;
                    case "java.lang.String":
                        t = "return new InMemoryUserDetailsManager(User.builder().username(#{any()}).build());";
                        templateParams = new Object[]{userExpr};
                        maybeAddImport(FQN_USER);
                        break;
                    default:
                        t = "return new InMemoryUserDetailsManager();";
                        b = SearchResult.found(b, "Unrecognized type of user expression " + userExpr + "\n.Please correct manually");
                }
                JavaTemplate template = JavaTemplate.builder(this::getCursor, t).javaParser(() -> JavaParser.fromJavaVersion()
                                .dependsOn(

                                        "package org.springframework.security.core.userdetails;\n" +
                                                "public interface UserDetails {}\n",

                                        "package org.springframework.security.provisioning;\n" +
                                                "public class InMemoryUserDetailsManager {\n" +
                                                "    public InMemoryUserDetailsManager(org.springframework.security.core.userdetails.UserDetails user) {}\n" +
                                                "}",

                                        "package org.springframework.security.core.userdetails;\n" +
                                                "public class User {\n" +
                                                "   public static UserBuilder builder() {}\n" +
                                                "   public interface UserBuilder {\n" +
                                                "       UserBuilder username(String s);\n" +
                                                "       UserDetails build();\n" +
                                                "   }\n" +
                                                "}\n"
                                )
                                .build())
                        .imports(FQN_INMEMORY_AUTH_MANAGER, FQN_USER_DETAILS_BUILDER, FQN_USER)
                        .build();
                List<Statement> allExcetLastStatements = b.getStatements();
                allExcetLastStatements.remove(b.getStatements().size() - 1);
                b = b
                        .withStatements(allExcetLastStatements)
                        .withTemplate(template, b.getCoordinates().lastStatement(), templateParams);
                maybeAddImport(FQN_INMEMORY_AUTH_MANAGER);
                maybeRemoveImport(FQN_AUTH_MANAGER_BUILDER);
                return b;
            }

            private J.MethodDeclaration addBeanAnnotation(J.MethodDeclaration m, Cursor c) {
                maybeAddImport(FQN_BEAN);
                JavaTemplate template = JavaTemplate.builder(() -> c, BEAN_ANNOTATION).imports(FQN_BEAN).javaParser(() -> JavaParser.fromJavaVersion()
                        .dependsOn("package " + BEAN_PKG + "; public @interface " + BEAN_SIMPLE_NAME + " {}").build()).build();
                return m.withTemplate(template, m.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
            }


        };
    }

    private static String computeBeanNameFromClassName(String className, String beanType) {
        String lowerCased = Character.toLowerCase(className.charAt(0)) + className.substring(1);
        String newName = lowerCased
                .replace("WebSecurityConfigurerAdapter", beanType)
                .replace("SecurityConfigurerAdapter", beanType)
                .replace("ConfigurerAdapter", beanType)
                .replace("Adapter", beanType);
        if (lowerCased.equals(newName)) {
            newName = newName + beanType;
        }
        return newName;
    }

    private static boolean isMetaAnnotated(JavaType.FullyQualified t, String fqn, Set<JavaType.FullyQualified> visited) {
        for (JavaType.FullyQualified a : t.getAnnotations()) {
            if (!visited.contains(a)) {
                visited.add(a);
                if (fqn.equals(a.getFullyQualifiedName())) {
                    return true;
                } else {
                    boolean metaAnnotated = isMetaAnnotated(a, fqn, visited);
                    if (metaAnnotated) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isAnnotatedWith(Collection<J.Annotation> annotations, String annotationType) {
        return annotations.stream().anyMatch(a -> TypeUtils.isOfClassType(a.getType(), annotationType));
    }

    private static AuthType getAuthType(J.MethodDeclaration m) {
        Statement lastStatement = m.getBody().getStatements().get(m.getBody().getStatements().size() - 1);
        if (lastStatement instanceof J.MethodInvocation) {
            for (J.MethodInvocation invocation = (J.MethodInvocation) lastStatement; invocation != null;) {
                Expression target = invocation.getSelect();
                if (target != null) {
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(target.getType());
                    if (type != null) {
                        switch (type.getFullyQualifiedName()) {
                            case FQN_INMEMORY_AUTH_CONFIG:
                                return AuthType.INMEMORY;
                            case FQN_LDAP_AUTH_CONFIG:
                                return AuthType.LDAP;
                            case FQN_JDBC_AUTH_CONFIG:
                                return AuthType.JDBC;
                        }
                    }
                    if (target instanceof J.MethodInvocation) {
                        invocation = (J.MethodInvocation) target;
                        continue;
                    }
                }
                invocation = null;
            }

        }
        return AuthType.NONE;
    }

    private Expression findUserParameterExpression(Statement s) {
        AtomicReference<Expression> context = new AtomicReference<>();
        new JavaIsoVisitor<AtomicReference<Expression>>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicReference<Expression> ref) {
                if (AUTH_INMEMORY_WITH_USER.matches(method)) {
                    ref.set(method.getArguments().get(0));
                    return method;
                }
                return super.visitMethodInvocation(method, ref);
            }
        }.visit(s, context);
        return context.get();
    }

}
