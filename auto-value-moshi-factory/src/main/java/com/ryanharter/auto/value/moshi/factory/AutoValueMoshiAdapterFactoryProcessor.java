package com.ryanharter.auto.value.moshi.factory;

import com.google.auto.common.GeneratedAnnotationSpecs;
import com.google.auto.common.Visibility;
import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.ryanharter.auto.value.moshi.AutoValueMoshiExtension;
import com.ryanharter.auto.value.moshi.MoshiAdapterFactory;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;

import static com.google.auto.common.MoreElements.getPackage;
import static java.util.stream.Collectors.toList;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.tools.Diagnostic.Kind.ERROR;
import static net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.AGGREGATING;

/**
 * Annotation Processor responsible for the generation of the {@link JsonAdapter.Factory} class
 * annotated with {@link MoshiAdapterFactory}.
 *
 * @author rharter
 */
@IncrementalAnnotationProcessor(AGGREGATING)
@AutoService(Processor.class)
public final class AutoValueMoshiAdapterFactoryProcessor extends AbstractProcessor {
  private static final ClassName ADAPTER_CLASS_NAME = ClassName.get(JsonAdapter.class);
  private static final ParameterSpec TYPE_SPEC = ParameterSpec.builder(Type.class, "type").build();
  private static final WildcardTypeName WILDCARD_TYPE_NAME =
      WildcardTypeName.subtypeOf(Annotation.class);
  private static final ParameterSpec ANNOTATIONS_SPEC = ParameterSpec
      .builder(
          ParameterizedTypeName.get(ClassName.get(Set.class), WILDCARD_TYPE_NAME), "annotations")
      .build();
  private static final ParameterSpec MOSHI_SPEC =
      ParameterSpec.builder(Moshi.class, "moshi").build();
  private static final ParameterizedTypeName FACTORY_RETURN_TYPE_NAME =
      ParameterizedTypeName.get(ADAPTER_CLASS_NAME, WildcardTypeName.subtypeOf(TypeName.OBJECT));

  private Types typeUtils;
  private Elements elementUtils;

