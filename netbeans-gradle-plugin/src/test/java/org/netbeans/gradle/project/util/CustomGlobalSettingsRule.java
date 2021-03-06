package org.netbeans.gradle.project.util;

import org.jtrim.utils.ExceptionHelper;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.netbeans.gradle.project.properties.GenericProfileSettings;
import org.netbeans.gradle.project.properties.global.CommonGlobalSettings;

public final class CustomGlobalSettingsRule implements TestRule {
    private final NbConsumer<? super CommonGlobalSettings> settingsProvider;

    public CustomGlobalSettingsRule(NbConsumer<? super CommonGlobalSettings> settingsProvider) {
        ExceptionHelper.checkNotNullArgument(settingsProvider, "settingsProvider");

        this.settingsProvider = settingsProvider;
    }

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                withCleanMemorySettings(base);
            }
        };
    }

    private void withCleanMemorySettings(final Statement base) throws Throwable {
        withCleanMemorySettings(new NbConsumer<GenericProfileSettings>() {
            @Override
            public void accept(GenericProfileSettings settings) {
                settingsProvider.accept(CommonGlobalSettings.getDefault());

                try {
                    base.evaluate();
                } catch (Throwable ex) {
                    throw new TestExceptionWrapper(ex);
                }
            }
        });
    }

    private static void withCleanMemorySettings(NbConsumer<GenericProfileSettings> task) throws Throwable {
        try {
            CommonGlobalSettings.withCleanMemorySettings(task);
        } catch (TestExceptionWrapper ex) {
            throw ex.getCause();
        }
    }

    private static class TestExceptionWrapper extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TestExceptionWrapper(Throwable cause) {
            super(cause);
        }
    }
}
