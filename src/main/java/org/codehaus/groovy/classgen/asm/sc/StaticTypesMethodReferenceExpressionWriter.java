/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.classgen.asm.sc;

import groovy.lang.Tuple;
import groovy.lang.Tuple2;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodReferenceExpression;
import org.codehaus.groovy.ast.tools.GeneralUtils;
import org.codehaus.groovy.classgen.asm.BytecodeHelper;
import org.codehaus.groovy.classgen.asm.MethodReferenceExpressionWriter;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.codehaus.groovy.syntax.RuntimeParserException;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.groovy.ast.tools.ClassNodeUtils.addGeneratedMethod;
import static org.codehaus.groovy.ast.tools.GeneralUtils.args;
import static org.codehaus.groovy.ast.tools.GeneralUtils.block;
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.classX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.ctorX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.nullX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.returnS;
import static org.codehaus.groovy.ast.tools.ParameterUtils.parametersCompatible;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.filterMethodsByVisibility;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.findDGMMethodsForClassNode;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.isAssignableTo;

/**
 * Generates bytecode for method reference expressions in statically-compiled code.
 *
 * @since 3.0.0
 */
public class StaticTypesMethodReferenceExpressionWriter extends MethodReferenceExpressionWriter implements AbstractFunctionalInterfaceWriter {

    public StaticTypesMethodReferenceExpressionWriter(final WriterController controller) {
        super(controller);
    }

    @Override
    public void writeMethodReferenceExpression(final MethodReferenceExpression methodReferenceExpression) {
        ClassNode functionalInterfaceType = getFunctionalInterfaceType(methodReferenceExpression);
        if (!ClassHelper.isFunctionalInterface(functionalInterfaceType)) {
            // generate the default bytecode; most likely a method closure
            super.writeMethodReferenceExpression(methodReferenceExpression);
            return;
        }

        ClassNode redirect = functionalInterfaceType.redirect();
        MethodNode abstractMethod = ClassHelper.findSAM(redirect);
        String abstractMethodDesc = createMethodDescriptor(abstractMethod);

        ClassNode classNode = controller.getClassNode();
        Expression typeOrTargetRef = methodReferenceExpression.getExpression();
        boolean isClassExpression = (typeOrTargetRef instanceof ClassExpression);
        ClassNode typeOrTargetRefType = isClassExpression ? typeOrTargetRef.getType()
                : controller.getTypeChooser().resolveType(typeOrTargetRef, classNode);

        ClassNode[] methodReferenceParamTypes = methodReferenceExpression.getNodeMetaData(StaticTypesMarker.CLOSURE_ARGUMENTS);
        Parameter[] parametersWithExactType = createParametersWithExactType(abstractMethod, methodReferenceParamTypes);
        String methodRefName = methodReferenceExpression.getMethodName().getText();
        boolean isConstructorReference = isConstructorReference(methodRefName);

        MethodNode methodRefMethod;
        if (isConstructorReference) {
            methodRefName = controller.getContext().getNextConstructorReferenceSyntheticMethodName(controller.getMethodNode());
            methodRefMethod = addSyntheticMethodForConstructorReference(methodRefName, typeOrTargetRefType, parametersWithExactType);
        } else {
            // TODO: move the findMethodRefMethod and checking to StaticTypeCheckingVisitor
            methodRefMethod = findMethodRefMethod(methodRefName, parametersWithExactType, typeOrTargetRef, typeOrTargetRefType);
        }

        validate(methodReferenceExpression, typeOrTargetRef, typeOrTargetRefType, methodRefName, parametersWithExactType, methodRefMethod);

        if (isExtensionMethod(methodRefMethod)) {
            ExtensionMethodNode extensionMethodNode = (ExtensionMethodNode) methodRefMethod;
            methodRefMethod = extensionMethodNode.getExtensionMethodNode();
            if (extensionMethodNode.isStaticExtension()) {
                methodRefMethod = addSyntheticMethodForDGSM(methodRefMethod);
            }

            typeOrTargetRefType = methodRefMethod.getDeclaringClass();
            Expression classExpression = classX(typeOrTargetRefType);
            classExpression.setSourcePosition(typeOrTargetRef);
            typeOrTargetRef = classExpression;
        }

        methodRefMethod.putNodeMetaData(ORIGINAL_PARAMETERS_WITH_EXACT_TYPE, parametersWithExactType);

        if (!isClassExpression) {
            if (isConstructorReference) {
                // TODO: move the checking code to the parser
                addFatalError("Constructor reference must be className::new", methodReferenceExpression);
            } else if (methodRefMethod.isStatic()) {
                ClassExpression classExpression = classX(typeOrTargetRefType);
                classExpression.setSourcePosition(typeOrTargetRef);
                typeOrTargetRef = classExpression;
                isClassExpression = true;
            } else {
                typeOrTargetRef.visit(controller.getAcg());
            }
        }

        controller.getMethodVisitor().visitInvokeDynamicInsn(
                abstractMethod.getName(),
                createAbstractMethodDesc(functionalInterfaceType, typeOrTargetRef),
                createBootstrapMethod(classNode.isInterface(), false),
                createBootstrapMethodArguments(
                        abstractMethodDesc,
                        methodRefMethod.isStatic() || isConstructorReference ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL,
                        isConstructorReference ? controller.getClassNode() : typeOrTargetRefType,
                        methodRefMethod,
                        false
                )
        );

        if (isClassExpression) {
            controller.getOperandStack().push(redirect);
        } else {
            controller.getOperandStack().replace(redirect, 1);
        }
    }

