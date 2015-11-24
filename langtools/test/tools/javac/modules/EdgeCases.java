/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary tests for multi-module mode compilation
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.code
 *      jdk.compiler/com.sun.tools.javac.main
 * @build ToolBox ModuleTestBase
 * @run main EdgeCases
 */

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;

public class EdgeCases extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        new EdgeCases().runTests();
    }

    @Test
    void testAddExportUndefinedModule(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "package test; import undef.Any; public class Test {}");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = tb.new JavacTask()
                .options("-XaddExports:undef/undef=ALL-UNNAMED", "-XDrawDiagnostics")
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutputLines(ToolBox.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("- compiler.err.cant.find.module: undef",
                                              "Test.java:1:27: compiler.err.doesnt.exist: undef",
                                              "2 errors");

        if (!expected.equals(log))
            throw new Exception("expected output not found: " + log);
    }

    @Test
    void testModuleSymbolOutterMostClass(Path base) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Path moduleSrc = base.resolve("module-src");
            Path m1 = moduleSrc.resolve("m1");

            tb.writeJavaFiles(m1, "module m1 { }");

            Iterable<? extends JavaFileObject> files = fm.getJavaFileObjects(findJavaFiles(moduleSrc));
            JavacTask task = (JavacTask) compiler.getTask(null, fm, null, null, null, files);

            task.analyze();

            ModuleSymbol msym = (ModuleSymbol) task.getElements().getModuleElement("m1");

            msym.outermostClass();
        }
    }

    @Test
    void testParseEnterAnalyze(Path base) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Path moduleSrc = base.resolve("module-src");
            Path m1 = moduleSrc.resolve("m1");

            tb.writeJavaFiles(m1, "module m1 { }",
                                  "package p;",
                                  "package p; class T { }");

            Path classes = base.resolve("classes");
            Iterable<? extends JavaFileObject> files = fm.getJavaFileObjects(findJavaFiles(moduleSrc));
            List<String> options = Arrays.asList("-d", classes.toString(), "-Xpkginfo:always");
            JavacTaskImpl task = (JavacTaskImpl) compiler.getTask(null, fm, null, options, null, files);

            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            Iterable<? extends Element> entered = task.enter(parsed);
            Iterable<? extends Element> analyzed = task.analyze(entered);
            Iterable<? extends JavaFileObject> generatedFiles = task.generate(analyzed);

            Set<String> generated = new HashSet<>();

            for (JavaFileObject jfo : generatedFiles) {
                generated.add(jfo.getName());
            }

            Set<String> expected = new HashSet<>(
                    Arrays.asList("testParseEnterAnalyze/classes/p/package-info.class",
                                  "testParseEnterAnalyze/classes/module-info.class",
                                  "testParseEnterAnalyze/classes/p/T.class")
            );

            if (!Objects.equals(expected, generated))
                throw new AssertionError("Incorrect generated files: " + generated);
        }
    }

    @Test
    void testModuleImplicitModuleBoundaries(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1");
        tb.writeJavaFiles(src_m1,
                          "module m1 { exports api1; }",
                          "package api1; public class Api1 { public void call() { } }");
        Path src_m2 = src.resolve("m2");
        tb.writeJavaFiles(src_m2,
                          "module m2 { requires m1; exports api2; }",
                          "package api2; public class Api2 { public static api1.Api1 get() { return null; } }");
        Path src_m3 = src.resolve("m3");
        tb.writeJavaFiles(src_m3,
                          "module m3 { requires m2; }",
                          "package test; public class Test { { api2.Api2.get().call(); api2.Api2.get().toString(); } }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        String log = tb.new JavacTask()
                .options("-XDrawDiagnostics",
                         "-modulesourcepath", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(ToolBox.Expect.FAIL)
                .writeAll()
                .getOutput(ToolBox.OutputKind.DIRECT);

        if (!log.contains("Test.java:1:52: compiler.err.not.def.access.class.intf.cant.access: call(), api1.Api1") ||
            !log.contains("Test.java:1:76: compiler.err.not.def.access.class.intf.cant.access: toString(), java.lang.Object"))
            throw new Exception("expected output not found");
    }

}
