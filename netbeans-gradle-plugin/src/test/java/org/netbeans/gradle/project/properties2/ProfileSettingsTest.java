package org.netbeans.gradle.project.properties2;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jtrim.cancel.Cancellation;
import org.jtrim.cancel.OperationCanceledException;
import org.jtrim.collections.Equality;
import org.jtrim.concurrent.WaitableSignal;
import org.jtrim.property.MutableProperty;
import org.jtrim.property.PropertyFactory;
import org.jtrim.property.PropertySource;
import org.junit.Ignore;
import org.junit.Test;
import org.netbeans.gradle.project.properties2.standard.PlatformId;
import org.netbeans.gradle.project.properties2.standard.TargetPlatformProperty;

import static org.junit.Assert.*;

public class ProfileSettingsTest {
    private static void readDocument(ProfileSettings settings, String configFileName) throws IOException {
        try (InputStream input = TestResourceUtils.openResource(configFileName)) {
            settings.loadFromStream(input);
        }
    }

    private static void readFromSettings1(ProfileSettings settings) throws IOException {
        readDocument(settings, "settings1.xml");
    }

    private static ConfigPath getConfigPath(String... keys) {
        return ConfigPath.fromKeys(Arrays.asList(keys));
    }

    private static <ValueKey, ValueType> MutableProperty<ValueType> getProperty(
            ProfileSettings settings,
            PropertyDef<ValueKey, ValueType> propertyDef,
            String... keys) {

        ConfigPath configPath = getConfigPath(keys);
        return settings.getProperty(configPath, propertyDef);
    }

    private static void setSimpleEncodingDef(PropertyDef.Builder<ConfigTree, ?> result) {
        result.setKeyEncodingDef(new PropertyKeyEncodingDef<ConfigTree>() {
            @Override
            public ConfigTree decode(ConfigTree config) {
                return config;
            }

            @Override
            public ConfigTree encode(ConfigTree value) {
                return value;
            }
        });
    }

    private static PropertyDef<PlatformId, PlatformId> getTargetPlatformProfileDef() {
        PropertyDef.Builder<PlatformId, PlatformId> result = new PropertyDef.Builder<>();
        result.setValueDef(new PropertyValueDef<PlatformId, PlatformId>() {
            @Override
            public PropertySource<PlatformId> property(PlatformId valueKey) {
                return PropertyFactory.constSource(valueKey);
            }

            @Override
            public PlatformId getKeyFromValue(PlatformId value) {
                return value;
            }
        });
        result.setKeyEncodingDef(TargetPlatformProperty.getPropertyDef().getKeyEncodingDef());

        return result.create();
    }

    private static PropertyDef<ConfigTree, String> getTextProfileDef(boolean naturalEquals) {
        PropertyDef.Builder<ConfigTree, String> result = new PropertyDef.Builder<>();
        result.setValueDef(new PropertyValueDef<ConfigTree, String>() {
            @Override
            public PropertySource<String> property(ConfigTree valueKey) {
                return PropertyFactory.constSource(valueKey != null
                        ? valueKey.getValue(null)
                        : null);
            }

            @Override
            public ConfigTree getKeyFromValue(String value) {
                if (value == null) {
                    return null;
                }

                return ConfigTree.singleValue(value);
            }
        });
        if (!naturalEquals) {
            result.setValueKeyEquality(Equality.referenceEquality());
        }
        setSimpleEncodingDef(result);

        return result.create();
    }

    private static MutableProperty<String> getTextProperty(
            ProfileSettings settings,
            String... keys) {
        return getTextProperty(settings, true, keys);
    }

    private static MutableProperty<String> getTextProperty(
            ProfileSettings settings,
            boolean naturalEquals,
            String... keys) {

        ConfigPath configPath = getConfigPath(keys);
        return settings.getProperty(configPath, getTextProfileDef(naturalEquals));
    }

    private void testSetValueOfTextProperty(
            String initialValue,
            String newValue,
            String... propertyPath) throws IOException {
        testSetValueOfTextProperty(initialValue, newValue, true, propertyPath);
    }

    private void testSetValueOfTextProperty(
            String initialValue,
            String newValue,
            boolean naturalEquals,
            String... propertyPath) throws IOException {

        ProfileSettings settings = new ProfileSettings();
        readFromSettings1(settings);

        String propertyName = Arrays.toString(propertyPath);

        MutableProperty<String> property = getTextProperty(settings, naturalEquals, propertyPath);
        assertEquals(propertyName, initialValue, property.getValue());

        WaitableListener documentListener = new WaitableListener();
        settings.addDocumentChangeListener(documentListener);

        WaitableListener listener = new WaitableListener();
        property.addChangeListener(listener);

        property.setValue(newValue);
        assertEquals(propertyName, newValue, property.getValue());

        listener.waitForCall("Value change for text node.");
        documentListener.waitForCall("Document change for text node.");
    }