  @Override public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(AutoValue.class.getName(), MoshiAdapterFactory.class.getName());
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    typeUtils = processingEnv.getTypeUtils();
    elementUtils = processingEnv.getElementUtils();
  }

  @Override public boolean process(Set<? extends TypeElement> annotations,
      RoundEnvironment roundEnv) {
    Set<? extends Element> adapterFactories =
        roundEnv.getElementsAnnotatedWith(MoshiAdapterFactory.class);
    if (adapterFactories.isEmpty()) {
      return false;
    }
    Set<? extends Element> autoValueElements = roundEnv.getElementsAnnotatedWith(AutoValue.class);
    List<TypeElement> elements = roundEnv.getElementsAnnotatedWith(AutoValue.class)
        .stream()
        .map(e -> (TypeElement) e)
        .filter(e ->  AutoValueMoshiExtension.isApplicable(e, processingEnv.getMessager()))
        .sorted((o1, o2) -> {
          final String o1Name = classNameOf(o1);
          final String o2Name = classNameOf(o2);
          return o1Name.compareTo(o2Name);
        })
        .collect(toList());

    if (elements.isEmpty()) {
      Element reportableElement = adapterFactories.iterator().next();
      if (!autoValueElements.isEmpty()) {
        processingEnv.getMessager().printMessage(ERROR,
            "Failed to write JsonAdapter.Factory: Cannot generate class for this "
                + "@MoshiAdapterFactory-annotated element because while @AutoValue-annotated "
                + "elements were found on the compilation classpath, none of them contain a "
                + "requisite public static JsonAdapter-returning signature method to opt in to "
                + "being included in @MoshiAdapterFactory-generated factories. See the "
                + "auto-value-moshi README for more information on declaring these.",
            reportableElement);
      } else {
        processingEnv.getMessager().printMessage(ERROR,
            "Failed to write JsonAdapter.Factory: Cannot generate class for this "
                + "@MoshiAdapterFactory-annotated element because no @AutoValue-annotated "
                + "elements were found on the compilation classpath.",
            reportableElement);
      }
      return false;
    }

    for (Element element : adapterFactories) {
      if (!element.getModifiers().contains(ABSTRACT)) {
        error(element, "Must be abstract!");
      }
      // Safe to cast because this is only applicable on types anyway
      TypeElement type = (TypeElement) element;
      if (!implementsJsonAdapterFactory(type)) {
        error(element, "Must implement JsonAdapter.Factory!");
      }
      String adapterName = classNameOf(type);
      PackageElement packageElement = packageElementOf(type);
      String packageName = packageElement.getQualifiedName().toString();

      List<TypeElement> applicableElements = elements.stream()
          .filter(e -> {
            Visibility typeVisibility = Visibility.ofElement(e);
            switch (typeVisibility) {
              case PRIVATE:
                return false;
              case DEFAULT:
              case PROTECTED:
                //noinspection UnstableApiUsage
                if (!getPackage(e).equals(packageElement)) {
                  return false;
                }
                break;
            }
            // If we got here, the class is visible. Now check the jsonAdapter method
            ExecutableElement adapterMethod = getJsonAdapterMethod(e);
            if (adapterMethod == null) {
              return false;
            }
            Visibility methodVisibility = Visibility.ofElement(adapterMethod);
            switch (methodVisibility) {
              case PRIVATE:
                return false;
              case DEFAULT:
              case PROTECTED:
                //noinspection UnstableApiUsage
                if (!getPackage(adapterMethod).equals(packageElement)) {
                  return false;
                }
                break;
            }
            return true;
          })
          .collect(toList());

      MoshiAdapterFactory annotation = element.getAnnotation(MoshiAdapterFactory.class);
      boolean requestNullSafeAdapters = annotation.nullSafe();

      TypeSpec jsonAdapterFactory = createJsonAdapterFactory(type,
          applicableElements,
          packageName,
          adapterName,
          requestNullSafeAdapters);
      JavaFile file = JavaFile.builder(packageName, jsonAdapterFactory).build();
      try {
        file.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        processingEnv.getMessager()
            .printMessage(ERROR,
                "Failed to write JsonAdapter.Factory: " + e.getLocalizedMessage());
      }
    }

    return false;
  }

  private TypeSpec createJsonAdapterFactory(TypeElement sourceElement,
      List<TypeElement> elements,
      String packageName,
      String factoryName,
      boolean requestNullSafeAdapters) {
    TypeSpec.Builder factory =
        TypeSpec.classBuilder(ClassName.get(packageName, "AutoValueMoshi_" + factoryName));
    factory.addOriginatingElement(sourceElement);
    if (sourceElement.getModifiers().contains(PUBLIC)) {
      factory.addModifiers(PUBLIC);
    }
    factory.addModifiers(FINAL);
    factory.superclass(ClassName.get(sourceElement));

    GeneratedAnnotationSpecs.generatedAnnotationSpec(
        processingEnv.getElementUtils(),
        processingEnv.getSourceVersion(),
        AutoValueMoshiAdapterFactoryProcessor.class,
        AutoValueMoshiExtension.GENERATED_COMMENTS
    ).ifPresent(factory::addAnnotation);

    ParameterSpec type = TYPE_SPEC;
    ParameterSpec annotations = ANNOTATIONS_SPEC;
    ParameterSpec moshi = MOSHI_SPEC;

    MethodSpec.Builder create = MethodSpec.methodBuilder("create")
        .addModifiers(PUBLIC)
        .addAnnotation(Override.class)
        .addParameters(ImmutableSet.of(type, annotations, moshi))
        .returns(FACTORY_RETURN_TYPE_NAME);

    CodeBlock.Builder classes = null;
    CodeBlock.Builder generics = null;

    int numGenerics = 0;
    int numClasses = 0;

    // Avoid providing an adapter for an annotated type.
    create.addStatement("if (!$N.isEmpty()) return null", annotations)
        .addStatement("$T rawType = $T.getRawType($N)",
            ParameterizedTypeName.get(ClassName.get(Class.class),
                WildcardTypeName.subtypeOf(Object.class)),
            com.squareup.moshi.Types.class,
            type);

    for (Element element : elements) {
      factory.addOriginatingElement(element);
      TypeName elementTypeName = TypeName.get(element.asType());

      if (elementTypeName instanceof ParameterizedTypeName) {
        if (generics == null) {
          generics = CodeBlock.builder()
              .beginControlFlow("if ($N instanceof $T)", type, ParameterizedType.class);
        }

        addControlFlowGeneric(generics,
            elementTypeName,
            element,
            numGenerics,
            requestNullSafeAdapters);
        numGenerics++;
      } else {
        if (classes == null) {
          classes = CodeBlock.builder();
        }

        addControlFlow(classes, elementTypeName, numClasses);
        numClasses++;

        ExecutableElement jsonAdapterMethod = getJsonAdapterMethod(element);
        CodeBlock moshiArgBlock = jsonAdapterMethod.getParameters().size() == 0
            ? CodeBlock.of("")
            : CodeBlock.of("$N", moshi);
        String returnStatement =
            requestNullSafeAdapters ? "return $T.$L($L).nullSafe()" : "return $T.$L($L)";
        classes.addStatement(
            returnStatement,
            element,
            jsonAdapterMethod.getSimpleName(),
            moshiArgBlock);
      }
    }

    if (generics != null) {
      generics.endControlFlow();
      generics.addStatement("return null");
      generics.endControlFlow();
      create.addCode(generics.build());
    }

    if (classes != null) {
      classes.endControlFlow();
      create.addCode(classes.build());
    }

    create.addStatement("return null");
    factory.addMethod(create.build());
    return factory.build();
  }

  private void addControlFlowGeneric(CodeBlock.Builder block, TypeName elementTypeName,
      Element element, int numGenerics, boolean requestNullSafeAdapters) {
    TypeName typeName = ((ParameterizedTypeName) elementTypeName).rawType;
    addControlFlow(block, typeName, numGenerics);

    String returnStatement = requestNullSafeAdapters
        ? "return $L.$L($N, (($T) $N).getActualTypeArguments()).nullSafe()"
        : "return $L.$L($N, (($T) $N).getActualTypeArguments())";

    ExecutableElement jsonAdapterMethod = getJsonAdapterMethod(element);
    if (jsonAdapterMethod.getParameters().size() > 1) {
      block.addStatement(returnStatement,
          element.getSimpleName(), jsonAdapterMethod.getSimpleName(), MOSHI_SPEC,
          ParameterizedType.class, TYPE_SPEC);
    }
  }

  private void addControlFlow(CodeBlock.Builder block,
      TypeName elementTypeName, int pos) {
    if (pos == 0) {
      block.beginControlFlow("if ($T.class.isAssignableFrom(rawType))", elementTypeName);
    } else {
      block.nextControlFlow("else if ($T.class.isAssignableFrom(rawType))", elementTypeName);
    }
  }

  private ExecutableElement getJsonAdapterMethod(Element element) {
    ParameterizedTypeName jsonAdapterType = ParameterizedTypeName.get(
        ClassName.get(JsonAdapter.class), TypeName.get(element.asType()));
    for (ExecutableElement method : ElementFilter.methodsIn(element.getEnclosedElements())) {
      if (method.getModifiers().contains(STATIC) && !method.getModifiers().contains(PRIVATE)) {
        TypeMirror rType = method.getReturnType();
        TypeName returnType = TypeName.get(rType);
        if (returnType.equals(jsonAdapterType)) {
          return method;
        }
      }
    }
    return null;
  }

  private void error(Element element, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    processingEnv.getMessager().printMessage(ERROR, message, element);
  }

  private boolean implementsJsonAdapterFactory(TypeElement type) {
    TypeMirror jsonAdapterType
        = elementUtils.getTypeElement(JsonAdapter.Factory.class.getCanonicalName()).asType();
    TypeMirror typeMirror = type.asType();
    if (!type.getInterfaces().isEmpty() || typeMirror.getKind() != TypeKind.NONE) {
      while (typeMirror.getKind() != TypeKind.NONE) {
        if (searchInterfacesAncestry(typeMirror, jsonAdapterType)) {
          return true;
        }
        type = (TypeElement) typeUtils.asElement(typeMirror);
        typeMirror = type.getSuperclass();
      }
    }
    return false;
  }

  private boolean searchInterfacesAncestry(TypeMirror rootIface, TypeMirror target) {
    TypeElement rootIfaceElement = (TypeElement) typeUtils.asElement(rootIface);
    // check if it implements valid interfaces
    for (TypeMirror iface : rootIfaceElement.getInterfaces()) {
      TypeElement ifaceElement = (TypeElement) typeUtils.asElement(rootIface);
      while (iface.getKind() != TypeKind.NONE) {
        if (typeUtils.isSameType(iface, target)) {
          return true;
        }
        // go up
        if (searchInterfacesAncestry(iface, target)) {
          return true;
        }
        // then move on
        iface = ifaceElement.getSuperclass();
      }
    }
    return false;
  }

  /**
   * Returns the name of the given type, including any enclosing types but not the package.
   */
  private static String classNameOf(TypeElement type) {
    return Joiner.on("_").join(ClassName.get(type).simpleNames());
  }

  /**
   * Returns the package element that the given type is in. If the type is in the default
   * (unnamed) package then the name is the empty string.
   */
  private static PackageElement packageElementOf(TypeElement type) {
    //noinspection UnstableApiUsage
    return getPackage(type);
  }
}
