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
package org.codehaus.groovy.transform.sc.transformers;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.tools.WideningCategories;
import org.codehaus.groovy.classgen.asm.sc.StaticPropertyAccessHelper;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.transform.sc.ListOfExpressionsExpression;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.apache.groovy.ast.tools.ExpressionUtils.isNullConstant;
import static org.codehaus.groovy.ast.tools.GeneralUtils.args;
import static org.codehaus.groovy.ast.tools.GeneralUtils.binX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.boolX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.classX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.constX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.isOrImplements;
import static org.codehaus.groovy.ast.tools.GeneralUtils.nullX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.ternaryX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.varX;
import static org.codehaus.groovy.ast.ClassHelper.isBigDecimalType;
import static org.codehaus.groovy.ast.ClassHelper.isBigIntegerType;
import static org.codehaus.groovy.ast.ClassHelper.isWrapperByte;
import static org.codehaus.groovy.ast.ClassHelper.isWrapperDouble;
import static org.codehaus.groovy.ast.ClassHelper.isWrapperFloat;
import static org.codehaus.groovy.ast.ClassHelper.isWrapperInteger;
import static org.codehaus.groovy.ast.ClassHelper.isWrapperLong;
import static org.codehaus.groovy.ast.ClassHelper.isWrapperShort;

public class BinaryExpressionTransformer {
    private static final MethodNode COMPARE_TO_METHOD = ClassHelper.COMPARABLE_TYPE.getMethods("compareTo").get(0);
    private static final ConstantExpression CONSTANT_MINUS_ONE = constX(-1, true);
    private static final ConstantExpression CONSTANT_ZERO = constX(0, true);
    private static final ConstantExpression CONSTANT_ONE = constX(1, true);

    private int tmpVarCounter;

    private final StaticCompilationTransformer staticCompilationTransformer;

    public BinaryExpressionTransformer(final StaticCompilationTransformer staticCompilationTransformer) {
        this.staticCompilationTransformer = staticCompilationTransformer;
    }

    public Expression transformBinaryExpression(final BinaryExpression bin) {
        if (bin instanceof DeclarationExpression) {
            Expression optimized = transformDeclarationExpression(bin);
            if (optimized != null) {
                return optimized;
            }
        }

        Token operation = bin.getOperation();
        int operationType = operation.getType();
        Expression leftExpression = bin.getLeftExpression();
        Expression rightExpression = bin.getRightExpression();
        if (bin instanceof DeclarationExpression
                && leftExpression instanceof VariableExpression
                && rightExpression instanceof ConstantExpression) {
            ClassNode declarationType = ((VariableExpression) leftExpression).getOriginType();
            if (!rightExpression.getType().equals(declarationType)
                    && ClassHelper.getWrapper(declarationType).isDerivedFrom(ClassHelper.Number_TYPE)
                    && WideningCategories.isDoubleCategory(ClassHelper.getUnwrapper(declarationType))) {
                ConstantExpression constant = (ConstantExpression) rightExpression;
                if (!constant.isNullExpression()) {
                    return optimizeConstantInitialization(bin, operation, constant, leftExpression, declarationType);
                }
            }
        }
        if (operationType == Types.ASSIGN) {
            // GROOVY-10029: add "?.toArray(new T[0])" to "T[] array = collectionOfT" assignments
            ClassNode leftType = findType(leftExpression), rightType = findType(rightExpression);
            if (leftType.isArray() && !(rightExpression instanceof ListExpression) && isOrImplements(rightType, ClassHelper.COLLECTION_TYPE)) {
                ArrayExpression emptyArray = new ArrayExpression(leftType.getComponentType(), null, Collections.singletonList(CONSTANT_ZERO));
                rightExpression = callX(rightExpression, "toArray", args(emptyArray));
                rightExpression.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, leftType);
                ((MethodCallExpression) rightExpression).setMethodTarget(
                        rightType.getMethod("toArray", new Parameter[]{new Parameter(ClassHelper.OBJECT_TYPE.makeArray(), "a")}));
                ((MethodCallExpression) rightExpression).setImplicitThis(false);
                ((MethodCallExpression) rightExpression).setSafe(true);
            }

            MethodNode directMCT = leftExpression.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
            if (directMCT != null) {
                Expression left = staticCompilationTransformer.transform(leftExpression);
                Expression right = staticCompilationTransformer.transform(rightExpression);
                if (left instanceof PropertyExpression) {
                    // transform "a.x = val" into "def tmp = val; a.setX(tmp); tmp"
                    PropertyExpression pe = (PropertyExpression) left;
                    return transformAssignmentToSetterCall(
                            pe.getObjectExpression(), // "a"
                            directMCT, // "setX"
                            right, // "val"
                            false,
                            pe.isSafe(),
                            pe.getProperty(), // "x"
                            bin // "a.x = val"
                    );
                } else if (left instanceof VariableExpression) {
                    // transform "x = val" into "def tmp = val; this.setX(tmp); tmp"
                    return transformAssignmentToSetterCall(
                            varX("this"),
                            directMCT, // "setX"
                            right, // "val"
                            true,
                            false,
                            left, // "x"
                            bin // "x = val"
                    );
                }
            }

            // if not transformed to setter call but RHS has been transformed...
            if (rightExpression != bin.getRightExpression()) {
                bin.setRightExpression(rightExpression);
            }
        } else if (operationType == Types.COMPARE_EQUAL || operationType == Types.COMPARE_NOT_EQUAL) {
            // let's check if one of the operands is the null constant
            CompareToNullExpression compareToNullExpression = null;
            if (isNullConstant(leftExpression)) {
                compareToNullExpression = new CompareToNullExpression(staticCompilationTransformer.transform(rightExpression), operationType == Types.COMPARE_EQUAL);
            } else if (isNullConstant(rightExpression)) {
                compareToNullExpression = new CompareToNullExpression(staticCompilationTransformer.transform(leftExpression), operationType == Types.COMPARE_EQUAL);
            }
            if (compareToNullExpression != null) {
                compareToNullExpression.setSourcePosition(bin);
                return compareToNullExpression;
            }
        } else if (operationType == Types.KEYWORD_IN) {
            return staticCompilationTransformer.transform(convertInOperatorToTernary(bin, rightExpression, leftExpression));
        }

