package org.apache.thrift.maven;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.*;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Sets.newHashSet;

/**
 * This class represents an invokable configuration of the {@code thrift}
 * compiler. The actual executable is invoked using the plexus
 * {@link Commandline}.
 * <p/>
 * This class currently only supports generating java source files.
 *
 * @author gak@google.com (Gregory Kick)
 */
final class Thrift {
    final static String VERSION = "0.5.0";

    final static String GENERATED_JAVA = "gen-java";

    private final String executable;
    private final String generator;
    private final ImmutableSet<File> thriftPathElements;
    private final ImmutableSet<File> thriftFiles;
    private final File javaOutputDirectory;
    private final CommandLineUtils.StringStreamConsumer output;
    private final CommandLineUtils.StringStreamConsumer error;

    /**
     * Constructs a new instance. This should only be used by the {@link Builder}.
     *
     * @param executable          The path to the {@code thrift} executable.
     * @param generator           The value for the {@code --gen} option.
     * @param thriftPath          The directories in which to search for imports.
     * @param thriftFiles         The thrift source files to compile.
     * @param javaOutputDirectory The directory into which the java source files
     *                            will be generated.
     */
    private Thrift(String executable, String generator, ImmutableSet<File> thriftPath,
                   ImmutableSet<File> thriftFiles, File javaOutputDirectory) {
        this.generator = checkNotNull(generator, "generator");
        this.thriftPathElements = checkNotNull(thriftPath, "thriftPath");
        this.thriftFiles = checkNotNull(thriftFiles, "thriftFiles");
        this.javaOutputDirectory = checkNotNull(javaOutputDirectory, "javaOutputDirectory");
        this.error = new CommandLineUtils.StringStreamConsumer();
        this.output = new CommandLineUtils.StringStreamConsumer();

        this.executable = getExecutable(executable);
    }

    /**
     * Invokes the {@code thrift} compiler using the configuration specified at
     * construction.
     *
     * @return The exit status of {@code thrift}.
     * @throws CommandLineException
     */
    public int compile() throws CommandLineException {
        for (File thriftFile : thriftFiles) {
            Commandline cl = new Commandline();
            cl.setExecutable(executable);
            cl.addArguments(buildThriftCommand(thriftFile).toArray(new String[]{}));
            final int result = CommandLineUtils.executeCommandLine(cl, null, output, error);
            if (result != 0) {
                return result;
            }
            File genDir = new File(javaOutputDirectory, GENERATED_JAVA);
            moveGenerated(genDir, javaOutputDirectory);
        }

        // result will always be 0 here.
        return 0;
    }

    private void moveGenerated(File from, File to) {
        if (from.isDirectory()) {
            if (!to.isDirectory()) {
                Preconditions.checkState(to.mkdirs());
            }
            for (File file : Preconditions.checkNotNull(from.listFiles())) {
                moveGenerated(file, new File(to, file.getName()));
            }
            Preconditions.checkState(from.delete());
        } else {
            try {
                Files.move(from, to);
            } catch (IOException e) {
                throw new RuntimeException(String.format("Unable to move %s to %s", from, to), e);
            }
        }
    }

    /**
     * Creates the command line arguments.
     * <p/>
     * This method has been made visible for testing only.
     *
     * @param thriftFile
     * @return A list consisting of the executable followed by any arguments.
     */
    ImmutableList<String> buildThriftCommand(final File thriftFile) {
        final List<String> command = newLinkedList();
        // add the executable
        for (File thriftPathElement : thriftPathElements) {
            command.add("-I");
            command.add(thriftPathElement.toString());
        }
        command.add("-o");
        command.add(javaOutputDirectory.toString());
        command.add("--gen");
        command.add(generator);
        command.add(thriftFile.toString());
        return ImmutableList.copyOf(command);
    }

    /**
     * @return the output
     */
    public String getOutput() {
        return output.getOutput();
    }

    /**
     * @return the error
     */
    public String getError() {
        return error.getOutput();
    }

    /**
     * This class builds {@link Thrift} instances.
     *
     * @author gak@google.com (Gregory Kick)
     */
    static final class Builder {
        private final String executable;
        private final File javaOutputDirectory;
        private Set<File> thriftPathElements;
        private Set<File> thriftFiles;
        private String generator;

        /**
         * Constructs a new builder. The two parameters are present as they are
         * required for all {@link Thrift} instances.
         *
         * @param osName          The os.name to search for in the executable path
         * @param osArch          The os.arch to search for in the executable path
         * @param javaOutputDirectory The directory into which the java source files
         *                            will be generated.
         * @throws NullPointerException     If either of the arguments are {@code null}.
         * @throws IllegalArgumentException If the {@code javaOutputDirectory} is
         *                                  not a directory.
         */
        public Builder(String osName, String osArch, File javaOutputDirectory) {
            this.javaOutputDirectory = checkNotNull(javaOutputDirectory);
            checkArgument(javaOutputDirectory.isDirectory());
            this.thriftFiles = newHashSet();
            this.thriftPathElements = newHashSet();
            checkNotNull(osName, "osName");
            checkNotNull(osArch, "osArch");
            this.executable = getExecFilename(osName, osArch, VERSION);
        }

