/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.junit5;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.Calendar;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static rp.com.google.common.base.Throwables.getStackTraceAsString;

/**
 * ReportPortal Listener sends the results of test execution to ReportPortal in RealTime
 *
 * @author <a href="mailto:andrei_varabyeu@epam.com">Andrei Varabyeu</a>
 */

public class ReportPortalListener implements TestExecutionListener {

	private static final String SKIPPED_ISSUE_KEY = "skippedIssue";

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
		rq.setAttributes(params.getAttributes());
		rq.setStartTime(Calendar.getInstance().getTime());

		Boolean skippedAnIssue = params.getSkippedAnIssue();
		ItemAttributesRQ skippedIssueAttr = new ItemAttributesRQ();
		skippedIssueAttr.setKey(SKIPPED_ISSUE_KEY);
		skippedIssueAttr.setValue(skippedAnIssue == null ? "true" : skippedAnIssue.toString());
		skippedIssueAttr.setSystem(true);
		rq.getAttributes().add(skippedIssueAttr);

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
		String name = testItem.getName();
		rq.setName(name.length() > 256 ? name.substring(0, 200) + "..." : name);
		rq.setAttributes(testItem.getAttributes());
		if (null != reason) {
			rq.setDescription(reason);
		} else {
			rq.setDescription(testItem.getDescription());
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
		TestExecutionResult.Status status = testExecutionResult.getStatus();
		if (status.equals(TestExecutionResult.Status.SUCCESSFUL)) {
			return Statuses.PASSED;
		} else {
			sendStackTraceToRP(testExecutionResult.getThrowable().orElse(null));
			return Statuses.FAILED;
		}
	}

	private static void sendStackTraceToRP(final Throwable cause) {
		ReportPortal.emitLog(itemId -> {
			SaveLogRQ rq = new SaveLogRQ();
			rq.setItemId(itemId);
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

	protected static class TestItem {

		private String name;
		private String description;
		private Set<ItemAttributesRQ> attributes;

		protected String getName() {
			return name;
		}

		protected String getDescription() {
			return description;
		}

		protected Set<ItemAttributesRQ> getAttributes() {
			return attributes;
		}

		public TestItem(String name, String description, Set<String> tags) {
			this.name = name;
			this.description = description;
			this.attributes = tags.stream().map(it -> new ItemAttributesRQ(null, it)).collect(Collectors.toSet());
		}
	}

	protected TestItem getTestItem(TestIdentifier identifier, Launch launch) {
		String name = identifier.getDisplayName();
		String description = identifier.getLegacyReportingName();
		Set<String> tags = identifier.getTags().stream().map(TestTag::getName).collect(Collectors.toSet());
		return new TestItem(name, description, tags);
	}
}
