package org.maghraby.runner;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds and executes "compile" or "compile + run" plans for Java, C, C++, C#, Python and SQL (via the sqlite3 shell).
 *
 * Planning is pure (no terminal access). Execution inherits the real terminal, so the
 * program can read stdin and print normally. Build artifacts are stored in a temporary
 * directory to keep the user's workspace clean.
 */
public final class CodeRunner {

    private CodeRunner() {}

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public enum Language {
        JAVA("Java"), C("C"), CPP("C++"), CSHARP("C#"), PYTHON("Python"), SQL("SQL");

        public final String label;
        Language(String label) { this.label = label; }
    }

    /** A user-facing problem (e.g., missing compiler). The message is shown in the status bar. */
    public static final class RunnerException extends Exception {
        private static final long serialVersionUID = 1L;
        public RunnerException(String message) { super(message); }
    }

    public static final class Step {
        public final String title;          // E.g., "Compiling", "Running"
        public final List<String> command;
        public final boolean isRun;         // true = user program, false = compiler/checker

        Step(String title, List<String> command, boolean isRun) {
            this.title = title;
            this.command = command;
            this.isRun = isRun;
        }
    }

    public static final class Plan {
        public final Language language;
        public final Path workDir;
        public final Path buildDir;         // null when nothing needs building (e.g., Python)
        public final List<Step> steps;
        public final boolean run;

        Plan(Language language, Path workDir, Path buildDir, List<Step> steps, boolean run) {
            this.language = language;
            this.workDir = workDir;
            this.buildDir = buildDir;
            this.steps = steps;
            this.run = run;
        }
    }

    // ------------------------------------------------------------------
    // Language Detection
    // ------------------------------------------------------------------

