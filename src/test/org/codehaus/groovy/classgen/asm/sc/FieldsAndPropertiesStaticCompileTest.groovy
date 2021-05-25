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
package org.codehaus.groovy.classgen.asm.sc

import groovy.transform.stc.FieldsAndPropertiesSTCTest

final class FieldsAndPropertiesStaticCompileTest extends FieldsAndPropertiesSTCTest implements StaticCompilationTestSupport {

    void testMapGetAt() {
        assertScript '''
            Map map = [a: 1, b:2]
            String key = 'b'
            assert map['a'] == 1
            assert map[key] == 2
        '''
    }

    void testGetAtFromStaticMap() {
        assertScript '''
            class Foo {
                public static Map CLASSES = [:]
            }
            String foo = 'key'
            Foo.CLASSES[foo]
        '''
    }

    // GROOVY-5561
    void testShouldNotThrowAccessForbidden() {
        assertScript '''
            class Test {
                def foo() {
                    def bar = createBar()
                    bar.foo
                }

                Bar createBar() { new Bar() }
            }
            class Bar {
                List<String> foo = ['1','2']
            }
            assert new Test().foo() == ['1','2']
        '''
    }

    // GROOVY-5579
    void testUseSetterAndNotSetProperty() {
        assertScript '''
            Date d = new Date()
            d.time = 1

            assert d.time == 1
        '''
        assert astTrees.values().any {
            it.toString().contains 'INVOKEVIRTUAL java/util/Date.setTime (J)V'
        }
    }

    void testUseDirectWriteFieldAccess() {
        assertScript '''
            class A {
                boolean setterCalled

                protected int x
                public void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            class B extends A {
                void directAccess() {
                    this.@x = 2
                }
            }
            B b = new B()
            b.directAccess()
            assert b.isSetterCalled() == false
            assert b.x == 2
        '''
        assert astTrees['B'][1].contains('PUTFIELD A.x')
    }

    void testUseDirectWriteStaticFieldAccess() {
        assertScript '''
            class A {
                static boolean setterCalled

                static protected int x
                public static void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            class B extends A {
                static void directAccess() {
                    this.@x = 2
                }
            }
            B.directAccess()
            assert B.isSetterCalled() == false
            assert B.x == 2
        '''
        assert astTrees['B'][1].contains('PUTSTATIC A.x')
    }

    void testUseSetterFieldAccess() {
        assertScript '''
            class A {
                boolean setterCalled

                protected int x
                public void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            class B extends A {
                void setterAccess() {
                    this.x = 2
                }
            }
            B b = new B()
            b.setterAccess()
            assert b.isSetterCalled() == true
            assert b.x == 2
        '''
        assert astTrees['B'][1].contains('INVOKEVIRTUAL B.setX')
    }

    void testUseDirectWriteFieldAccessFromOutsideClass() {
        assertScript '''
            class A {
                public int x
            }
            class B  {
                void directAccess(A a) {
                    a.@x = 2
                }
            }
            B b = new B()
            A a = new A()
            b.directAccess(a)
            assert a.x == 2
        '''
        assert astTrees['B'][1].contains('PUTFIELD A.x')
    }

    void testUseDirectWriteFieldAccessPrivateWithRuntimeClassBeingDifferent() {
        assertScript '''
            class A {
                private int x
                public A(int x) {
                    this.@x = x
                }
                public boolean sameAs(A a) {
                    return this.@x == a.@x
                }
            }
            class B extends A {
                // B.x visible in B A.x in A, but reflection depending on the runtime type
                // would see B.x in A#sameAs and not A.x
                private int x
                public B(int x) {
                    super(x)
                    this.@x = x + 50
                }
            }
            B b = new B(1)
            A a = new A(1)
            assert b.sameAs(a)
        '''
        // same with property style access:
        assertScript '''
            class A {
                private int x
                public A(int x) {
                    this.x = x
                }
                public boolean sameAs(A a) {
                    return this.x == a.x
                }
            }
            class B extends A {
                // B.x visible in B A.x in A, but reflection depending on the runtime type
                // would see B.x in A#sameAs and not A.x
                private int x
                public B(int x) {
                    super(x)
                    this.x = x + 50
                }
            }
            B b = new B(1)
            A a = new A(1)
            assert b.sameAs(a)
        '''
    }

