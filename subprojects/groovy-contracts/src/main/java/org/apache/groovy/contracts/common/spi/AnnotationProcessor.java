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
package org.apache.groovy.contracts.common.spi;

import org.apache.groovy.contracts.domain.Contract;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;

/**
 * <p>Base class for modifying the internal domain model, starting at {@link Contract}, and adding parts to it.</p>
 *
 * @see org.apache.groovy.contracts.annotations.meta.AnnotationProcessorImplementation
 */
public abstract class AnnotationProcessor {

    public void process(final ProcessingContextInformation processingContextInformation, final Contract contract, final ClassNode classNode, final BlockStatement blockStatement, final BooleanExpression booleanExpression) {
    }

    public void process(final ProcessingContextInformation processingContextInformation, final Contract contract, final ClassNode classNode, final MethodNode methodNode, final BlockStatement blockStatement, final BooleanExpression booleanExpression) {
    }

}