    private void validate(final MethodReferenceExpression methodReferenceExpression, final Expression typeOrTargetRef, final ClassNode typeOrTargetRefType, final String methodRefName, final Parameter[] parametersWithExactType, final MethodNode methodRefMethod) {
        if (methodRefMethod == null) {
            addFatalError("Failed to find the expected method["
                    + methodRefName + "("
                    + Arrays.stream(parametersWithExactType)
                            .map(e -> e.getType().getText())
                            .collect(Collectors.joining(","))
                    + ")] in the type[" + typeOrTargetRefType.getText() + "]", methodReferenceExpression);
        } else if (parametersWithExactType.length > 0 && isTypeReferingInstanceMethod(typeOrTargetRef, methodRefMethod)) {
            ClassNode firstParameterType = parametersWithExactType[0].getType();
            if (!isAssignableTo(firstParameterType, typeOrTargetRefType)) {
                throw new RuntimeParserException("Invalid receiver type: " + firstParameterType.getText() + " is not compatible with " + typeOrTargetRefType.getText(), typeOrTargetRef);
            }
        }
    }

    private MethodNode addSyntheticMethodForDGSM(final MethodNode mn) {
        Parameter[] parameters = removeFirstParameter(mn.getParameters());
        ArgumentListExpression args = args(parameters);
        args.getExpressions().add(0, nullX());

        MethodCallExpression returnValue = callX(classX(mn.getDeclaringClass()), mn.getName(), args);
        returnValue.putNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET, mn);
        returnValue.setMethodTarget(mn);

        MethodNode delegateMethod = addGeneratedMethod(controller.getClassNode(),
                "dgsm$$" + mn.getParameters()[0].getType().getName().replace('.', '$') + "$$" + mn.getName(),
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                mn.getReturnType(),
                parameters,
                ClassNode.EMPTY_ARRAY,
                block(returnS(returnValue))
        );