    void testReadFieldFromSameClass() {
        ['', 'public', 'private', 'protected', '@groovy.transform.PackageScope'].each { mod ->
            assertScript """
                class A {
                    $mod int x
                    int m() {
                        x
                    }
                }
                assert new A().m() == 0
            """
            def a = astTrees['A'][1]
            assert (a =~ 'GETFIELD A.x').collect().size() == mod.empty ? 2 : 1
        }
    }

    void testWriteFieldFromSameClass() {
        ['', 'public', 'private', 'protected', '@groovy.transform.PackageScope'].each { mod ->
            assertScript """
                class A {
                    $mod int x
                    int m() {
                        x = 5
                        x
                    }
                }
                new A().m() == 5
            """
            def a = astTrees['A'][1]
            assert (a =~ 'PUTFIELD A.x').collect().size() == mod.empty ? 2 : 1
        }
    }

    void testReadFieldFromSuperClass() {
        ['public', 'protected', '@groovy.transform.PackageScope'].each { mod ->
            assertScript """
                class A {
                    $mod int x
                }
                class B extends A {
                    int m() {
                        x
                    }
                }
                assert new B().m() == 0
            """
            def b = astTrees['B'][1]
            assert  b.contains('GETFIELD A.x')
        }
    }

    // GROOVY-9791
    void testReadFieldFromSuperClass2() {
        assertScript '''
            package p
            class A {
                protected int x
            }
            new p.A()
        '''
        assertScript '''
            class B extends p.A {
                int m() {
                    x
                }
            }
            assert new B().m() == 0
        '''
        def b = astTrees['B'][1]
        assert  b.contains('GETFIELD p/A.x')
        assert !b.contains('INVOKEINTERFACE groovy/lang/GroovyObject.getProperty')
    }

    // GROOVY-9791
    void testReadFieldFromSuperClass3() {
        assertScript '''
            package p
            class A {
                protected static int x
            }
            new p.A()
        '''
        assertScript '''
            class B extends p.A {
                static int m() {
                    x
                }
            }
            assert B.m() == 0
        '''
        def b = astTrees['B'][1]
        assert  b.contains('GETSTATIC B.x')
        assert !b.contains('INVOKESTATIC org/codehaus/groovy/runtime/ScriptBytecodeAdapter.getGroovyObjectProperty')
    }

    void testReadPropertyFromSuperClass() {
        ['', 'public', 'private', 'protected', '@groovy.transform.PackageScope'].each { mod ->
            assertScript """
                class A {
                    $mod int x
                    int getX() { x }
                }
                class B extends A {
                    int m() {
                        x
                    }
                }
                assert new B().m() == 0
            """
            def b = astTrees['B'][1]
            assert !b.contains('GETFIELD A.x') : 'no GETFIELD in B'
            assert  b.contains('INVOKEVIRTUAL B.getX') : 'getX() in B'
        }
    }

    void testUseDirectReadFieldAccess() {
        assertScript '''
            class A {
                boolean getterCalled

                protected int x
                public int getX() {
                    getterCalled = true
                    x
                }
            }
            class B extends A {
                void m() {
                    this.@x
                }
            }
            B b = new B()
            b.m()
            assert b.isGetterCalled() == false
        '''
        def b = astTrees['B'][1]
        assert b.contains('GETFIELD A.x')
    }

