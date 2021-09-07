/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.capability.xml.description;

import static org.mule.runtime.module.extension.internal.capability.xml.DocumenterUtils.getParameterGroups;
import static org.mule.runtime.module.extension.internal.capability.xml.DocumenterUtils.getParameters;

import org.mule.runtime.api.meta.model.declaration.fluent.ParameterizedDeclaration;
import org.mule.runtime.module.extension.internal.capability.xml.DocumenterUtils;
import org.mule.runtime.module.extension.internal.capability.xml.schema.MethodDocumentation;

import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * {@link AbstractDescriptionDocumenter} implementation that fills {@link ParameterizedDeclaration}s
 *
 * @since 4.0
 */
final class ParameterDescriptionDocumenter extends AbstractDescriptionDocumenter {

  ParameterDescriptionDocumenter(ProcessingEnvironment processingEnv) {
    super(processingEnv);
  }

  /**
   * Describes parameters that are defined as Method parameters.
   */
  void document(ParameterizedDeclaration<?> parameterized, Element method, MethodDocumentation documentation) {
    parameterized.getAllParameters().forEach(p -> {
      String description = documentation.getParameters().get(p.getName());
      if (description != null) {
        p.setDescription(description);
      }
    });

    if (method instanceof ExecutableElement) {
      ((ExecutableElement) method).getParameters().stream()
          .filter(DocumenterUtils::isParameterGroup)
          .forEach(group -> {
            TypeElement typeElement = (TypeElement) processingEnv.getTypeUtils().asElement(group.asType());
            document(parameterized, typeElement);
          });
    }
  }

  void document(ParameterizedDeclaration<?> parameterized, final TypeElement element) {
    TypeElement traversingElement = element;
    while (traversingElement != null && !Object.class.getName().equals(traversingElement.getQualifiedName().toString())) {

      final Map<String, VariableElement> variableElements = getParameters(element, processor)
          .entrySet()
          .stream()
          .collect(Collectors.toMap(entry -> getNameOrAlias(entry.getValue()),
                                    Map.Entry::getValue));

      parameterized.getAllParameters()
          .stream().filter(param -> variableElements.containsKey(param.getName()))
          .forEach(param -> {
            String summary = processor.getJavaDocSummary(processingEnv, variableElements.get(param.getName()));
            param.setDescription(summary);
          });
      traversingElement = (TypeElement) processingEnv.getTypeUtils().asElement(traversingElement.getSuperclass());
    }

    for (VariableElement variableElement : getParameterGroups(element, processor).values()) {
      TypeElement typeElement = (TypeElement) processingEnv.getTypeUtils().asElement(variableElement.asType());
      document(parameterized, typeElement);
    }
  }
}
