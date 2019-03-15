package com.epam.reportportal.junit5;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.opentest4j.TestAbortedException;

import java.util.Calendar;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import io.reactivex.Maybe;

import static rp.com.google.common.base.Throwables.getStackTraceAsString;

/**
 * ReportPortal Listener sends the results of test execution to ReportPortal in RealTime
 */

public class ReportPortalListener implements TestExecutionListener {

    private final ConcurrentMap<String, Maybe<String>> idMapping;
    private final Launch launch;
    private ThreadLocal<Boolean> isDisabledTest = new ThreadLocal<>();

    public ReportPortalListener() {
        this.idMapping = new ConcurrentHashMap<>();
        ReportPortal rp = ReportPortal.builder().build();
        ListenerParameters params = rp.getParameters();
        StartLaunchRQ rq = new StartLaunchRQ();
        rq.setMode(params.getLaunchRunningMode());
        rq.setDescription(params.getDescription());
        rq.setName(params.getLaunchName());
        rq.setTags(params.getTags());
        rq.setStartTime(Calendar.getInstance().getTime());
        this.launch = rp.newLaunch(rq);
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.launch.start();
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        isDisabledTest.set(false);
        startTestItem(testIdentifier, null);
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (Boolean.valueOf(System.getProperty("reportDisabledTests"))) {
            isDisabledTest.set(true);
            startTestItem(testIdentifier, reason);
            finishTestItem(testIdentifier, TestExecutionResult.successful());
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        finishTestItem(testIdentifier, testExecutionResult);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setEndTime(Calendar.getInstance().getTime());
        this.launch.finish(rq);
    }

    private synchronized void startTestItem(TestIdentifier testIdentifier, String reason) {
        TestItem testItem = getTestItem(testIdentifier, launch);
        StartTestItemRQ rq = new StartTestItemRQ();
        rq.setStartTime(Calendar.getInstance().getTime());

        String name = testItem.name;
        rq.setName(name.length() > 256 ? name.substring(0, 200) + "..." : name);

        Set<String> tags = testItem.tags;
        rq.setTags(tags);

        if (null != reason) {
            rq.setDescription(reason);
        } else {
            rq.setDescription(testItem.description);
        }

        rq.setUniqueId(testIdentifier.getUniqueId());
        rq.setType(testIdentifier.isContainer() ? "SUITE" : "STEP");
        rq.setRetry(false);
        Maybe<String> itemId = testIdentifier.getParentId()
                                             .map(parent -> Optional.ofNullable(idMapping.get(parent)))
                                             .map(parentId -> this.launch.startTestItem(parentId.orElse(null), rq))
                                             .orElseGet(() -> this.launch.startTestItem(rq));
        this.idMapping.put(testIdentifier.getUniqueId(), itemId);
    }

    private synchronized void finishTestItem(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        FinishTestItemRQ rq = new FinishTestItemRQ();
        if (isDisabledTest.get()) {
            rq.setStatus("SKIPPED");
        } else {
            rq.setStatus(getExecutionStatus(testExecutionResult));
        }
        rq.setEndTime(Calendar.getInstance().getTime());
        this.launch.finishTestItem(this.idMapping.get(testIdentifier.getUniqueId()), rq);
    }

    private static String getExecutionStatus(TestExecutionResult testExecutionResult) {
        Optional<Throwable> exception = Optional.of(testExecutionResult.getThrowable()).orElse(null);
        if (!exception.isPresent()) {
            return Statuses.PASSED;
        } else if (exception.get() instanceof TestAbortedException) {
            sendStackTraceToRP(exception.get());
            return Statuses.FAILED;
        } else {
            sendStackTraceToRP(exception.get());
            return Statuses.FAILED;
        }
    }

    private static void sendStackTraceToRP(final Throwable cause) {
        ReportPortal.emitLog(itemId -> {
            SaveLogRQ rq = new SaveLogRQ();
            rq.setTestItemId(itemId);
            rq.setLevel("ERROR");
            rq.setLogTime(Calendar.getInstance().getTime());
            if (cause != null) {
                rq.setMessage(getStackTraceAsString(cause));
            } else {
                rq.setMessage("Test has failed without exception");
            }
            rq.setLogTime(Calendar.getInstance().getTime());
            return rq;
        });
    }

    protected class TestItem {

        private String name;
        private String description;
        private Set<String> tags;

        protected String getName() {
            return name;
        }

        protected String getDescription() {
            return description;
        }

        protected Set<String> getTags() {
            return tags;
        }

        public TestItem(String name, String description, Set<String> tags) {
            this.name = name;
            this.description = description;
            this.tags = tags;
        }
    }

    protected TestItem getTestItem(TestIdentifier identifier, Launch launch) {
        String name = identifier.getDisplayName();
        String description = identifier.getLegacyReportingName();
        Set<String> tags = identifier.getTags().stream().map(TestTag::getName).collect(Collectors.toSet());
        return new TestItem(name, description, tags);
    }
}
