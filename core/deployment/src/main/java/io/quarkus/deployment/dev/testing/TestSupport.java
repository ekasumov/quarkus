package io.quarkus.deployment.dev.testing;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.deployment.dev.CompilationProvider;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.QuarkusCompiler;
import io.quarkus.deployment.dev.RuntimeUpdatesProcessor;

public class TestSupport implements TestController {

    private static final Logger log = Logger.getLogger(TestSupport.class);

    final CuratedApplication curatedApplication;
    final List<CompilationProvider> compilationProviders;
    final DevModeContext context;
    final List<TestListener> testListeners = new CopyOnWriteArrayList<>();
    final TestState testState = new TestState();

    volatile CuratedApplication testCuratedApplication;
    volatile QuarkusCompiler compiler;
    volatile TestRunner testRunner;
    volatile boolean started;
    volatile TestRunResults testRunResults;
    volatile List<String> includeTags = Collections.emptyList();
    volatile List<String> excludeTags = Collections.emptyList();
    volatile Pattern include = null;
    volatile Pattern exclude = null;
    volatile boolean displayTestOutput;
    volatile Boolean explicitDisplayTestOutput;
    volatile boolean failingTestsOnly;

    public TestSupport(CuratedApplication curatedApplication, List<CompilationProvider> compilationProviders,
            DevModeContext context) {
        this.curatedApplication = curatedApplication;
        this.compilationProviders = compilationProviders;
        this.context = context;
    }

    public static Optional<TestSupport> instance() {
        if (RuntimeUpdatesProcessor.INSTANCE == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(RuntimeUpdatesProcessor.INSTANCE.getTestSupport());
    }

    public boolean isRunning() {
        if (testRunner == null) {
            return false;
        }
        return testRunner.isRunning();
    }

    public List<TestListener> getTestListeners() {
        return testListeners;
    }

    /**
     * returns the current status of the test runner.
     * <p>
     * This is expressed in terms of test run ids, where -1 signifies
     * no result.
     */
    public RunStatus getStatus() {
        if (testRunner == null) {
            return new RunStatus(-1, -1);
        }
        long last = -1;
        //get the running test id before the current status
        //otherwise there is a race where they both could be -1 even though it has started
        long runningTestRunId = testRunner.getRunningTestRunId();
        TestRunResults tr = testRunResults;
        if (tr != null) {
            last = tr.getId();
        }
        return new RunStatus(last, runningTestRunId);
    }

    public void start() {
        if (!started) {
            synchronized (this) {
                if (!started) {
                    try {
                        if (context.getApplicationRoot().getTest().isPresent()) {
                            started = true;
                            init();
                            for (TestListener i : testListeners) {
                                i.testsEnabled();
                            }
                            testRunner.enable();
                        }
                    } catch (Exception e) {
                        log.error("Failed to create compiler, runtime compilation will be unavailable", e);
                    }

                }
            }
        }
    }

    public void init() {
        if (!context.getApplicationRoot().getTest().isPresent()) {
            return;
        }
        if (testCuratedApplication == null) {
            try {
                testCuratedApplication = curatedApplication.getQuarkusBootstrap().clonedBuilder()
                        .setMode(QuarkusBootstrap.Mode.TEST)
                        .setDisableClasspathCache(false)
                        .setIsolateDeployment(true)
                        .setBaseClassLoader(getClass().getClassLoader())
                        .setTest(true)
                        .setAuxiliaryApplication(true)
                        .addAdditionalApplicationArchive(new AdditionalDependency(
                                Paths.get(context.getApplicationRoot().getTest().get().getClassesPath()), true,
                                true))
                        .build()
                        .bootstrap();
                compiler = new QuarkusCompiler(testCuratedApplication, compilationProviders, context);
                testRunner = new TestRunner(this, context, testCuratedApplication);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public synchronized void stop() {
        if (started) {
            started = false;
            for (TestListener i : testListeners) {
                i.testsDisabled();
            }
        }
        if (testRunner != null) {

            testRunner.disable();
        }
    }

    public void addListener(TestListener listener) {
        boolean run = false;
        synchronized (this) {
            testListeners.add(listener);
            if (started) {
                run = true;
            }
        }
        listener.listenerRegistered(this);
        if (run) {
            //run outside lock
            listener.testsEnabled();
        }
    }

    public boolean isStarted() {
        return started;
    }

    public TestRunner getTestRunner() {
        return testRunner;
    }

    public CuratedApplication getCuratedApplication() {
        return curatedApplication;
    }

    public QuarkusCompiler getCompiler() {
        return compiler;
    }

    public TestRunResults getTestRunResults() {
        return testRunResults;
    }

    public synchronized void pause() {
        if (started) {
            testRunner.pause();
        }
    }

    public synchronized void resume() {
        if (started) {
            testRunner.resume();
        }
    }

    public TestRunResults getResults() {
        return testRunResults;
    }

    public void setTags(List<String> includeTags, List<String> excludeTags) {
        this.includeTags = includeTags;
        this.excludeTags = excludeTags;
    }

    public void setPatterns(String include, String exclude) {
        this.include = include == null ? null : Pattern.compile(include);
        this.exclude = exclude == null ? null : Pattern.compile(exclude);
    }

    public TestSupport setConfiguredDisplayTestOutput(boolean displayTestOutput) {
        if (explicitDisplayTestOutput != null) {
            this.displayTestOutput = displayTestOutput;
        }
        this.displayTestOutput = displayTestOutput;
        return this;
    }

    @Override
    public TestState currentState() {
        return testState;
    }

    @Override
    public void runAllTests() {
        getTestRunner().runTests();
    }

    @Override
    public void setDisplayTestOutput(boolean displayTestOutput) {
        this.explicitDisplayTestOutput = displayTestOutput;
        this.displayTestOutput = displayTestOutput;
    }

    @Override
    public void runFailedTests() {
        getTestRunner().runFailedTests();
    }

    @Override
    public boolean toggleBrokenOnlyMode() {
        return failingTestsOnly = !failingTestsOnly;
    }

    public static class RunStatus {

        final long lastRun;
        final long running;

        public RunStatus(long lastRun, long running) {
            this.lastRun = lastRun;
            this.running = running;
        }

        public long getLastRun() {
            return lastRun;
        }

        public long getRunning() {
            return running;
        }
    }

}