        delegateMethod.putNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE, Boolean.TRUE);

        return delegateMethod;
    }

    private MethodNode addSyntheticMethodForConstructorReference(final String syntheticMethodName, final ClassNode returnType, final Parameter[] parametersWithExactType) {
        ArgumentListExpression ctorArgs = args(parametersWithExactType);

        Expression returnValue;
        if (returnType.isArray()) {
            returnValue = new ArrayExpression(
                    returnType.getComponentType(),
                    null, ctorArgs.getExpressions());
        } else {
            returnValue = ctorX(returnType, ctorArgs);
        }

        MethodNode delegateMethod = addGeneratedMethod(controller.getClassNode(),
                syntheticMethodName,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                returnType,
                parametersWithExactType,
                ClassNode.EMPTY_ARRAY,
                block(returnS(returnValue))
        );

        // TODO: if StaticTypesMarker.DIRECT_METHOD_CALL_TARGET or
        // OptimizingStatementWriter.StatementMeta.class metadatas
        // can bet set for the ctorX above, then this can be TRUE:
        delegateMethod.putNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE, Boolean.FALSE);

        return delegateMethod;
    }

    private String createAbstractMethodDesc(final ClassNode functionalInterfaceType, final Expression methodRef) {
        List<Parameter> methodReferenceSharedVariableList = new ArrayList<>();

        if (!(methodRef instanceof ClassExpression)) {
            prependParameter(methodReferenceSharedVariableList, "__METHODREF_EXPR_INSTANCE",
                controller.getTypeChooser().resolveType(methodRef, controller.getClassNode()));
        }

        return BytecodeHelper.getMethodDescriptor(functionalInterfaceType.redirect(), methodReferenceSharedVariableList.toArray(Parameter.EMPTY_ARRAY));
    }

    private Parameter[] createParametersWithExactType(final MethodNode abstractMethod, final ClassNode[] inferredParamTypes) {
        // MUST clone the parameters to avoid impacting the original parameter type of SAM
        Parameter[] parameters = GeneralUtils.cloneParams(abstractMethod.getParameters());

        if (inferredParamTypes != null) {
            for (int i = 0, n = parameters.length; i < n; i += 1) {
                ClassNode inferredParamType = inferredParamTypes[i];
                if (inferredParamType == null) continue;

                Parameter parameter = parameters[i];
                Parameter targetParameter = parameter;

                ClassNode type = convertParameterType(targetParameter.getType(), parameter.getType(), inferredParamType);
                parameter.setOriginType(type);
                parameter.setType(type);
            }
        }

        return parameters;
    }

    private MethodNode findMethodRefMethod(final String methodName, final Parameter[] samParameters, final Expression typeOrTargetRef, final ClassNode typeOrTargetRefType) {
        List<MethodNode> methods = findVisibleMethods(methodName, typeOrTargetRefType);

        return chooseMethodRefMethodCandidate(typeOrTargetRef, methods.stream().filter(method -> {
            Parameter[] parameters = method.getParameters();
            if (isTypeReferingInstanceMethod(typeOrTargetRef, method)) {
                // there is an implicit parameter for "String::length"
                ClassNode firstParamType = method.getDeclaringClass();

                int n = parameters.length;
                Parameter[] plusOne = new Parameter[n + 1];
                plusOne[0] = new Parameter(firstParamType, "");
                System.arraycopy(parameters, 0, plusOne, 1, n);

                parameters = plusOne;
            }
            return parametersCompatible(samParameters, parameters);
        }).collect(Collectors.toList()));
    }

    private List<MethodNode> findVisibleMethods(final String name, final ClassNode type) {
        List<MethodNode> methods = type.getMethods(name);
        methods.addAll(findDGMMethodsForClassNode(controller.getSourceUnit().getClassLoader(), type, name));
        methods = filterMethodsByVisibility(methods, controller.getClassNode());
        return methods;
    }

    private void addFatalError(final String msg, final ASTNode node) {
        controller.getSourceUnit().addFatalError(msg, node);
    }

    //--------------------------------------------------------------------------

    private static boolean isConstructorReference(final String methodRefName) {
        return "new".equals(methodRefName);
    }

    private static boolean isExtensionMethod(final MethodNode methodRefMethod) {
        return (methodRefMethod instanceof ExtensionMethodNode);
    }

    private static boolean isTypeReferingInstanceMethod(final Expression typeOrTargetRef, final MethodNode mn) {
        // class::instanceMethod
        return (typeOrTargetRef instanceof ClassExpression) && ((mn != null && !mn.isStatic())
                || (isExtensionMethod(mn) && !((ExtensionMethodNode) mn).isStaticExtension()));
    }

    private static Parameter[] removeFirstParameter(final Parameter[] parameters) {
        return Arrays.copyOfRange(parameters, 1, parameters.length);
    }

    /**
     * Choose the best method node for method reference.
     */
    private static MethodNode chooseMethodRefMethodCandidate(final Expression methodRef, final List<MethodNode> candidates) {
        if (1 == candidates.size()) return candidates.get(0);

        return candidates.stream()
                .map(e -> Tuple.tuple(e, matchingScore(e, methodRef)))
                .min((t1, t2) -> Integer.compare(t2.getV2(), t1.getV2()))
                .map(Tuple2::getV1)
                .orElse(null);
    }

    private static Integer matchingScore(final MethodNode mn, final Expression typeOrTargetRef) {
        ClassNode typeOrTargetRefType = typeOrTargetRef.getType(); // TODO: pass this type in

        int score = 9;
        for (ClassNode cn = mn.getDeclaringClass(); null != cn && !cn.equals(typeOrTargetRefType); cn = cn.getSuperClass()) {
            score -= 1;
        }
        if (score < 0) {
            score = 0;
        }
        score *= 10;

        if ((typeOrTargetRef instanceof ClassExpression) == mn.isStatic()) {
            score += 9;
        }

        if (isExtensionMethod(mn)) {
            score += 100;
        }

        return score;
    }
}
