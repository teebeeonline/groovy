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
package org.apache.groovy.gradle

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

@CompileStatic
@CacheableTask
class DgmConverter extends DefaultTask {

    private final ExecOperations execOperations


    @OutputDirectory
    final DirectoryProperty outputDirectory

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    final ConfigurableFileTree sources

    @InputFiles
    @Classpath
    final ConfigurableFileCollection classpath

    @Inject
    DgmConverter(ExecOperations execOperations) {
        description = 'Generates DGM info file required for faster startup.'
        this.execOperations = execOperations
        classpath = project.objects.fileCollection()
        sources = project.objects.fileTree()
        outputDirectory = project.objects.directoryProperty().convention(
                project.layout.buildDirectory.dir("dgm")
        )
    }

    @TaskAction
    @CompileDynamic
    void generateDgmInfo() {
        outputDirectory.dir("META-INF").get().asFile.mkdirs()
        execOperations.javaexec {
            it.mainClass.set('org.codehaus.groovy.tools.DgmConverter')
            it.classpath = this.classpath
            it.args('--info', outputDirectory.asFile.get().absolutePath)
        }
    }
}
