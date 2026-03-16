package com.rorm.durable.processor;

import com.palantir.javapoet.*;
import com.rorm.durable.*;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.rorm.durable.DurableJob")
public class DurableJobProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (var element : roundEnv.getElementsAnnotatedWith(DurableJob.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printError("@DurableJob can only be applied to classes", element);
                continue;
            }
            processJob((TypeElement) element);
        }
        return true;
    }

    private void processJob(TypeElement jobClass) {
        var entryMethod = findEntryMethod(jobClass);
        if (entryMethod == null) {
            processingEnv.getMessager().printError(
                "@DurableJob class must have exactly one @JobEntry method", jobClass);
            return;
        }

        var depFields = extractDependencyFields(jobClass);
        var submitter = generateSubmitter(jobClass, entryMethod, depFields);

        var packageName = processingEnv.getElementUtils().getPackageOf(jobClass).getQualifiedName().toString();
        try {
            JavaFile.builder(packageName, submitter).build().writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printError("Failed to generate submitter: " + e.getMessage(), jobClass);
        }
    }

    private ExecutableElement findEntryMethod(TypeElement jobClass) {
        ExecutableElement found = null;
        for (var enclosed : jobClass.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD && enclosed.getAnnotation(JobEntry.class) != null) {
                if (found != null) {
                    processingEnv.getMessager().printError(
                        "@DurableJob class must have exactly one @JobEntry method", enclosed);
                    return null;
                }
                found = (ExecutableElement) enclosed;
            }
        }
        return found;
    }

    private List<FieldSpec> extractDependencyFields(TypeElement jobClass) {
        var fields = new ArrayList<FieldSpec>();
        for (var enclosed : jobClass.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            var field = (VariableElement) enclosed;
            if (!field.getModifiers().contains(Modifier.FINAL)) {
                continue;
            }
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }

            var fieldBuilder = FieldSpec.builder(
                TypeName.get(field.asType()),
                field.getSimpleName().toString(),
                Modifier.PRIVATE, Modifier.FINAL
            );
            for (var annotation : field.getAnnotationMirrors()) {
                fieldBuilder.addAnnotation(AnnotationSpec.get(annotation));
            }
            fields.add(fieldBuilder.build());
        }
        return fields;
    }

    private TypeSpec generateSubmitter(
        TypeElement jobClass,
        ExecutableElement entryMethod,
        List<FieldSpec> depFields
    ) {
        var jobClassName = ClassName.get(jobClass);
        var submitterName = jobClass.getSimpleName() + "Submitter";
        var returnType = TypeName.get(entryMethod.getReturnType());
        var awaitableType = ParameterizedTypeName.get(ClassName.get(Awaitable.class), returnType.box());
        var runtimeType = ClassName.get(DurableJobRuntime.class);
        var jobSpecType = ClassName.get(JobSpec.class);

        var allFields = new ArrayList<>(depFields);
        allFields.add(FieldSpec.builder(runtimeType, "runtime", Modifier.PRIVATE, Modifier.FINAL).build());

        var constructor = buildConstructor(allFields);
        var submitMethod = buildSubmitMethod(submitterName, entryMethod, awaitableType, jobSpecType);
        var executeMethod = buildExecuteMethod(jobClassName, entryMethod, depFields, returnType);

        var classBuilder = TypeSpec.classBuilder(submitterName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"));

        allFields.forEach(classBuilder::addField);
        classBuilder.addMethod(constructor);
        classBuilder.addMethod(submitMethod);
        classBuilder.addMethod(executeMethod);

        return classBuilder.build();
    }

    private MethodSpec buildConstructor(List<FieldSpec> fields) {
        var builder = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for (var field : fields) {
            builder.addParameter(field.type(), field.name());
            builder.addStatement("this.$N = $N", field.name(), field.name());
        }
        return builder.build();
    }

    private MethodSpec buildSubmitMethod(
        String submitterName,
        ExecutableElement entryMethod,
        ParameterizedTypeName awaitableType,
        ClassName jobSpecType
    ) {
        var builder = MethodSpec.methodBuilder("submit")
            .addModifiers(Modifier.PUBLIC)
            .returns(awaitableType);

        for (var param : entryMethod.getParameters()) {
            builder.addParameter(TypeName.get(param.asType()), param.getSimpleName().toString());
        }

        var argsList = entryMethod.getParameters().stream()
            .map(p -> p.getSimpleName().toString())
            .collect(Collectors.joining(", "));

        // Bean name is the uncapitalized class name (Spring convention)
        var beanName = Character.toLowerCase(submitterName.charAt(0)) + submitterName.substring(1);

        builder.addStatement("var spec = new $T($S, $S, new Object[]{$L})",
            jobSpecType, beanName, "executeEntry", argsList);
        builder.addStatement("return runtime.submit(spec)");

        return builder.build();
    }

    private MethodSpec buildExecuteMethod(
        ClassName jobClassName,
        ExecutableElement entryMethod,
        List<FieldSpec> depFields,
        TypeName returnType
    ) {
        var builder = MethodSpec.methodBuilder("executeEntry")
            .addModifiers(Modifier.PUBLIC)
            .returns(returnType);

        for (var param : entryMethod.getParameters()) {
            builder.addParameter(TypeName.get(param.asType()), param.getSimpleName().toString());
        }

        if (!entryMethod.getThrownTypes().isEmpty()) {
            builder.addException(Exception.class);
        }

        var depArgs = depFields.stream()
            .map(FieldSpec::name)
            .collect(Collectors.joining(", "));

        var entryArgs = entryMethod.getParameters().stream()
            .map(p -> p.getSimpleName().toString())
            .collect(Collectors.joining(", "));

        builder.addStatement("var job = new $T($L)", jobClassName, depArgs);
        builder.addStatement("return job.$N($L)", entryMethod.getSimpleName().toString(), entryArgs);

        return builder.build();
    }
}
