package com.epam.reportportal.junit5;

import com.epam.reportportal.service.Launch;

import org.junit.platform.launcher.TestIdentifier;

public class CustomListener extends ReportPortalListener {

    @Override
    protected TestItem getTestItem(
        TestIdentifier identifier, Launch launch) {
        TestItem testItem = super.getTestItem(identifier, launch);
        return new TestItem("Name: " + testItem.getName(), "Description: " + testItem.getDescription(),
            testItem.getTags());
    }
}
