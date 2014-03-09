package org.netbeans.gradle.project.properties2;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jtrim.cancel.Cancellation;
import org.jtrim.cancel.CancellationToken;
import org.jtrim.collections.EqualityComparator;
import org.jtrim.concurrent.CancelableTask;
import org.jtrim.concurrent.GenericUpdateTaskExecutor;
import org.jtrim.concurrent.MonitorableTaskExecutorService;
import org.jtrim.concurrent.TaskExecutor;
import org.jtrim.concurrent.UpdateTaskExecutor;
import org.jtrim.event.CopyOnTriggerListenerManager;
import org.jtrim.event.EventDispatcher;
import org.jtrim.event.ListenerManager;
import org.jtrim.event.ListenerRef;
import org.jtrim.event.ListenerRegistries;
import org.jtrim.property.MutableProperty;
import org.jtrim.property.PropertyFactory;
import org.jtrim.property.PropertySourceProxy;
import org.jtrim.swing.concurrent.SwingTaskExecutor;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.gradle.project.NbTaskExecutors;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public final class ProfileSettings {
    private static final Logger LOGGER = Logger.getLogger(ProfileSettings.class.getName());
    private static final int FILE_STREAM_BUFFER_SIZE = 8 * 1024;
    private static final Set<ConfigPath> ROOT_PATH = Collections.singleton(ConfigPath.ROOT);

    // Must be FIFO
    private static final TaskExecutor EVENT_THREAD
            = SwingTaskExecutor.getStrictExecutor(false);

    // Must be FIFO / ProfileSettings instance.
    private static final MonitorableTaskExecutorService DOCUMENT_EVENT_THREAD
            = NbTaskExecutors.newExecutor("Document-Change-Events", 1);

    private final ListenerManager<ConfigUpdateListener> configUpdateListeners;
    private final EventDispatcher<ConfigUpdateListener, Collection<ConfigPath>> configUpdateDispatcher;

    private final ReentrantLock configLock;
    private ConfigTree.Builder currentConfig;

    public ProfileSettings() {
        this.configLock = new ReentrantLock();
        this.currentConfig = new ConfigTree.Builder();
        this.configUpdateListeners = new CopyOnTriggerListenerManager<>();

        this.configUpdateDispatcher = new EventDispatcher<ConfigUpdateListener, Collection<ConfigPath>>() {
            @Override
            public void onEvent(ConfigUpdateListener eventListener, Collection<ConfigPath> arg) {
                eventListener.configUpdated(arg);
            }
        };
    }

    public static boolean isEventThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    ListenerRef addDocumentChangeListener(final Runnable listener) {
        ExceptionHelper.checkNotNullArgument(listener, "listener");

        return configUpdateListeners.registerListener(new ConfigUpdateListener() {
            @Override
            public void configUpdated(Collection<ConfigPath> changedPaths) {
                listener.run();
            }
        });
    }

    private static DocumentBuilder getDocumentBuilder() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            throw new RuntimeException("Cannot create Document builder.", ex);
        }
    }

    private static Document getEmptyDocument() {
        return getDocumentBuilder().newDocument();
    }

    private static Document readXml(InputStream xmlSource) throws IOException, SAXException {
        ExceptionHelper.checkNotNullArgument(xmlSource, "xmlSource");

        return getDocumentBuilder().parse(xmlSource);
    }

    private static Document readXml(Path xmlFile) throws IOException, SAXException {
        ExceptionHelper.checkNotNullArgument(xmlFile, "xmlFile");

        if (!Files.exists(xmlFile)) {
            return getEmptyDocument();
        }

        try (InputStream fileInput = Files.newInputStream(xmlFile);
                InputStream input = new BufferedInputStream(fileInput, FILE_STREAM_BUFFER_SIZE)) {
            return readXml(input);
        }
    }

    public void loadFromFile(Path xmlFile) {
        Document document;
        try {
            document = readXml(xmlFile);
        } catch (IOException | SAXException ex) {
            LOGGER.log(Level.INFO, "Unable to parse XML config file: " + xmlFile, ex);
            return;
        }

        loadFromDocument(document);
    }

    public void loadFromStream(InputStream xmlSource) {
        Document document;
        try {
            document = readXml(xmlSource);
        } catch (IOException | SAXException ex) {
            LOGGER.log(Level.INFO, "Unable to parse XML config file from stream.", ex);
            return;
        }

        loadFromDocument(document);
    }

    private void fireDocumentUpdate(final Collection<ConfigPath> path) {
        DOCUMENT_EVENT_THREAD.execute(Cancellation.UNCANCELABLE_TOKEN, new CancelableTask() {
            @Override
            public void execute(CancellationToken cancelToken) {
                configUpdateListeners.onEvent(configUpdateDispatcher, path);
            }
        }, null);
    }

    private void loadFromDocument(final Document document) {
        ExceptionHelper.checkNotNullArgument(document, "document");

        ConfigTree.Builder parsedDocument = ConfigXmlUtils.parseDocument(document);

        configLock.lock();
        try {
            currentConfig = parsedDocument;
        } finally {
            configLock.unlock();
        }

        fireDocumentUpdate(ROOT_PATH);
    }

    private static ConfigTree createSubTree(ConfigTree.Builder builer, ConfigPath path) {
        ConfigTree.Builder subBuilder = builer.getDeepSubBuilder(path);
        subBuilder.detachSubTreeBuilders();
        return subBuilder.create();
    }

    private ConfigTree getSubConfig(ConfigPath path) {
        configLock.lock();
        try {
            return createSubTree(currentConfig, path);
        } finally {
            configLock.unlock();
        }
    }

    private ConfigTree getSubConfig(ConfigPath basePath, ConfigPath[] relPaths) {
        if (relPaths.length == 1) {
            assert relPaths[0].getKeyCount() == 0;

            // Common case
            return getSubConfig(basePath);
        }

        ConfigTree.Builder result = new ConfigTree.Builder();
        configLock.lock();
        try {
            ConfigTree.Builder baseBuilder = currentConfig.getDeepSubBuilder(basePath);
            for (ConfigPath relPath: relPaths) {
                ConfigTree subTree = createSubTree(baseBuilder, relPath);
                setChildTree(baseBuilder, relPath, subTree);
            }
        } finally {
            configLock.unlock();
        }

        return result.create();
    }

    public <ValueKey, ValueType> MutableProperty<ValueType> getProperty(
            ConfigPath configPath,
            PropertyDef<ValueKey, ValueType> propertyDef) {
        return getProperty(Collections.singleton(configPath), propertyDef);
    }

    public <ValueKey, ValueType> MutableProperty<ValueType> getProperty(
            Collection<ConfigPath> configPaths,
            PropertyDef<ValueKey, ValueType> propertyDef) {
        ExceptionHelper.checkNotNullArgument(configPaths, "configPaths");
        ExceptionHelper.checkNotNullArgument(propertyDef, "propertyDef");

        return new DomTrackingProperty<>(configPaths, propertyDef);
    }

    private static List<ConfigPath> copyPaths(Collection<ConfigPath> paths) {
        switch (paths.size()) {
            case 0:
                return Collections.emptyList();
            case 1:
                return Collections.singletonList(paths.iterator().next());
            default:
                return Collections.unmodifiableList(new ArrayList<>(paths));
        }
    }

    private static ConfigPath[] removeTopParents(int removeCount, ConfigPath[] paths) {
        if (removeCount == 0) {
            return paths;
        }

        ConfigPath[] result = new ConfigPath[paths.length];
        for (int i = 0; i < result.length; i++) {
            List<ConfigKey> keys = paths[i].getKeys();
            result[i] = ConfigPath.fromKeys(keys.subList(removeCount, keys.size()));
        }
        return result;
    }

    private static ConfigPath getCommonParent(ConfigPath[] paths) {
        if (paths.length == 1) {
            // Almost every time this path is taken.
            return paths[0];
        }
        if (paths.length == 0) {
            return ConfigPath.ROOT;
        }

        int minLength = paths[0].getKeyCount();
        for (int i = 1; i < paths.length; i++) {
            int keyCount = paths[i].getKeyCount();
            if (keyCount < minLength) minLength = keyCount;
        }

        List<ConfigKey> result = new LinkedList<>();

        outerLoop:
        for (int keyIndex = 0; keyIndex < minLength; keyIndex++) {
            ConfigKey key = paths[0].getKeyAt(keyIndex);
            for (int pathIndex = 1; pathIndex < paths.length; pathIndex++) {
                if (!key.equals(paths[pathIndex].getKeyAt(keyIndex))) {
                    break outerLoop;
                }
            }
            result.add(key);
        }

        return ConfigPath.fromKeys(result);
    }

    private static void setChildTree(ConfigTree.Builder builder, ConfigPath path, ConfigTree content) {
        int keyCount = path.getKeyCount();
        assert keyCount > 0;

        ConfigTree.Builder subConfig = builder;
        for (int i = 0; i < keyCount - 1; i++) {
            subConfig = subConfig.getSubBuilder(path.getKeyAt(i));
        }
        subConfig.setChildTree(path.getKeyAt(keyCount - 1), content);
    }

    private <ValueKey> ValueKey getValueKeyFromCurrentConfig(
            ConfigPath parent,
            ConfigPath[] relativePaths,
            PropertyKeyEncodingDef<ValueKey> keyEncodingDef) {

        ConfigTree parentBasedConfig = getSubConfig(parent, relativePaths);
        return keyEncodingDef.decode(parentBasedConfig);
    }

    private static interface ConfigUpdateListener {
        public void configUpdated(Collection<ConfigPath> changedPaths);
    }

    private class DomTrackingProperty<ValueKey, ValueType>
    implements
            MutableProperty<ValueType> {

        private final ConfigPath configParent;
        private final ConfigPath[] configPaths;
        private final ConfigPath[] relativeConfigPaths;
        private final List<ConfigPath> configPathsAsList;

        private final PropertyKeyEncodingDef<ValueKey> keyEncodingDef;
        private final PropertyValueDef<ValueKey, ValueType> valueDef;
        private final EqualityComparator<? super ValueKey> valueKeyEquality;

        private final UpdateTaskExecutor valueUpdaterThread;
        private final UpdateTaskExecutor eventThread;

        private final PropertySourceProxy<ValueType> source;

        public DomTrackingProperty(
                Collection<ConfigPath> configPaths,
                PropertyDef<ValueKey, ValueType> propertyDef) {

            ExceptionHelper.checkNotNullArgument(configPaths, "configPaths");
            ExceptionHelper.checkNotNullArgument(propertyDef, "propertyDef");

            this.configPathsAsList = copyPaths(configPaths);
            this.configPaths = configPathsAsList.toArray(new ConfigPath[configPathsAsList.size()]);
            this.configParent = getCommonParent(this.configPaths);
            this.relativeConfigPaths = removeTopParents(configParent.getKeyCount(), this.configPaths);

            this.keyEncodingDef = propertyDef.getKeyEncodingDef();
            this.valueDef = propertyDef.getValueDef();
            this.valueKeyEquality = propertyDef.getValueKeyEquality();

            ValueKey initialValueKey = getValueKeyFromCurrentConfig(
                    this.configParent,
                    this.relativeConfigPaths,
                    this.keyEncodingDef);
            this.source = PropertyFactory.proxySource(valueDef.property(initialValueKey));

            this.valueUpdaterThread = new GenericUpdateTaskExecutor(DOCUMENT_EVENT_THREAD);
            this.eventThread = new GenericUpdateTaskExecutor(EVENT_THREAD);

            ExceptionHelper.checkNotNullElements(this.configPaths, "configPaths");
        }

        private void updateConfigAtPath(ConfigPath path, ConfigTree content) {
            assert configLock.isHeldByCurrentThread();

            if (path.getKeyCount() == 0) {
                currentConfig = new ConfigTree.Builder(content);
            }
            else {
                setChildTree(currentConfig, path, content);
            }
        }

        private void updateConfigFromKey(ValueKey valueKey) {
            ConfigTree encodedValueKey = keyEncodingDef.encode(valueKey);

            configLock.lock();
            try {
                // TODO: Report unsaved keys.
                int pathCount = relativeConfigPaths.length;
                for (int i = 0; i < pathCount; i++) {
                    ConfigPath relativePath = relativeConfigPaths[i];
                    ConfigPath path = configPaths[i];

                    ConfigTree configTree = encodedValueKey.getDeepSubTree(relativePath);
                    updateConfigAtPath(path, configTree);
                }
            } finally {
                configLock.unlock();
            }

            fireDocumentUpdate(configPathsAsList);
        }

        @Override
        public void setValue(final ValueType value) {
            final ValueKey valueKey = valueDef.getKeyFromValue(value);
            if (!updateSource(valueKey)) {
                return;
            }

            valueUpdaterThread.execute(new Runnable() {
                @Override
                public void run() {
                    updateConfigFromKey(valueKey);
                }
            });
        }

        @Override
        public ValueType getValue() {
            return source.getValue();
        }

        private boolean affectsThis(Collection<ConfigPath> changedPaths) {
            if (changedPaths == configPathsAsList) {
                // This event is comming from us, so we won't update.
                // This check is not necessary for correctness but to avoid
                // unecessary reparsing of the property value.
                return false;
            }

            for (ConfigPath changedPath: changedPaths) {
                for (ConfigPath ourPath: configPaths) {
                    if (changedPath.isParentOfOrEqual(ourPath)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private ValueKey getValueKey() {
            return getValueKeyFromCurrentConfig(configParent, relativeConfigPaths, keyEncodingDef);
        }

        private boolean updateSource(ValueKey valueKey) {
            // TODO: Check if we really need to update.
            source.replaceSource(valueDef.property(valueKey));
            return true;
        }

        private void updateFromConfig() {
            updateSource(getValueKey());
        }

        @Override
        public ListenerRef addChangeListener(final Runnable listener) {
            ExceptionHelper.checkNotNullArgument(listener, "listener");

            ListenerRef ref1 = configUpdateListeners.registerListener(new ConfigUpdateListener() {
                @Override
                public void configUpdated(Collection<ConfigPath> changedPaths) {
                    if (affectsThis(changedPaths)) {
                        updateFromConfig();
                    }
                }
            });

            ListenerRef ref2 = source.addChangeListener(new Runnable() {
                @Override
                public void run() {
                    eventThread.execute(listener);
                }
            });

            return ListenerRegistries.combineListenerRefs(ref1, ref2);
        }

        @Override
        public String toString() {
            return "Property{" + configPaths + '}';
        }
    }
}