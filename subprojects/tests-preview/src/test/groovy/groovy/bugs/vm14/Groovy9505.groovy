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
package groovy.bugs.vm14

import groovy.test.GroovyTestCase

class Groovy9505 extends GroovyTestCase {
    void testUsingRecord() {
        assertScript """
        import org.apache.groovy.util.JavaShell
        def opts = ['--enable-preview', '--release', '${System.getProperty('java.specification.version')}']
        def src = 'record Coord(int x, int y) {}'
        Class coordClass = new JavaShell().compile('Coord', opts, src)
        assert coordClass.newInstance(5, 10).toString() == 'Coord[x=5, y=10]'
        """
    }
}