    void testUseAttributeExternal() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            A a = new A()
            a.@x = 100
            assert a.x == 100
            assert a.isSetterCalled() == false
        '''
    }

    void testUseAttributeExternalSafe() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            A a = new A()
            a?.@x = 100
            assert a.x == 100
            assert a.isSetterCalled() == false
        '''
    }

    void testUseAttributeExternalSafeWithNull() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            A a = null
            a?.@x = 100
        '''
    }

    void testUseSetterExternal() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            A a = new A()
            a.x = 100
            assert a.x == 100
            assert a.isSetterCalled() == true
        '''
    }

    void testUseAttributeExternalSpread() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            List<A> a = [new A(), new A()]
            a*.@x = 100
          println a[0].x == 100
          println a[0].isSetterCalled() == false
        '''
    }

    void testUseAttributeExternalSpreadSafeWithNull() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            List<A> a = [new A(), null]
            a*.@x = 100
            assert a[0].x == 100
            assert a[0].isSetterCalled() == false
            assert a[1] == null
        '''
    }

    void testUseAttributeExternalSpreadUsingSetter() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            List<A> a = [new A(), new A()]
            a*.x = 100
            assert a[0].x == 100
            assert a[0].isSetterCalled() == true
        '''
    }

    void testUseAttributeExternalSpreadSafeWithNullUsingSetter() {
        assertScript '''
            class A {
                boolean setterCalled

                public int x
                void setX(int a) {
                    setterCalled = true
                    x = a
                }
            }
            List<A> a = [new A(), null]
            a*.x = 100
            assert a[0].x == 100
            assert a[0].isSetterCalled() == true
            assert a[1] == null
        '''
    }

    // GROOVY-5649
    void testShouldNotThrowStackOverflowUsingThis() {
        new GroovyShell().evaluate '''
            class HaveOption {
                private String helpOption

                public void setHelpOption(String helpOption) {
                    this.helpOption = helpOption
                }
            }
            def o = new HaveOption()
            o.setHelpOption 'foo'
            assert o.helpOption
        '''
    }

    void testShouldNotThrowStackOverflow() {
        new GroovyShell().evaluate '''
            class HaveOption {
              private String helpOption

              public void setHelpOption(String ho) {
                  helpOption = ho
              }
            }
            def o = new HaveOption()
            o.setHelpOption 'foo'
            assert o.helpOption
        '''
    }

    @Override
    void testPropertyWithMultipleSetters() {
        // we need to override the test because the AST is going to be changed
        assertScript '''
            import org.codehaus.groovy.ast.expr.BinaryExpression
            import org.codehaus.groovy.ast.expr.BooleanExpression
            import org.codehaus.groovy.ast.stmt.AssertStatement
            import org.codehaus.groovy.transform.sc.ListOfExpressionsExpression

            class A {
                private field
                void setX(Integer a) {field=a}
                void setX(String b) {field=b}
                def getX(){field}
            }

            @ASTTest(phase=INSTRUCTION_SELECTION,value={
                lookup('test1').each { stmt ->
                    def exp = stmt.expression
                    assert exp instanceof ListOfExpressionsExpression
                }
                lookup('test2').each { stmt ->
                    def exp = stmt.expression
                    assert exp instanceof ListOfExpressionsExpression
                }
            })
            void testBody() {
                def a = new A()
                test1:
                a.x = 1
                assert a.x==1
                test2:
                a.x = "3"
                assert a.x == "3"
            }
            testBody()
        '''
    }

    void testCallSetterAsPropertyWithinFinallyBlockShouldNotThrowVerifyError() {
        try {
            assertScript '''
                class Multi {
                   void setOut(int a) {}
                }

                void foo() {
                   def m = new Multi()
                   try {
                   } finally {
                      m.out = 1
                   }
                }
                foo()
            '''
        } finally {
            assert astTrees.values().any {
                it.toString().contains 'INVOKEVIRTUAL Multi.setOut (I)V'
            }
        }
    }

    void testCallMultiSetterAsPropertyWithinFinallyBlockShouldNotThrowVerifyError() {
        try {
            assertScript '''
                class Multi {
                   void setOut(int a) {}
                   void setOut(String a) {}
                }

                void foo() {
                   def m = new Multi()
                   try {
                   } finally {
                      m.out = 1
                      m.out = 'foo'
                   }
                }
                foo()
            '''
        } finally {
            assert astTrees.values().any {
                def code = it.toString()
                code.contains('INVOKEVIRTUAL Multi.setOut (I)V') &&
                        code.contains('INVOKEVIRTUAL Multi.setOut (Ljava/lang/String;)V')
            }
        }
    }

    // GROOVY-7698
    void testSafePropertyStyleSetterCalls() {
        assertScript '''
            class Foo {
                private String id

                void setId(String id) {
                    this.id = id
                }
            }
            Foo foo = null
            foo?.id = 'new'
        '''
    }

    // GROOVY-9700
    void testVariableAssignmentUsesDirectSetterCall() {
        assertScript '''
            import org.codehaus.groovy.transform.sc.ListOfExpressionsExpression

            class Foo {
                void setX(Date value) {}
                void setX(Long value) {}
            }
            class Bar extends Foo {
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    def assignment = node.code.statements[0].expression
                    assert assignment instanceof ListOfExpressionsExpression
                        assignment = node.code.statements[1].expression
                    assert assignment instanceof ListOfExpressionsExpression
                })
                void test() {
                    x = 42L
                    x = new Date()
                }
            }
            new Bar().test()
        '''

        def bar = astTrees['Bar'][1]
        assert bar.contains('INVOKEVIRTUAL Bar.setX (Ljava/lang/Long;)V')
        assert bar.contains('INVOKEVIRTUAL Bar.setX (Ljava/util/Date;)V')
        assert !bar.contains('INVOKESTATIC org/codehaus/groovy/runtime/ScriptBytecodeAdapter.setGroovyObjectProperty ')
    }

    void testPrivateFieldMutationInClosureUsesBridgeMethod() {
        try {
            assertScript '''
                class Foo {
                    private String s
                    Closure c = { this.s = 'abc' }

                    void test() {
                        c()
                        assert s == 'abc'
                    }
                }
                new Foo().test()
            '''
        } finally {
            assert astTrees['Foo$_closure1'][1].contains('INVOKESTATIC Foo.pfaccess$00 (LFoo;Ljava/lang/String;)Ljava/lang/String')
        }
    }

    void testImplicitPrivateFieldMutationInClosureUsesBridgeMethod() {
        try {
            assertScript '''
                class Foo {
                    private String s
                    Closure c = { s = 'abc' }

                    String test() {
                        c()
                        assert s == 'abc'
                    }
                }
                new Foo().test()
            '''
        } finally {
            assert astTrees['Foo$_closure1'][1].contains('INVOKESTATIC Foo.pfaccess$00 (LFoo;Ljava/lang/String;)Ljava/lang/String')
        }
    }

    void testPrivateStaticFieldMutationInClosureUsesBridgeMethod() {
        try {
            assertScript '''
                class Foo {
                    private static String s
                    Closure c = { s = 'abc' }

                    String test() {
                        c()
                        assert s == 'abc'
                    }
                }
                new Foo().test()
            '''
        } finally {
            assert astTrees['Foo$_closure1'][1].contains('INVOKESTATIC Foo.pfaccess$00 (LFoo;Ljava/lang/String;)Ljava/lang/String')
        }
    }

    void testPrivateFieldMutationInAICUsesBridgeMethod() {
        try {
            assertScript '''
                class A {
                    private int x
                    void test() {
                        def aic = new Runnable() { void run() { A.this.x = 666 } }
                        aic.run()
                        assert x == 666
                    }
                }
                new A().test()
            '''
        } finally {
            assert astTrees['A$1'][1].contains('INVOKESTATIC A.pfaccess$00 (LA;I)I')
        }
    }

    void testImplicitPrivateFieldMutationInAICUsesBridgeMethod() {
        try {
            assertScript '''
                class A {
                    private int x
                    void test() {
                        def aic = new Runnable() { void run() { x = 666 } }
                        aic.run()
                        assert x == 666
                    }
                }
                new A().test()
            '''
        } finally {
            assert astTrees['A$1'][1].contains('INVOKESTATIC A.pfaccess$00 (LA;I)I')
        }
    }

    void testPrivateStaticFieldMutationInAICUsesBridgeMethod() {
        try {
            assertScript '''
                class A {
                    private static int x
                    void test() {
                        def aic = new Runnable() { void run() { x = 666 } }
                        aic.run()
                        assert x == 666
                    }
                }
                new A().test()
            '''
        } finally {
            assert astTrees['A$1'][1].contains('INVOKESTATIC A.pfaccess$00 (LA;I)I')
        }
    }

    void testMultiplePrivateFieldMutatorBridgeMethods() {
        try {
            assertScript '''
                class A {
                    private int x
                    private String y
                    Closure mutate = { x = 1; y = 'abc' }

                    void test() {
                        mutate()
                        assert x == 1
                        assert y == 'abc'
                    }
                }
                new A().test()
            '''
        } finally {
            assert astTrees['A$_closure1'][1].contains('INVOKESTATIC A.pfaccess$00 (LA;I)I')
            assert astTrees['A$_closure1'][1].contains('INVOKESTATIC A.pfaccess$01 (LA;Ljava/lang/String;)Ljava/lang/String;')
        }
    }

    void testPrivateFieldBridgeMethodsAreGeneratedAsNecessary() {
        try {
            assertScript '''
                class A {
                    private int accessed = 0
                    private String mutated
                    private String accessedAndMutated = ''
                    Closure c = {
                        println accessed
                        mutated = 'abc'
                        println accessedAndMutated
                        accessedAndMutated = 'def'
                    }

                    void test() {
                        c()
                        assert mutated == 'abc'
                        assert accessedAndMutated == 'def'
                    }
                }
                new A().test()
            '''
        } finally {
            def dump = astTrees['A'][1]
            assert dump.contains('pfaccess$0') // accessor bridge method for 'accessed'
            assert !dump.contains('pfaccess$00') // no mutator bridge method for 'accessed'
            assert dump.contains('pfaccess$01') // mutator bridge method for 'mutated'
            assert dump.contains('pfaccess$1') // accessor bridge method for 'mutated' -- GROOVY-9385
            assert dump.contains('pfaccess$2') // accessor bridge method for 'accessedAndMutated'
            assert dump.contains('pfaccess$02') // mutator bridge method for 'accessedAndMutated'
            dump = astTrees['A$_closure1'][1]
            assert dump.contains('INVOKESTATIC A.pfaccess$2 (LA;)Ljava/lang/String;')
            assert dump.contains('INVOKESTATIC A.pfaccess$02 (LA;Ljava/lang/String;)Ljava/lang/String;')
        }
    }

    // GROOVY-8369
    void testPropertyAccessOnEnumClass() {
        assertScript '''
            enum Foo {}

            def test() {
                assert Foo.getModifiers() == Foo.modifiers
            }
            test()
        '''
    }

    // GROOVY-8753
    void testPrivateFieldWithPublicGetter() {
        assertScript '''
            class A {
               private List<String> fooNames = []
               public A(Collection<String> names) {
                  names.each { fooNames << it }
               }
               public List<String> getFooNames() { fooNames }
            }
            assert new A(['foo1', 'foo2']).fooNames.size() == 2
        '''
    }

    // GROOVY-9683
    void testPrivateFieldAccessInClosure3() {
        assertScript '''
            class A {
                private static X = 'xxx'
                void test() {
                    [:].with {
                        assert X == 'xxx'
                    }
                }
            }
            new A().test()
        '''
    }

    // GROOVY-9695
    void testPrivateFieldAccessInClosure4() {
        assertScript '''
            class A {
                private static final X = 'xxx'
                void test() {
                    Map m = [:]
                    def c = { ->
                        assert X == 'xxx'
                        m[X] = 123
                    }
                    c()
                    assert m == [xxx:123]
                }
            }
            new A().test()

            class B extends A {
            }
            new B().test()
        '''
    }
}
