package io.openapitools.swagger;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.lightbend.lagom.internal.javadsl.api.MethodRefServiceCallHolder;
import com.lightbend.lagom.javadsl.api.Descriptor;
import com.lightbend.lagom.javadsl.api.Service;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.ParameterProcessor;
import io.swagger.v3.core.util.PathUtils;
import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.jaxrs2.ResolvedParameter;
import io.swagger.v3.jaxrs2.SecurityParser;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtension;
import io.swagger.v3.jaxrs2.ext.OpenAPIExtensions;
import io.swagger.v3.jaxrs2.util.ReaderUtils;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.integration.api.OpenAPIConfiguration;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.ws.rs.Path;

public class LagomReader extends Reader {
    private static final Logger LOGGER = LoggerFactory.getLogger(LagomReader.class);

    private OpenAPI openAPI;
    private final Paths paths = new Paths();
    private Components components;
    private final Set<Tag> openApiTags = new LinkedHashSet<>();

    private final Map<Method, String> httpMethods = new HashMap<>();

    public LagomReader(OpenAPI openAPI) {
        super(openAPI);
    }

    @Override
    public void setConfiguration(OpenAPIConfiguration openApiConfiguration) {
        openApiConfiguration.getOpenAPI().setComponents(new Components());
        openApiConfiguration.getOpenAPI().setTags(new ArrayList<>());
        super.setConfiguration(openApiConfiguration);

        this.openAPI = getOpenAPI();
        this.components = getOpenAPI().getComponents();
    }

    @Override
    protected Class<?> getSubResourceWithJaxRsSubresourceLocatorSpecs(Method method) {
        if (httpMethods.containsKey(method)) {
            final Class<?> rawType = method.getReturnType();
            final Class<?> type;
            if (ServiceCall.class.equals(rawType)) {
                type = getServiceClassResultArgument(method.getGenericReturnType());
            } else {
                type = rawType;
            }
            return type;
        } else {
            return super.getSubResourceWithJaxRsSubresourceLocatorSpecs(method);
        }
    }

    private Class<?> getServiceClassResultArgument(Type cls) {
        if (cls instanceof ParameterizedType) {
            final ParameterizedType parameterized = (ParameterizedType) cls;
            final Type[] args = parameterized.getActualTypeArguments();
            if (args.length != 2) {
                LOGGER.error("Unexpected class definition: {}", cls);
                return null;
            }
            final Type second = args[1];
            if (second instanceof Class) {
                return (Class<?>) second;
            } else {
                return null;
            }
        } else {
            LOGGER.error("Unknown class definition: {}", cls);
            return null;
        }
    }


