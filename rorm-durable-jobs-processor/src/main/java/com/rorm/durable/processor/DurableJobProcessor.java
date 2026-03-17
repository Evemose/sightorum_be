package com.rorm.durable.processor;

import com.palantir.javapoet.*;
import com.rorm.durable.DurableJob;
import com.rorm.durable.DurableRuntime;
import com.rorm.durable.JobEntry;
import com.rorm.durable.JobSpec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
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

        var submitter = generateSubmitter(jobClass, entryMethod);
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

    private TypeSpec generateSubmitter(TypeElement jobClass, ExecutableElement entryMethod) {
        var submitterName = jobClass.getSimpleName() + "Submitter";
        var beanName = Character.toLowerCase(submitterName.charAt(0)) + submitterName.substring(1);
        var returnType = TypeName.get(entryMethod.getReturnType());
        var runtimeField = FieldSpec.builder(
            ClassName.get(DurableRuntime.class), "runtime", Modifier.PRIVATE, Modifier.FINAL
        ).build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ClassName.get(DurableRuntime.class), "runtime")
            .addStatement("this.runtime = runtime")
            .build();

        var submitMethod = buildSubmitMethod(jobClass, entryMethod, returnType);

        return TypeSpec.classBuilder(submitterName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("org.springframework.stereotype", "Component"))
            .addField(runtimeField)
            .addMethod(constructor)
            .addMethod(submitMethod)
            .build();
    }

    private MethodSpec buildSubmitMethod(
        TypeElement jobClass, ExecutableElement entryMethod, TypeName returnType
    ) {
        var builder = MethodSpec.methodBuilder("submit")
            .addModifiers(Modifier.PUBLIC)
            .returns(returnType);

        for (var param : entryMethod.getParameters()) {
            builder.addParameter(TypeName.get(param.asType()), param.getSimpleName().toString());
        }

        var beanName = Character.toLowerCase(jobClass.getSimpleName().charAt(0))
                       + jobClass.getSimpleName().toString().substring(1);

        var argsList = entryMethod.getParameters().stream()
            .map(p -> p.getSimpleName().toString())
            .collect(Collectors.joining(", "));

        builder.addStatement("var spec = new $T($S, $S, new Object[]{$L})",
            ClassName.get(JobSpec.class), beanName, entryMethod.getSimpleName().toString(), argsList);
        builder.addStatement("return ($T) runtime.submit(spec)", returnType);

        return builder.build();
    }
}