        Object[] list = bin.getNodeMetaData(StaticCompilationMetadataKeys.BINARY_EXP_TARGET);
        if (list != null) {
            MethodCallExpression call;
            Expression left = staticCompilationTransformer.transform(leftExpression);
            Expression right = staticCompilationTransformer.transform(rightExpression);

            if (operationType == Types.COMPARE_TO
                    && findType(left).implementsInterface(ClassHelper.COMPARABLE_TYPE)
                    && findType(right).implementsInterface(ClassHelper.COMPARABLE_TYPE)) {
                call = callX(left, "compareTo", args(right));
                call.setImplicitThis(false);
                call.setMethodTarget(COMPARE_TO_METHOD);
                call.setSourcePosition(bin);

                // right == null ? 1 : left.compareTo(right)
                Expression expr = ternaryX(
                        boolX(new CompareToNullExpression(right, true)),
                        CONSTANT_ONE,
                        call
                );
                expr.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, ClassHelper.int_TYPE);

                // left == null ? -1 : (right == null ? 1 : left.compareTo(right))
                expr = ternaryX(
                        boolX(new CompareToNullExpression(left, true)),
                        CONSTANT_MINUS_ONE,
                        expr
                );
                expr.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, ClassHelper.int_TYPE);

                // left === right ? 0 : (left == null ? -1 : (right == null ? 1 : left.compareTo(right)))
                expr = ternaryX(
                        boolX(new CompareIdentityExpression(left, right)),
                        CONSTANT_ZERO,
                        expr
                );
                expr.putNodeMetaData(StaticTypesMarker.INFERRED_TYPE, ClassHelper.int_TYPE);