        /**
         * Adds a thrift file to be compiled. Thrift files must be on the thriftpath
         * and this method will fail if a thrift file is added without first adding a
         * parent directory to the thriftpath.
         *
         * @param thriftFile
         * @return The builder.
         * @throws IllegalStateException If a thrift file is added without first
         *                               adding a parent directory to the thriftpath.
         * @throws NullPointerException  If {@code thriftFile} is {@code null}.
         */
        public Builder addThriftFile(File thriftFile) {
            checkNotNull(thriftFile);
            checkArgument(thriftFile.isFile());
            checkArgument(thriftFile.getName().endsWith(".thrift"));
            checkThriftFileIsInThriftPath(thriftFile);
            thriftFiles.add(thriftFile);
            return this;
        }

        /**
         * Adds the option string for the Thrift executable's {@code --gen} parameter.
         *
         * @param generator
         * @return The builder
         * @throws NullPointerException If {@code generator} is {@code null}.
         */
        public Builder setGenerator(String generator) {
            checkNotNull(generator);
            this.generator = generator;
            return this;
        }

        private void checkThriftFileIsInThriftPath(File thriftFile) {
            assert thriftFile.isFile();
            checkState(checkThriftFileIsInThriftPathHelper(thriftFile.getParentFile()));
        }

        private boolean checkThriftFileIsInThriftPathHelper(File directory) {
            assert directory.isDirectory();
            if (thriftPathElements.contains(directory)) {
                return true;
            } else {
                final File parentDirectory = directory.getParentFile();
                return (parentDirectory == null) ? false
                        : checkThriftFileIsInThriftPathHelper(parentDirectory);
            }
        }

        /**
         * @see #addThriftFile(File)
         */
        public Builder addThriftFiles(Iterable<File> thriftFiles) {
            for (File thriftFile : thriftFiles) {
                addThriftFile(thriftFile);
            }
            return this;
        }

        /**
         * Adds the {@code thriftPathElement} to the thriftPath.
         *
         * @param thriftPathElement A directory to be searched for imported thrift message
         *                          buffer definitions.
         * @return The builder.
         * @throws NullPointerException     If {@code thriftPathElement} is {@code null}.
         * @throws IllegalArgumentException If {@code thriftPathElement} is not a
         *                                  directory.
         */
        public Builder addThriftPathElement(File thriftPathElement) {
            checkNotNull(thriftPathElement);
            checkArgument(thriftPathElement.isDirectory());
            thriftPathElements.add(thriftPathElement);
            return this;
        }

        /**
         * @see #addThriftPathElement(File)
         */
        public Builder addThriftPathElements(Iterable<File> thriftPathElements) {
            for (File thriftPathElement : thriftPathElements) {
                addThriftPathElement(thriftPathElement);
            }
            return this;
        }

        /**
         * @return A configured {@link Thrift} instance.
         * @throws IllegalStateException If no thrift files have been added.
         */
        public Thrift build() {
            checkState(!thriftFiles.isEmpty());
            return new Thrift(executable, generator, ImmutableSet.copyOf(thriftPathElements),
                    ImmutableSet.copyOf(thriftFiles), javaOutputDirectory);
        }
    }

    public static String getExecutable(String executableName) {
        InputStream stream = Thrift.class.getResourceAsStream("/thrift/" + executableName);
        Preconditions.checkNotNull(stream, "No binary embedded for: %s", executableName);

        try {
            try {
                File file = File.createTempFile(executableName, "");
                ByteStreams.copy(stream, Files.newOutputStreamSupplier(file));
                if (!file.setExecutable(true)) {
                    throw new IllegalStateException(String.format("Unable to make %s executable", file.getAbsolutePath()));
                }
                file.deleteOnExit();
                return file.getAbsolutePath();
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Map<String, String> osNameMap = ImmutableMap.<String, String>builder()
            .put("Mac OS X", "osx")
            .put("Linux", "linux")
            .put("FreeBSD", "bsd")
            .put("OpenBSD", "bsd")
            .build();

    private static final Map<String, String> osArchMap = ImmutableMap.<String, String>builder()
            .put("amd64", "64")
            .put("x86_64", "64")
            .put("x86", "32")
            .put("i386", "32")
            .put("i486", "32")
            .put("i586", "32")
            .put("i686", "32")
            .build();

    public static String getExecFilename(String osName, String osArch, String version) {
        if (osName.startsWith("Windows")) {
            return String.format("thrift-%s.exe", version);
        }

        osName = osNameMap.get(osName);
        Preconditions.checkNotNull(osName, "Unknown os.name", osName);

        osArch = osArchMap.get(osArch);
        Preconditions.checkNotNull(osArch, "Unknown os.arch", osArch);

        return String.format("thrift-%s-%s%s", version, osName, osArch);
    }
}