    public static Language detect(String filename) {
        if (filename == null) return null;
        String base = Paths.get(filename.trim()).getFileName() == null
                ? "" : Paths.get(filename.trim()).getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot < 0) return null;
        switch (base.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "java": return Language.JAVA;
            case "c": return Language.C;
            case "cpp": case "cc": case "cxx": return Language.CPP;
            case "cs": return Language.CSHARP;
            case "py": return Language.PYTHON;
            case "sql": return Language.SQL;
            default: return null;
        }
    }

    public static String supportedExtensions() {
        return ".java .c .cpp .cc .cxx .cs .py .sql";
    }

    // ------------------------------------------------------------------
    // Planning Phase
    // ------------------------------------------------------------------

    /**
     * Creates an execution plan based on the file type.
     * @param run  true = compile and run, false = compile / check only
     * @param args program arguments (only used when run == true)
     */
    public static Plan plan(String filename, boolean run, List<String> args) throws RunnerException {
        Language lang = detect(filename);
        if (lang == null) {
            throw new RunnerException("Cannot compile/run '" + filename + "'. Supported: " + supportedExtensions());
        }

        Path file;
        try {
            file = Paths.get(filename.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new RunnerException("Invalid file name: " + filename);
        }

        if (!Files.isRegularFile(file)) {
            throw new RunnerException("File not found: " + file);
        }

        Path dir = file.getParent();
        String fileName = file.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        String exeName = IS_WINDOWS ? "prog.exe" : "prog";

        Path build = null;
        try {
            List<Step> steps = new ArrayList<>();

            switch (lang) {
                case JAVA: {
                    String javac = findTool("javac");
                    String java = run ? findTool("java") : null;
                    if (javac == null) {
                        throw new RunnerException("'javac' not found. Ensure JDK is installed and on PATH.");
                    }
                    if (run && java == null) throw new RunnerException("'java' not found on PATH.");

                    String pkg = readJavaPackage(file);
                    String mainClass = pkg == null ? baseName : pkg + "." + baseName;
                    Path sourceRoot = javaSourceRoot(dir, pkg);
                    build = Files.createTempDirectory("maghraby-build-");

                    steps.add(new Step("Compiling", cmd(javac, "-encoding", "UTF-8",
                            "-sourcepath", sourceRoot.toString(), "-d", build.toString(), file.toString()), false));
                    if (run) {
                        List<String> runCmd = cmd(java, "-cp", build.toString(), mainClass);
                        runCmd.addAll(args);
                        steps.add(new Step("Running", runCmd, true));
                    }
                    break;
                }
                case C:
                case CPP: {
                    String compiler = lang == Language.C
                            ? findTool("gcc", "clang", "cc")
                            : findTool("g++", "clang++", "c++");
                    if (compiler == null) {
                        throw new RunnerException(lang == Language.C
                                ? "No C compiler found (tried gcc, clang, cc). Install one and put it on PATH."
                                : "No C++ compiler found (tried g++, clang++, c++). Install one and put it on PATH.");
                    }
                    build = Files.createTempDirectory("maghraby-build-");
                    Path exe = build.resolve(exeName);

                    List<String> compile = cmd(compiler, "-Wall", "-o", exe.toString(), file.toString());
                    if (lang == Language.C) compile.add("-lm"); // Support math functions

                    steps.add(new Step("Compiling", compile, false));
                    if (run) {
                        List<String> runCmd = cmd(exe.toString());
                        runCmd.addAll(args);
                        steps.add(new Step("Running", runCmd, true));
                    }
                    break;
                }
                case CSHARP: {
                    String dotnet = findTool("dotnet");
                    if (dotnet != null) {
                        // Wrap the single file in a throw-away project, so no .csproj is needed next to it
                        build = Files.createTempDirectory("maghraby-build-");
                        Path proj = build.resolve("prog.csproj");
                        Path out = build.resolve("out");
                        Files.write(proj, csharpProject(file, dotnetTargetFramework(dotnet)).getBytes(StandardCharsets.UTF_8));

                        steps.add(new Step("Compiling", cmd(dotnet, "build", proj.toString(), "-o", out.toString(),
                                "-nologo", "-v", "q", "-clp:NoSummary"), false));
                        if (run) {
                            List<String> runCmd = cmd(dotnet, out.resolve("prog.dll").toString());
                            runCmd.addAll(args);
                            steps.add(new Step("Running", runCmd, true));
                        }
                        break;
                    }
                    String mcs = findTool("mcs");
                    String mono = run ? findTool("mono") : null;
                    if (mcs == null) {
                        throw new RunnerException("'dotnet' not found. Install the .NET SDK (or Mono's 'mcs') and put it on PATH.");
                    }
                    if (run && mono == null) throw new RunnerException("'mono' not found on PATH.");
                    build = Files.createTempDirectory("maghraby-build-");
                    Path exe = build.resolve("prog.exe");
                    steps.add(new Step("Compiling", cmd(mcs, "-out:" + exe, file.toString()), false));
                    if (run) {
                        List<String> runCmd = cmd(mono, exe.toString());
                        runCmd.addAll(args);
                        steps.add(new Step("Running", runCmd, true));
                    }
                    break;
                }
                case SQL: {
                    String sqlite = findTool("sqlite3");
                    if (sqlite == null) {
                        throw new RunnerException("'sqlite3' not found. Install the SQLite command-line shell and put it on PATH.");
                    }
                    if (args.size() > 1) {
                        throw new RunnerException("SQL takes at most one argument: the SQLite database file (default: in memory).");
                    }
                    // ":run db-file" executes against that database; ":compile", or ":run" with no argument, uses a
                    // throw-away in-memory one, so nothing is ever written unless the user names a file.
                    String db = run && !args.isEmpty() ? args.get(0) : ":memory:";
                    if (!db.equals(":memory:")) db = Paths.get(db).toAbsolutePath().normalize().toString();
                    String read = ".read \"" + file.toString().replace("\\", "/") + "\"";
                    steps.add(new Step(run ? "Running" : "Checking SQL (in memory)",
                            cmd(sqlite, "-bail", "-header", "-column", db, read), run));
                    break;
                }
                case PYTHON: {
                    String python = findTool("python3", "python", "py");
                    if (python == null) {
                        throw new RunnerException("Python not found. Install it and put it on PATH.");
                    }
                    if (run) {
                        List<String> runCmd = cmd(python, file.toString());
                        runCmd.addAll(args);
                        steps.add(new Step("Running", runCmd, true));
                    } else {
                        steps.add(new Step("Checking syntax", cmd(python, "-c", PY_SYNTAX_CHECK, file.toString()), false));
                    }
                    break;
                }
                default:
                    throw new RunnerException("Unsupported language.");
            }
            return new Plan(lang, dir, build, steps, run);

        } catch (IOException e) {
            deleteQuietly(build);
            throw new RunnerException("Could not create build directory: " + e.getMessage());
        } catch (RunnerException e) {
            deleteQuietly(build);
            throw e;
        }
    }

    /** Picks the newest target framework the installed SDK supports, e.g. "net8.0". */
    private static String dotnetTargetFramework(String dotnet) {
        try {
            Process p = new ProcessBuilder(dotnet, "--version")
                    .redirectErrorStream(true).start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            Matcher m = Pattern.compile("^(\\d+)\\.").matcher(out);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                if (major >= 5) return "net" + major + ".0";
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the default
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "net8.0";
    }

    /** A minimal console-app project that compiles just {@code source}. */
    static String csharpProject(Path source, String targetFramework) {
        return "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
                + "  <PropertyGroup>\n"
                + "    <OutputType>Exe</OutputType>\n"
                + "    <TargetFramework>" + targetFramework + "</TargetFramework>\n"
                + "    <AssemblyName>prog</AssemblyName>\n"
                + "    <ImplicitUsings>enable</ImplicitUsings>\n"
                + "    <Nullable>disable</Nullable>\n"
                + "    <UseAppHost>false</UseAppHost>\n"
                + "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
                + "  </PropertyGroup>\n"
                + "  <ItemGroup>\n"
                + "    <Compile Include=\"" + xmlEscape(source.toString()) + "\" />\n"
                + "  </ItemGroup>\n"
                + "</Project>\n";
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Syntax check without writing __pycache__ and with a compact one-line error. */
    private static final String PY_SYNTAX_CHECK =
            "import sys\n"
                    + "try:\n"
                    + "    compile(open(sys.argv[1], encoding='utf-8').read(), sys.argv[1], 'exec')\n"
                    + "except SyntaxError as e:\n"
                    + "    sys.stderr.write('%s:%s:%s: SyntaxError: %s\\n' % (e.filename, e.lineno, e.offset, e.msg))\n"
                    + "    sys.exit(1)\n";

    // ------------------------------------------------------------------
    // Execution Phase
    // ------------------------------------------------------------------

    /**
     * Runs the plan on the real terminal. Returns a one-line summary for the status bar.
     * Never throws for ordinary failures (compile errors, non-zero exit...).
     */
    @SuppressWarnings("try") // Guard is only used for its close(): it restores the previous SIGINT handler
    public static String execute(Plan plan, PrintStream out) {
        String summary = "Done.";
        try (SigintGuard ignored = SigintGuard.install()) {
            for (Step step : plan.steps) {
                out.println("\u001b[1;36m$ " + shellJoin(step.command) + "\u001b[0m");
                out.flush();

                int code;
                try {
                    Process p = new ProcessBuilder(step.command)
                            .directory(plan.workDir.toFile())
                            .inheritIO()
                            .start();
                    code = waitFor(p);
                } catch (IOException e) {
                    out.println("Failed to start: " + e.getMessage());
                    return step.title + " failed to start";
                }

                if (step.isRun) {
                    out.println();
                    String what = plan.language == Language.SQL ? "Script" : "Program";
                    out.println((code == 0 ? "\u001b[32m" : "\u001b[31m")
                            + "[" + what + " exited with code " + code + "]\u001b[0m");
                    summary = plan.language.label + " " + what.toLowerCase(Locale.ROOT) + " exited with code " + code + ".";
                } else if (code != 0) {
                    out.println();
                    out.println("\u001b[31m[" + step.title + " failed, exit code " + code + "]\u001b[0m");
                    return plan.language.label + ": " + step.title.toLowerCase(Locale.ROOT) + " failed (exit " + code + ").";
                } else {
                    summary = plan.language.label + ": " + (plan.language == Language.PYTHON ? "syntax OK."
                            : plan.language == Language.SQL ? "script OK." : "compiled OK.");
                    if (!plan.run) out.println("\u001b[32m[" + summary + "]\u001b[0m");
                }
                out.flush();
            }
        }
        return summary;
    }

    private static int waitFor(Process p) {
        while (true) {
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                // Keep waiting: the child process owns the terminal right now
            }
        }
    }

    public static void cleanup(Plan plan) {
        if (plan != null) deleteQuietly(plan.buildDir);
    }

    // ------------------------------------------------------------------
    // Helpers & Utilities
    // ------------------------------------------------------------------

    private static List<String> cmd(String... parts) {
        List<String> list = new ArrayList<>();
        Collections.addAll(list, parts);
        return list;
    }

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static String readJavaPackage(Path file) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher m = PACKAGE_DECL.matcher(text);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Resolves the source root directory based on the Java package declaration. */
    private static Path javaSourceRoot(Path dir, String pkg) {
        if (pkg == null) return dir;
        String[] segments = pkg.split("\\.");
        Path root = dir;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (root != null && root.getFileName() != null && root.getFileName().toString().equals(segments[i])) {
                root = root.getParent();
            } else {
                return dir;
            }
        }
        return root == null ? dir : root;
    }

    /** Finds the first available tool on the system PATH. */
    public static String findTool(String... names) {
        List<File> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) if (!d.isEmpty()) dirs.add(new File(d));
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) dirs.add(new File(javaHome, "bin"));
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null) dirs.add(new File(envJavaHome, "bin"));

        String[] suffixes = IS_WINDOWS ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""};
        for (String name : names) {
            for (File dir : dirs) {
                for (String suffix : suffixes) {
                    File f = new File(dir, name + suffix);
                    if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String shellJoin(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String part : command) {
            if (sb.length() > 0) sb.append(' ');
            if (part.indexOf('\n') >= 0) part = "<inline script>";
            boolean quote = part.isEmpty() || part.matches(".*[\\s\"'$&|;<>()*?].*");
            sb.append(quote ? "'" + part.replace("'", "'\\''") + "'" : part);
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * Prevents Ctrl+C (SIGINT) from killing the editor when the user program is running.
     * Replaces the handler temporarily and restores it upon closing.
     */
    private static final class SigintGuard implements AutoCloseable {
        private Object signal;
        private Object previous;
        private Method handle;

        static SigintGuard install() {
            SigintGuard guard = new SigintGuard();
            try {
                Class<?> signalClass = Class.forName("sun.misc.Signal");
                Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
                Object noop = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[]{handlerClass},
                        (proxy, method, a) -> {
                            switch (method.getName()) {
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == a[0];
                                case "toString": return "NoopSigintHandler";
                                default: return null;
                            }
                        });
                guard.handle = signalClass.getMethod("handle", signalClass, handlerClass);
                guard.signal = signalClass.getConstructor(String.class).newInstance("INT");
                guard.previous = guard.handle.invoke(null, guard.signal, noop);
            } catch (Throwable t) {
                guard.signal = null;
            }
            return guard;
        }

        @Override
        public void close() {
            if (signal == null) return;
            try {
                handle.invoke(null, signal, previous);
            } catch (Throwable ignored) { }
        }
    }
}