                return expr;
            }

            Expression optimized = tryOptimizeCharComparison(left, right, bin);
            if (optimized != null) {
                optimized.removeNodeMetaData(StaticCompilationMetadataKeys.BINARY_EXP_TARGET);
                optimized.removeNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
                return optimized;
            }

            String name = (String) list[1];
            MethodNode node = (MethodNode) list[0];
            boolean isAssignment = Types.isAssignment(operationType);
            Expression expr = left; // TODO: if (isAssignment) scrub source offsets from new copy of left?
            MethodNode adapter = StaticCompilationTransformer.BYTECODE_BINARY_ADAPTERS.get(operationType);
            if (adapter != null) {
                Expression sba = classX(StaticCompilationTransformer.BYTECODE_ADAPTER_CLASS);
                call = callX(sba, adapter.getName(), args(expr, right));
                call.setMethodTarget(adapter);
            } else {
                call = callX(expr, name, args(right));
                call.setMethodTarget(node);
            }
            call.setImplicitThis(false);
            if (!isAssignment) {
                call.setSourcePosition(bin);
                return call;
            }
            // case of +=, -=, /=, ...
            // the method represents the operation type only, and we must add an assignment
            expr = binX(left, Token.newSymbol(Types.ASSIGN, operation.getStartLine(), operation.getStartColumn()), call);
            expr.setSourcePosition(bin);
            return expr;
        }
        if (operationType == Types.ASSIGN && leftExpression instanceof TupleExpression && rightExpression instanceof ListExpression) {
            // multiple assignment
            ListOfExpressionsExpression cle = new ListOfExpressionsExpression();
            boolean isDeclaration = (bin instanceof DeclarationExpression);
            List<Expression> leftExpressions = ((TupleExpression) leftExpression).getExpressions();
            List<Expression> rightExpressions = ((ListExpression) rightExpression).getExpressions();
            Iterator<Expression> leftIt = leftExpressions.iterator();
            Iterator<Expression> rightIt = rightExpressions.iterator();
            if (isDeclaration) {
                while (leftIt.hasNext()) {
                    Expression left = leftIt.next();
                    if (rightIt.hasNext()) {
                        Expression right = rightIt.next();
                        BinaryExpression bexp = new DeclarationExpression(left, operation, right);
                        bexp.setSourcePosition(right);
                        cle.addExpression(bexp);
                    }
                }
            } else {
                // (next, result) = [ result, next+result ]
                // -->
                // def tmp1 = result
                // def tmp2 = next+result
                // next = tmp1
                // result = tmp2
                int size = rightExpressions.size();
                List<Expression> tmpAssignments = new ArrayList<>(size);
                List<Expression> finalAssignments = new ArrayList<>(size);
                for (int i = 0, n = Math.min(size, leftExpressions.size()); i < n; i += 1) {
                    Expression left = leftIt.next();
                    Expression right = rightIt.next();
                    VariableExpression tmpVar = varX("$tmpVar$" + tmpVarCounter++);
                    BinaryExpression bexp = new DeclarationExpression(tmpVar, operation, right);
                    bexp.setSourcePosition(right);
                    tmpAssignments.add(bexp);
                    bexp = binX(left, operation, varX(tmpVar));
                    bexp.setSourcePosition(left);
                    finalAssignments.add(bexp);
                }
                for (Expression tmpAssignment : tmpAssignments) {
                    cle.addExpression(tmpAssignment);
                }
                for (Expression finalAssignment : finalAssignments) {
                    cle.addExpression(finalAssignment);
                }
            }
            return staticCompilationTransformer.transform(cle);
        }
        return staticCompilationTransformer.superTransform(bin);
    }

    private ClassNode findType(final Expression expression) {
        ClassNode classNode = staticCompilationTransformer.getClassNode();
        return staticCompilationTransformer.getTypeChooser().resolveType(expression, classNode);
    }

    private static BinaryExpression tryOptimizeCharComparison(final Expression left, final Expression right, final BinaryExpression bin) {
        int op = bin.getOperation().getType();
        if (StaticTypeCheckingSupport.isCompareToBoolean(op) || op == Types.COMPARE_EQUAL || op == Types.COMPARE_NOT_EQUAL) {
            Character cLeft = tryCharConstant(left);
            Character cRight = tryCharConstant(right);
            if (cLeft != null || cRight != null) {
                Expression oLeft = (cLeft == null ? left : constX(cLeft, true));
                if (oLeft instanceof PropertyExpression && !hasCharType((PropertyExpression)oLeft)) return null;
                oLeft.setSourcePosition(left);
                Expression oRight = (cRight == null ? right : constX(cRight, true));
                if (oRight instanceof PropertyExpression && !hasCharType((PropertyExpression)oRight)) return null;
                oRight.setSourcePosition(right);
                bin.setLeftExpression(oLeft);
                bin.setRightExpression(oRight);
                return bin;
            }
        }
        return null;
    }

    private static boolean hasCharType(PropertyExpression pe) {
        ClassNode inferredType = pe.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE);
        return inferredType != null && ClassHelper.Character_TYPE.equals(ClassHelper.getWrapper(inferredType));
    }

    private static Character tryCharConstant(final Expression expr) {
        if (expr instanceof ConstantExpression && ClassHelper.STRING_TYPE.equals(expr.getType())) {
            String value = (String) ((ConstantExpression) expr).getValue();
            if (value != null && value.length() == 1) {
                return value.charAt(0);
            }
        }
        return null;
    }

    private static Expression transformDeclarationExpression(final BinaryExpression bin) {
        Expression leftExpression = bin.getLeftExpression();
        if (leftExpression instanceof VariableExpression) {
            if (ClassHelper.char_TYPE.equals(((VariableExpression) leftExpression).getOriginType())) {
                Expression rightExpression = bin.getRightExpression();
                Character c = tryCharConstant(rightExpression);
                if (c != null) {
                    Expression ce = constX(c, true);
                    ce.setSourcePosition(rightExpression);
                    bin.setRightExpression(ce);
                    return bin;
                }
            }
        }
        return null;
    }

    private static Expression convertInOperatorToTernary(final BinaryExpression bin, final Expression rightExpression, final Expression leftExpression) {
        MethodCallExpression call = callX(rightExpression, "isCase", leftExpression);
        call.setMethodTarget(bin.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET));
        call.setSourcePosition(bin);
        call.copyNodeMetaData(bin);
        Expression tExp = ternaryX(
                boolX(binX(rightExpression, Token.newSymbol("==", -1, -1), nullX())),
                binX(leftExpression, Token.newSymbol("==", -1, -1), nullX()),
                call
        );
        return tExp;
    }

    private static DeclarationExpression optimizeConstantInitialization(final BinaryExpression originalDeclaration, final Token operation, final ConstantExpression constant, final Expression leftExpression, final ClassNode declarationType) {
        Expression cexp = constX(convertConstant((Number) constant.getValue(), ClassHelper.getWrapper(declarationType)), true);
        cexp.setType(declarationType);
        cexp.setSourcePosition(constant);
        DeclarationExpression result = new DeclarationExpression(
                leftExpression,
                operation,
                cexp
        );
        result.setSourcePosition(originalDeclaration);
        result.copyNodeMetaData(originalDeclaration);
        return result;
    }

    private static Object convertConstant(final Number source, final ClassNode target) {
        if (isWrapperByte(target)) {
            return source.byteValue();
        }
        if (isWrapperShort(target)) {
            return source.shortValue();
        }
        if (isWrapperInteger(target)) {
            return source.intValue();
        }
        if (isWrapperLong(target)) {
            return source.longValue();
        }
        if (isWrapperFloat(target)) {
            return source.floatValue();
        }
        if (isWrapperDouble(target)) {
            return source.doubleValue();
        }
        if (isBigIntegerType(target)) {
            return DefaultGroovyMethods.asType(source, BigInteger.class);
        }
        if (isBigDecimalType(target)) {
            return DefaultGroovyMethods.asType(source, BigDecimal.class);
        }
        throw new IllegalArgumentException("Unsupported conversion");
    }

    /**
     * Adapter for {@link StaticPropertyAccessHelper#transformToSetterCall}.
     */
    private static Expression transformAssignmentToSetterCall(
            final Expression receiver,
            final MethodNode setterMethod,
            final Expression valueExpression,
            final boolean implicitThis,
            final boolean safeNavigation,
            final Expression nameExpression,
            final Expression binaryExpression) {
        // expression that will transfer assignment and name positions
        Expression pos = new PropertyExpression(null, nameExpression);
        pos.setSourcePosition(binaryExpression);

        return StaticPropertyAccessHelper.transformToSetterCall(
                receiver,
                setterMethod,
                valueExpression,
                implicitThis,
                safeNavigation,
                false, // spreadSafe
                true, // TODO: replace with a proper test whether a return value is required or not
                pos
        );
    }
}
