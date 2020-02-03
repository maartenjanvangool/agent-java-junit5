package com.epam.reportportal.junit5;

import com.epam.reportportal.junit5.features.SingleDynamicTest;
import com.epam.reportportal.junit5.util.TestUtils;
import com.epam.reportportal.service.Launch;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import io.reactivex.Maybe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CodeReferenceTests {

	private static final Map<String, Launch> launchMap = new ConcurrentHashMap<>();

	public static class CodeReferenceTestExtension extends ReportPortalExtension {
		@Override
		Launch getLaunch(ExtensionContext context) {
			return launchMap.computeIfAbsent(context.getRoot().getUniqueId(), (id) -> {
				Launch launch = mock(Launch.class);
				when(launch.startTestItem(any(), any())).thenAnswer((Answer<Maybe<String>>) invocation -> TestUtils.createItemUuidMaybe());
				return launch;
			});
		}
	}

	@AfterEach
	public void cleanUp() {
		launchMap.clear();
	}

	@Test
	public void verify_dynamic_test_code_reference_generation() {
		TestUtils.runClasses(SingleDynamicTest.class);

		assertThat(launchMap.entrySet(), hasSize(1));
		Launch launch = launchMap.entrySet().iterator().next().getValue();

		verify(launch, times(1)).startTestItem(isNull(), any()); // Start parent Suite

		ArgumentCaptor<StartTestItemRQ> captor = ArgumentCaptor.forClass(StartTestItemRQ.class);
		verify(launch, times(2)).startTestItem(notNull(), captor.capture()); // Start a test class and a test

		List<StartTestItemRQ> rqValues = captor.getAllValues();
		String testName = SingleDynamicTest.class.getCanonicalName() + ".testForTestFactory";
		assertThat(rqValues.get(0).getCodeRef(), equalTo(testName));
		assertThat(rqValues.get(1).getCodeRef(), equalTo(testName + "$" + SingleDynamicTest.TEST_CASE_DISPLAY_NAME));
	}

}