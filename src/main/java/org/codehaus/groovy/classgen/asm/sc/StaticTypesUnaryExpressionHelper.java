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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.classgen.asm.TypeChooser;
import org.codehaus.groovy.classgen.asm.UnaryExpressionHelper;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.objectweb.asm.Label;

import static org.codehaus.groovy.ast.ClassHelper.boolean_TYPE;
import static org.codehaus.groovy.ast.tools.GeneralUtils.bytecodeX;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveBoolean;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveByte;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveChar;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveDouble;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveFloat;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveInt;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveLong;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveShort;
import static org.objectweb.asm.Opcodes.DNEG;
import static org.objectweb.asm.Opcodes.FNEG;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.I2B;
import static org.objectweb.asm.Opcodes.I2C;
import static org.objectweb.asm.Opcodes.I2S;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_M1;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.INEG;
import static org.objectweb.asm.Opcodes.IXOR;
import static org.objectweb.asm.Opcodes.LNEG;
import static org.objectweb.asm.Opcodes.LXOR;

/**
 * An expression helper which generates optimized bytecode depending on the
 * current type on top of the operand stack.
 */
public class StaticTypesUnaryExpressionHelper extends UnaryExpressionHelper {

    private static final BitwiseNegationExpression EMPTY_BITWISE_NEGATE = new BitwiseNegationExpression(EmptyExpression.INSTANCE);
    private static final UnaryMinusExpression EMPTY_UNARY_MINUS = new UnaryMinusExpression(EmptyExpression.INSTANCE);
    private static final UnaryPlusExpression EMPTY_UNARY_PLUS = new UnaryPlusExpression(EmptyExpression.INSTANCE);

    public StaticTypesUnaryExpressionHelper(final WriterController controller) {
        super(controller);
    }

    @Override
    public void writeBitwiseNegate(final BitwiseNegationExpression expression) {
        expression.getExpression().visit(controller.getAcg());
        ClassNode top = controller.getOperandStack().getTopOperand();
        if (isPrimitiveInt(top) || isPrimitiveLong(top) || isPrimitiveShort(top) || isPrimitiveByte(top) || isPrimitiveChar(top)) {
            bytecodeX(mv -> {
                if (isPrimitiveLong(top)) {
                    mv.visitLdcInsn(-1L);
                    mv.visitInsn(LXOR);
                } else {
                    mv.visitInsn(ICONST_M1);
                    mv.visitInsn(IXOR);
                    if (isPrimitiveByte(top)) {
                        mv.visitInsn(I2B);
                    } else if (isPrimitiveChar(top)) {
                        mv.visitInsn(I2C);
                    } else if (isPrimitiveShort(top)) {
                        mv.visitInsn(I2S);
                    }
                }
            }).visit(controller.getAcg());
            controller.getOperandStack().remove(1);
        } else {
            super.writeBitwiseNegate(EMPTY_BITWISE_NEGATE);
        }
    }

    @Override
    public void writeNotExpression(final NotExpression expression) {
        Expression subExpression = expression.getExpression();
        TypeChooser typeChooser = controller.getTypeChooser();
        if (isPrimitiveBoolean(typeChooser.resolveType(subExpression, controller.getClassNode()))) {
            subExpression.visit(controller.getAcg());
            controller.getOperandStack().doGroovyCast(boolean_TYPE);
            bytecodeX(mv -> {
                Label ne = new Label();
                mv.visitJumpInsn(IFNE, ne);
                mv.visitInsn(ICONST_1);
                Label out = new Label();
                mv.visitJumpInsn(GOTO, out);
                mv.visitLabel(ne);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(out);
            }).visit(controller.getAcg());
            controller.getOperandStack().remove(1);
        } else {
            super.writeNotExpression(expression);
        }
    }

    @Override
    public void writeUnaryMinus(final UnaryMinusExpression expression) {
        expression.getExpression().visit(controller.getAcg());
        ClassNode top = controller.getOperandStack().getTopOperand();
        if (isPrimitiveInt(top) || isPrimitiveLong(top) || isPrimitiveShort(top)|| isPrimitiveFloat(top)
                || isPrimitiveDouble(top) || isPrimitiveByte(top) || isPrimitiveChar(top)) {
            bytecodeX(mv -> {
                if (isPrimitiveInt(top) || isPrimitiveShort(top) || isPrimitiveByte(top) || isPrimitiveChar(top)) {
                    mv.visitInsn(INEG);
                    if (isPrimitiveByte(top)) {
                        mv.visitInsn(I2B);
                    } else if (isPrimitiveChar(top)) {
                        mv.visitInsn(I2C);
                    } else if (isPrimitiveShort(top)) {
                        mv.visitInsn(I2S);
                    }
                } else if (isPrimitiveLong(top)) {
                    mv.visitInsn(LNEG);
                } else if (isPrimitiveFloat(top)) {
                    mv.visitInsn(FNEG);
                } else if (isPrimitiveDouble(top)) {
                    mv.visitInsn(DNEG);
                }
            }).visit(controller.getAcg());
            controller.getOperandStack().remove(1);
        } else {
            super.writeUnaryMinus(EMPTY_UNARY_MINUS);
        }
    }

    @Override
    public void writeUnaryPlus(final UnaryPlusExpression expression) {
        expression.getExpression().visit(controller.getAcg());
        ClassNode top = controller.getOperandStack().getTopOperand();
        if (isPrimitiveInt(top) || isPrimitiveLong(top) || isPrimitiveShort(top)|| isPrimitiveFloat(top)
                || isPrimitiveDouble(top) || isPrimitiveByte(top) || isPrimitiveChar(top)) {
            // only visit the sub-expression
        } else {
            super.writeUnaryPlus(EMPTY_UNARY_PLUS);
        }
    }
}