    @Test(timeout = 30000)
    public void testSetValueOfRootTextPropertyWithReferenceComparison() throws IOException {
        testSetValueOfTextProperty("UTF-8", "ISO-8859-1", false, "source-encoding");
        testSetValueOfTextProperty("j2se", "j2me", false, "target-platform-name");
        testSetValueOfTextProperty("1.7", "1.6", false, "target-platform");
        testSetValueOfTextProperty("1.7", "1.8", false, "source-level");
    }

    @Test
    public void testSetValueOfRootTextProperty() throws IOException {
        for (int i = 0; i < 100; i++) {
            testSetValueOfTextProperty("UTF-8", "ISO-8859-1", "source-encoding");
        }

        testSetValueOfTextProperty("j2se", "j2me", "target-platform-name");
        testSetValueOfTextProperty("1.7", "1.6", "target-platform");
        testSetValueOfTextProperty("1.7", "1.8", "source-level");
    }

    @Test
    public void testSetValueOfDeepTextProperty() throws IOException {
        for (int i = 0; i < 100; i++) {
            testSetValueOfTextProperty("LF", "CRLF", "auxiliary", "com-junichi11-netbeans-changelf.lf-kind");
        }
    }

    @Test
    public void testSetValueOfDeepTextPropertyWithReferenceComparison() throws IOException {
        testSetValueOfTextProperty("LF", "CRLF", false, "auxiliary", "com-junichi11-netbeans-changelf.lf-kind");
    }

    private void testLoadFromFileLater(String expectedValue, String... propertyPath) throws IOException {
        ProfileSettings settings = new ProfileSettings();

        MutableProperty<String> property = getTextProperty(settings, propertyPath);
        readFromSettings1(settings);

        assertEquals(Arrays.toString(propertyPath), expectedValue, property.getValue());
    }

    @Test
    public void testLoadFromFileLater() throws IOException {
        testLoadFromFileLater("UTF-8","source-encoding");
        testLoadFromFileLater("j2se", "target-platform-name");
        testLoadFromFileLater("1.7", "target-platform");
        testLoadFromFileLater("1.7", "source-level");
    }

    // This test must be redesigned because target platform now cannot be fooled.
    @Ignore
    @Test
    public void testIntersectingMultiNodeProperties() throws IOException {
        ProfileSettings settings = new ProfileSettings();
        readFromSettings1(settings);

        List<ConfigPath> configPaths1 = Arrays.asList(
                getConfigPath("target-platform-name"),
                getConfigPath("target-platform"));
        List<ConfigPath> configPaths2 = Arrays.asList(
                getConfigPath("target-platform-name"),
                getConfigPath("source-level"));

        MutableProperty<PlatformId> property1
                = settings.getProperty(configPaths1, getTargetPlatformProfileDef());
        MutableProperty<PlatformId> property2
                = settings.getProperty(configPaths2, getTargetPlatformProfileDef());

        PlatformId propert1Initial = new PlatformId("j2se", "1.7");
        PlatformId propert2Initial = propert1Initial;

        assertEquals(propert1Initial, property1.getValue());
        assertEquals(propert2Initial, property2.getValue());

        property1.setValue(new PlatformId("j2me", "1.5"));
        assertEquals(new PlatformId("j2me", "1.7"), property2.getValue());
    }

    @Test
    public void testMultiNodeProperty() throws IOException {
        ProfileSettings settings = new ProfileSettings();
        readFromSettings1(settings);

        List<ConfigPath> configPaths = Arrays.asList(
                getConfigPath("target-platform-name"),
                getConfigPath("target-platform"));

        MutableProperty<PlatformId> property
                = settings.getProperty(configPaths, getTargetPlatformProfileDef());

        String propertyName = "TargetPlatform";

        assertEquals(propertyName, new PlatformId("j2se", "1.7"), property.getValue());

        WaitableListener documentListener = new WaitableListener();
        settings.addDocumentChangeListener(documentListener);

        WaitableListener listener = new WaitableListener();
        property.addChangeListener(listener);

        PlatformId newValue = new PlatformId("j2me", "1.5");
        property.setValue(newValue);
        assertEquals(propertyName, newValue, property.getValue());

        listener.waitForCall("Value change for multi node.");
        documentListener.waitForCall("Document change for multi node.");
    }

    private static final class WaitableListener implements Runnable {
        private final WaitableSignal calledSignal;

        public WaitableListener() {
            this.calledSignal = new WaitableSignal();
        }

        @Override
        public void run() {
            calledSignal.signal();
        }

        public void waitForCall(String taskName) {
            if (!calledSignal.tryWaitSignal(Cancellation.UNCANCELABLE_TOKEN, 10, TimeUnit.SECONDS)) {
                throw new OperationCanceledException("Timeout: " + taskName);
            }
        }
    }
}