    @Override
    public OpenAPI read(Class<?> cls,
                        String parentPath,
                        String parentMethod,
                        boolean isSubresource,
                        RequestBody parentRequestBody,
                        ApiResponses parentResponses,
                        Set<String> parentTags,
                        List<Parameter> parentParameters,
                        Set<Class<?>> scannedResources) {

        Hidden hidden = cls.getAnnotation(Hidden.class);
        // class path
        final javax.ws.rs.Path apiPath = ReflectionUtils.getAnnotation(cls, javax.ws.rs.Path.class);

        if (hidden != null) { //  || (apiPath == null && !isSubresource)) {
            return openAPI;
        }

        io.swagger.v3.oas.annotations.responses.ApiResponse[] classResponses = ReflectionUtils.getRepeatableAnnotationsArray(cls, io.swagger.v3.oas.annotations.responses.ApiResponse.class);

        List<io.swagger.v3.oas.annotations.security.SecurityScheme> apiSecurityScheme = ReflectionUtils.getRepeatableAnnotations(cls, io.swagger.v3.oas.annotations.security.SecurityScheme.class);
        List<io.swagger.v3.oas.annotations.security.SecurityRequirement> apiSecurityRequirements = ReflectionUtils.getRepeatableAnnotations(cls, io.swagger.v3.oas.annotations.security.SecurityRequirement.class);

        io.swagger.v3.oas.annotations.ExternalDocumentation apiExternalDocs = ReflectionUtils.getAnnotation(cls, io.swagger.v3.oas.annotations.ExternalDocumentation.class);
        io.swagger.v3.oas.annotations.tags.Tag[] apiTags = ReflectionUtils.getRepeatableAnnotationsArray(cls, io.swagger.v3.oas.annotations.tags.Tag.class);
        io.swagger.v3.oas.annotations.servers.Server[] apiServers = ReflectionUtils.getRepeatableAnnotationsArray(cls, io.swagger.v3.oas.annotations.servers.Server.class);

        javax.ws.rs.Consumes classConsumes = ReflectionUtils.getAnnotation(cls, javax.ws.rs.Consumes.class);
        javax.ws.rs.Produces classProduces = ReflectionUtils.getAnnotation(cls, javax.ws.rs.Produces.class);

        boolean classDeprecated = ReflectionUtils.getAnnotation(cls, Deprecated.class) != null;

        // OpenApiDefinition
        OpenAPIDefinition openAPIDefinition = ReflectionUtils.getAnnotation(cls, OpenAPIDefinition.class);

        if (openAPIDefinition != null) {

            // info
            AnnotationsUtils.getInfo(openAPIDefinition.info()).ifPresent(info -> openAPI.setInfo(info));

            // OpenApiDefinition security requirements
            SecurityParser
                .getSecurityRequirements(openAPIDefinition.security())
                .ifPresent(s -> openAPI.setSecurity(s));
            //
            // OpenApiDefinition external docs
            AnnotationsUtils
                .getExternalDocumentation(openAPIDefinition.externalDocs())
                .ifPresent(docs -> openAPI.setExternalDocs(docs));

            // OpenApiDefinition tags
            AnnotationsUtils
                .getTags(openAPIDefinition.tags(), false)
                .ifPresent(openApiTags::addAll);

            // OpenApiDefinition servers
            AnnotationsUtils.getServers(openAPIDefinition.servers()).ifPresent(servers -> openAPI.setServers(servers));

            // OpenApiDefinition extensions
            if (openAPIDefinition.extensions().length > 0) {
                openAPI.setExtensions(AnnotationsUtils
                    .getExtensions(openAPIDefinition.extensions()));
            }

        }

        // class security schemes
        if (apiSecurityScheme != null) {
            for (io.swagger.v3.oas.annotations.security.SecurityScheme securitySchemeAnnotation : apiSecurityScheme) {
                Optional<SecurityParser.SecuritySchemePair> securityScheme = SecurityParser.getSecurityScheme(securitySchemeAnnotation);
                if (securityScheme.isPresent()) {
                    Map<String, io.swagger.v3.oas.models.security.SecurityScheme> securitySchemeMap = new HashMap<>();
                    if (StringUtils.isNotBlank(securityScheme.get().key)) {
                        securitySchemeMap.put(securityScheme.get().key, securityScheme.get().securityScheme);
                        if (components.getSecuritySchemes() != null && !components.getSecuritySchemes().isEmpty()) {
                            components.getSecuritySchemes().putAll(securitySchemeMap);
                        } else {
                            components.setSecuritySchemes(securitySchemeMap);
                        }
                    }
                }
            }
        }

        // class security requirements
        List<io.swagger.v3.oas.models.security.SecurityRequirement> classSecurityRequirements = new ArrayList<>();
        if (apiSecurityRequirements != null) {
            Optional<List<io.swagger.v3.oas.models.security.SecurityRequirement>> requirementsObject = SecurityParser.getSecurityRequirements(
                apiSecurityRequirements.toArray(new io.swagger.v3.oas.annotations.security.SecurityRequirement[apiSecurityRequirements.size()])
            );
            if (requirementsObject.isPresent()) {
                classSecurityRequirements = requirementsObject.get();
            }
        }

        // class tags, consider only name to add to class operations
        final Set<String> classTags = new LinkedHashSet<>();
        if (apiTags != null) {
            AnnotationsUtils
                .getTags(apiTags, false).ifPresent(tags ->
                    tags
                        .stream()
                        .map(Tag::getName)
                        .forEach(classTags::add)
                );
        }

        // parent tags
        if (isSubresource) {
            if (parentTags != null) {
                classTags.addAll(parentTags);
            }
        }

        // servers
        final List<io.swagger.v3.oas.models.servers.Server> classServers = new ArrayList<>();
        if (apiServers != null) {
            AnnotationsUtils.getServers(apiServers).ifPresent(classServers::addAll);
        }

        // class external docs
        Optional<io.swagger.v3.oas.models.ExternalDocumentation> classExternalDocumentation = AnnotationsUtils.getExternalDocumentation(apiExternalDocs);


        JavaType classType = TypeFactory.defaultInstance().constructType(cls);
        BeanDescription bd = Json.mapper().getSerializationConfig().introspect(classType);

        final List<Parameter> globalParameters = new ArrayList<>();

        // look for constructor-level annotated properties
        globalParameters.addAll(ReaderUtils.collectConstructorParameters(cls, components, classConsumes, null));

        // look for field-level annotated properties
        globalParameters.addAll(ReaderUtils.collectFieldParameters(cls, components, classConsumes, null));

        // Make sure that the class methods are sorted for deterministic order
        // See https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#getMethods--
        final List<Method> methods = Arrays.stream(cls.getMethods())
            .sorted(new MethodComparator())
            .collect(Collectors.toList());

        // iterate class methods
        for (Method method : methods) {
            if (isOperationHidden(method)) {
                continue;
            }
            AnnotatedMethod annotatedMethod = bd.findMethod(method.getName(), method.getParameterTypes());
            javax.ws.rs.Produces methodProduces = ReflectionUtils.getAnnotation(method, javax.ws.rs.Produces.class);
            javax.ws.rs.Consumes methodConsumes = ReflectionUtils.getAnnotation(method, javax.ws.rs.Consumes.class);

            if (isMethodOverridden(method, cls)) {
                continue;
            }

            boolean methodDeprecated = ReflectionUtils.getAnnotation(method, Deprecated.class) != null;

            javax.ws.rs.Path methodPath = readPath(cls, method); // ReflectionUtils.getAnnotation(method, javax.ws.rs.Path.class);

            String operationPath = ReaderUtils.getPath(apiPath, methodPath, parentPath, isSubresource);

            // skip if path is the same as parent, e.g. for @ApplicationPath annotated application
            // extending resource config.
            if (ignoreOperationPath(operationPath, parentPath) && !isSubresource) {
                continue;
            }

            Map<String, String> regexMap = new LinkedHashMap<>();
            operationPath = PathUtils.parsePath(operationPath, regexMap);
            if (operationPath != null) {
                if (config != null && ReaderUtils.isIgnored(operationPath, config)) {
                    continue;
                }

                final Class<?> subResource = getSubResourceWithJaxRsSubresourceLocatorSpecs(method);

                String httpMethod = extractOperationMethod(method, OpenAPIExtensions.chain());
                httpMethod = (httpMethod == null && isSubresource) ? parentMethod : httpMethod;

                if (StringUtils.isBlank(httpMethod) && subResource == null) {
                    continue;
                } else if (StringUtils.isBlank(httpMethod) && subResource != null) {
                    Type returnType = method.getGenericReturnType();
                    if (annotatedMethod != null && annotatedMethod.getType() != null) {
                        returnType = annotatedMethod.getType();
                    }

                    if (shouldIgnoreClass(returnType.getTypeName()) && !method.getGenericReturnType().equals(subResource)) {
                        continue;
                    }
                }

                io.swagger.v3.oas.annotations.Operation apiOperation = ReflectionUtils.getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
                JsonView jsonViewAnnotation;
                JsonView jsonViewAnnotationForRequestBody;
                if (apiOperation != null && apiOperation.ignoreJsonView()) {
                    jsonViewAnnotation = null;
                    jsonViewAnnotationForRequestBody = null;
                } else {
                    jsonViewAnnotation = ReflectionUtils.getAnnotation(method, JsonView.class);
                    /* If one and only one exists, use the @JsonView annotation from the method parameter annotated
                       with @RequestBody. Otherwise, fall back to the @JsonView annotation for the method itself. */
                    jsonViewAnnotationForRequestBody = (JsonView) Arrays.stream(ReflectionUtils.getParameterAnnotations(method))
                        .filter(arr ->
                            Arrays.stream(arr)
                                .anyMatch(annotation ->
                                    annotation.annotationType()
                                        .equals(io.swagger.v3.oas.annotations.parameters.RequestBody.class)
                                )
                        ).flatMap(Arrays::stream)
                        .filter(annotation ->
                            annotation.annotationType()
                                .equals(JsonView.class)
                        ).reduce((a, b) -> null)
                        .orElse(jsonViewAnnotation);
                }

                io.swagger.v3.oas.models.Operation operation = parseMethod(
                    method,
                    globalParameters,
                    methodProduces,
                    classProduces,
                    methodConsumes,
                    classConsumes,
                    classSecurityRequirements,
                    classExternalDocumentation,
                    classTags,
                    classServers,
                    isSubresource,
                    parentRequestBody,
                    parentResponses,
                    jsonViewAnnotation,
                    classResponses,
                    annotatedMethod);
                if (operation != null) {

                    if (classDeprecated || methodDeprecated) {
                        operation.setDeprecated(true);
                    }

                    List<Parameter> operationParameters = new ArrayList<>();
                    List<Parameter> formParameters = new ArrayList<>();
                    Annotation[][] paramAnnotations = ReflectionUtils.getParameterAnnotations(method);
                    if (annotatedMethod == null) { // annotatedMethod not null only when method with 0-2 parameters
                        Type[] genericParameterTypes = method.getGenericParameterTypes();
                        for (int i = 0; i < genericParameterTypes.length; i++) {
                            final Type type = TypeFactory.defaultInstance().constructType(genericParameterTypes[i], cls);
                            io.swagger.v3.oas.annotations.Parameter paramAnnotation = AnnotationsUtils.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class, paramAnnotations[i]);
                            Type paramType = ParameterProcessor.getParameterType(paramAnnotation, true);
                            if (paramType == null) {
                                paramType = type;
                            } else {
                                if (!(paramType instanceof Class)) {
                                    paramType = type;
                                }
                            }
                            ResolvedParameter resolvedParameter = paramAnnotations[i].length > 0 ?
                                getParameters(paramType, Arrays.asList(paramAnnotations[i]), operation, classConsumes, methodConsumes, jsonViewAnnotation) :
                                new ResolvedParameter();
                            operationParameters.addAll(resolvedParameter.parameters);
                            // collect params to use together as request Body
                            formParameters.addAll(resolvedParameter.formParameters);
                            if (resolvedParameter.requestBody != null) {
                                processRequestBody(
                                    resolvedParameter.requestBody,
                                    operation,
                                    methodConsumes,
                                    classConsumes,
                                    operationParameters,
                                    paramAnnotations[i],
                                    type,
                                    jsonViewAnnotationForRequestBody,
                                    null);
                            }
                        }
                    } else {
                        for (int i = 0; i < annotatedMethod.getParameterCount(); i++) {
                            AnnotatedParameter param = annotatedMethod.getParameter(i);
                            final Type type = TypeFactory.defaultInstance().constructType(param.getParameterType(), cls);
                            io.swagger.v3.oas.annotations.Parameter paramAnnotation = AnnotationsUtils.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class, paramAnnotations[i]);
                            Type paramType = ParameterProcessor.getParameterType(paramAnnotation, true);
                            if (paramType == null) {
                                paramType = type;
                            } else {
                                if (!(paramType instanceof Class)) {
                                    paramType = type;
                                }
                            }
                            ResolvedParameter resolvedParameter = paramAnnotations[i].length > 0 ?
                                getParameters(paramType, Arrays.asList(paramAnnotations[i]), operation, classConsumes, methodConsumes, jsonViewAnnotation) :
                                new ResolvedParameter();
                            operationParameters.addAll(resolvedParameter.parameters);
                            // collect params to use together as request Body
                            formParameters.addAll(resolvedParameter.formParameters);
                            if (resolvedParameter.requestBody != null) {
                                processRequestBody(
                                    resolvedParameter.requestBody,
                                    operation,
                                    methodConsumes,
                                    classConsumes,
                                    operationParameters,
                                    paramAnnotations[i],
                                    type,
                                    jsonViewAnnotationForRequestBody,
                                    null);
                            }
                        }
                    }
                    // if we have form parameters, need to merge them into single schema and use as request body.
                    if (!formParameters.isEmpty()) {
                        Schema<?> mergedSchema = new ObjectSchema();
                        Map<String, Encoding> encoding = new LinkedHashMap<>();
                        for (Parameter formParam: formParameters) {
                            if (formParam.getExplode() != null || (formParam.getStyle() != null) && Encoding.StyleEnum.fromString(formParam.getStyle().toString()) != null) {
                                Encoding e = new Encoding();
                                if (formParam.getExplode() != null) {
                                    e.explode(formParam.getExplode());
                                }
                                if (formParam.getStyle() != null  && Encoding.StyleEnum.fromString(formParam.getStyle().toString()) != null) {
                                    e.style(Encoding.StyleEnum.fromString(formParam.getStyle().toString()));
                                }
                                encoding.put(formParam.getName(), e);
                            }
                            mergedSchema.addProperties(formParam.getName(), formParam.getSchema());
                            if (formParam.getSchema() != null &&
                                StringUtils.isNotBlank(formParam.getDescription()) &&
                                StringUtils.isBlank(formParam.getSchema().getDescription())) {
                                formParam.getSchema().description(formParam.getDescription());
                            }
                            if (null != formParam.getRequired() && formParam.getRequired()) {
                                mergedSchema.addRequiredItem(formParam.getName());
                            }
                        }
                        Parameter merged = new Parameter().schema(mergedSchema);
                        processRequestBody(
                            merged,
                            operation,
                            methodConsumes,
                            classConsumes,
                            operationParameters,
                            new Annotation[0],
                            null,
                            jsonViewAnnotationForRequestBody,
                            encoding);

                    }
                    if (!operationParameters.isEmpty()) {
                        for (Parameter operationParameter : operationParameters) {
                            operation.addParametersItem(operationParameter);
                        }
                    }

                    // if subresource, merge parent parameters
                    if (parentParameters != null) {
                        for (Parameter parentParameter : parentParameters) {
                            operation.addParametersItem(parentParameter);
                        }
                    }

                    if (subResource != null && !scannedResources.contains(subResource)) {
                        scannedResources.add(subResource);
                        read(subResource, operationPath, httpMethod, true, operation.getRequestBody(), operation.getResponses(), classTags, operation.getParameters(), scannedResources);
                        // remove the sub resource so that it can visit it later in another path,
                        // but we have a room for optimization in the future to reuse the scanned result
                        // by caching the scanned resources in the reader instance to avoid actual scanning
                        // the resources again
                        scannedResources.remove(subResource);
                        // don't proceed with root resource operation, as it's handled by subresource
                        continue;
                    }

                    final Iterator<OpenAPIExtension> chain = OpenAPIExtensions.chain();
                    if (chain.hasNext()) {
                        final OpenAPIExtension extension = chain.next();
                        extension.decorateOperation(operation, method, chain);
                    }

                    PathItem pathItemObject;
                    if (openAPI.getPaths() != null && openAPI.getPaths().get(operationPath) != null) {
                        pathItemObject = openAPI.getPaths().get(operationPath);
                    } else {
                        pathItemObject = new PathItem();
                    }

                    if (StringUtils.isBlank(httpMethod)) {
                        continue;
                    }
                    setPathItemOperation(pathItemObject, httpMethod, operation);

                    paths.addPathItem(operationPath, pathItemObject);
                    if (openAPI.getPaths() != null) {
                        this.paths.putAll(openAPI.getPaths());
                    }

                    openAPI.setPaths(this.paths);
                }
            }
        }

        // if no components object is defined in openApi instance passed by client, set openAPI.components to resolved components (if not empty)
        if (!isEmptyComponents(components) && openAPI.getComponents() == null) {
            openAPI.setComponents(components);
        }

        // add tags from class to definition tags
        AnnotationsUtils
            .getTags(apiTags, true).ifPresent(tags -> openApiTags.addAll(tags));

        if (!openApiTags.isEmpty()) {
            Set<Tag> tagsSet = new LinkedHashSet<>();
            if (openAPI.getTags() != null) {
                for (Tag tag : openAPI.getTags()) {
                    if (tagsSet.stream().noneMatch(t -> t.getName().equals(tag.getName()))) {
                        tagsSet.add(tag);
                    }
                }
            }
            for (Tag tag : openApiTags) {
                if (tagsSet.stream().noneMatch(t -> t.getName().equals(tag.getName()))) {
                    tagsSet.add(tag);
                }
            }
            openAPI.setTags(new ArrayList<>(tagsSet));
        }

        return openAPI;
    }

    private String extractOperationMethod(Method method, Iterator<OpenAPIExtension> chain) {
        return Optional.ofNullable(httpMethods.get(method)).orElse(ReaderUtils.extractOperationMethod(method, chain));
    }

    private boolean shouldIgnoreClass(String className) {
        if (StringUtils.isBlank(className)) {
            return true;
        } else {
            String rawClassName = className;
            if (className.startsWith("[")) {
                rawClassName = className.replace("[simple type, class ", "");
                rawClassName = rawClassName.substring(0, rawClassName.length() - 1);
            }

            boolean ignore = rawClassName.startsWith("javax.ws.rs.");
            ignore = ignore || rawClassName.equalsIgnoreCase("void");
            ignore = ignore || ModelConverters.getInstance().isRegisteredAsSkippedClass(rawClassName);
            return ignore;
        }
    }

    private boolean isEmptyComponents(Components components) {
        if (components == null) {
            return true;
        } else if (components.getSchemas() != null && !components.getSchemas().isEmpty()) {
            return false;
        } else if (components.getSecuritySchemes() != null && !components.getSecuritySchemes().isEmpty()) {
            return false;
        } else if (components.getCallbacks() != null && !components.getCallbacks().isEmpty()) {
            return false;
        } else if (components.getExamples() != null && !components.getExamples().isEmpty()) {
            return false;
        } else if (components.getExtensions() != null && !components.getExtensions().isEmpty()) {
            return false;
        } else if (components.getHeaders() != null && !components.getHeaders().isEmpty()) {
            return false;
        } else if (components.getLinks() != null && !components.getLinks().isEmpty()) {
            return false;
        } else if (components.getParameters() != null && !components.getParameters().isEmpty()) {
            return false;
        } else if (components.getRequestBodies() != null && !components.getRequestBodies().isEmpty()) {
            return false;
        } else {
            return components.getResponses() == null || components.getResponses().isEmpty();
        }
    }

    private void setPathItemOperation(PathItem pathItemObject, String method, io.swagger.v3.oas.models.Operation operation) {
        switch (method) {
            case "post":
                pathItemObject.post(operation);
                break;
            case "get":
                pathItemObject.get(operation);
                break;
            case "delete":
                pathItemObject.delete(operation);
                break;
            case "put":
                pathItemObject.put(operation);
                break;
            case "patch":
                pathItemObject.patch(operation);
                break;
            case "trace":
                pathItemObject.trace(operation);
                break;
            case "head":
                pathItemObject.head(operation);
                break;
            case "options":
                pathItemObject.options(operation);
        }
    }

    public Path readPath(Class<?> cls, Method method) {
        AtomicReference<Path> result = new AtomicReference<>();

        if (Service.class.isAssignableFrom(cls)) {
            Service proxy = (Service) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] { cls },
                (o, m, p) -> {
                    if (m.isDefault() && m.getName().equals("descriptor")) {
                        return MethodHandles.lookup()
                            .findSpecial(cls, m.getName(),
                                MethodType.methodType(Descriptor.class),
                                cls)
                            .bindTo(o)
                            .invokeWithArguments(p);
                    }
                    return null;
                });
            Descriptor descriptor = proxy.descriptor();
            descriptor.calls().stream()
                .filter(call -> call.serviceCallHolder() instanceof MethodRefServiceCallHolder
                    && (((MethodRefServiceCallHolder) call.serviceCallHolder()).methodReference()).equals(method)
                    && call.callId() instanceof Descriptor.RestCallId)
                .map(call -> (Descriptor.RestCallId) call.callId())
                .peek(rest -> httpMethods.put(method, rest.method().name().toLowerCase()))
                .findFirst()
                .ifPresent(rest -> result.set(new Path() {

                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return Path.class;
                    }

                    @Override
                    public String value() {
                        String pattern = rest.pathPattern().replaceAll("/:([^/]*)", "/{$1}");
                        int queryPos = pattern.indexOf('?');
                        return  queryPos == -1 ? pattern : pattern.substring(0, queryPos);
                    }
                }));
        } else {
            result.set(ReflectionUtils.getAnnotation(method, Path.class));
        }

        return result.get();
    }

    private static class MethodComparator implements Comparator<Method> {

        @Override
        public int compare(Method m1, Method m2) {
            // First compare the names of the method
            int val = m1.getName().compareTo(m2.getName());

            // If the names are equal, compare each argument type
            if (val == 0) {
                val = m1.getParameterTypes().length - m2.getParameterTypes().length;
                if (val == 0) {
                    Class<?>[] types1 = m1.getParameterTypes();
                    Class<?>[] types2 = m2.getParameterTypes();
                    for (int i = 0; i < types1.length; i++) {
                        val = types1[i].getName().compareTo(types2[i].getName());

                        if (val != 0) {
                            break;
                        }
                    }
                }
            }
            return val;
        }
    }
}
