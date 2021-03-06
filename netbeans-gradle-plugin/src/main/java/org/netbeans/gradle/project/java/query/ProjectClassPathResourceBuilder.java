package org.netbeans.gradle.project.java.query;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jtrim.collections.CollectionsEx;
import org.jtrim.utils.ExceptionHelper;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.gradle.model.java.JavaOutputDirs;
import org.netbeans.gradle.model.java.JavaSourceGroup;
import org.netbeans.gradle.model.java.JavaSourceSet;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.api.entry.ProjectPlatform;
import org.netbeans.gradle.project.java.JavaExtension;
import org.netbeans.gradle.project.java.model.JavaProjectDependencies;
import org.netbeans.gradle.project.java.model.JavaProjectDependencyDef;
import org.netbeans.gradle.project.java.model.NbJarOutput;
import org.netbeans.gradle.project.java.model.NbJavaModel;
import org.netbeans.gradle.project.java.model.NbJavaModule;
import org.netbeans.gradle.project.properties.global.CommonGlobalSettings;
import org.netbeans.gradle.project.util.ExcludeIncludeRules;
import org.netbeans.gradle.project.util.FileGroupFilter;
import org.netbeans.gradle.project.util.UnionFileGroupFilter;
import org.netbeans.gradle.project.util.UrlFactory;
import org.netbeans.spi.java.classpath.PathResourceImplementation;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.util.Lookup;

public final class ProjectClassPathResourceBuilder {
    private static final Logger LOGGER = Logger.getLogger(ProjectClassPathResourceBuilder.class.getName());

    private final NbJavaModel projectModel;
    private final Map<File, JavaProjectDependencyDef> translatedDependencies;
    private final ProjectPlatform currentPlatform;

    private Set<File> missing;
    private Map<ClassPathKey, List<PathResourceImplementation>> classpathResources;

    // Maps JAR name to source set output directory.
    private Map<String, Set<File>> openedProjectsOutput;

    private final UrlFactory urlForArchiveFactory;

    public ProjectClassPathResourceBuilder(
            NbJavaModel projectModel,
            Map<File, JavaProjectDependencyDef> translatedDependencies,
            ProjectPlatform currentPlatform) {
        ExceptionHelper.checkNotNullArgument(projectModel, "projectModel");
        ExceptionHelper.checkNotNullArgument(translatedDependencies, "translatedDependencies");
        ExceptionHelper.checkNotNullArgument(currentPlatform, "currentPlatform");

        this.projectModel = projectModel;
        this.translatedDependencies = translatedDependencies;
        this.currentPlatform = currentPlatform;
        this.classpathResources = null;
        this.missing = null;
        this.openedProjectsOutput = null;
        this.urlForArchiveFactory = UrlFactory.getDefaultArchiveOrDirFactory();
    }

    public void build() {
        openedProjectsOutput = findOpenedProjectsOutput();
        classpathResources = new HashMap<>();
        missing = new HashSet<>();

        NbJavaModule mainModule = projectModel.getMainModule();
        for (JavaSourceSet sourceSet: mainModule.getSources()) {
            loadPathResources(sourceSet, missing);
        }

        loadBootClassPath();
        loadAllRuntimeClassPath(mainModule);
        loadAllBuildOutputClassPath(projectModel);

        loadCompileForGlobalClassPath(projectModel);
        loadRuntimeForGlobalClassPath(projectModel);
    }

    /**
     * Returns all the currently opened Gradle Java projects, including the projects
     * referenced by them (project dependencies).
     */
    private static Collection<JavaExtension> getAllOpenedGradleJavaProjects() {
        Project[] openedProjects = OpenProjects.getDefault().getOpenProjects();
        Map<File, JavaExtension> result = new HashMap<>();

        for (Project project: openedProjects) {
            Lookup lookup = project.getLookup();
            NbGradleProject gradleProject = lookup.lookup(NbGradleProject.class);
            JavaExtension javaExt = lookup.lookup(JavaExtension.class);

            if (javaExt != null && gradleProject != null) {
                result.put(gradleProject.getProjectDirectoryAsFile(), javaExt);

                JavaProjectDependencies projectDependencies = javaExt.getProjectDependencies();
                for (JavaProjectDependencyDef dependency: projectDependencies.translatedDependencies().getValue().values()) {
                    JavaExtension dependencyJavaExt = dependency.getJavaExt();
                    result.put(dependencyJavaExt.getProjectDirectoryAsFile(), dependencyJavaExt);
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private static Map<String, Set<File>> findOpenedProjectsOutput() {
        if (!CommonGlobalSettings.getDefault().detectProjectDependenciesByJarName().getActiveValue()) {
            return null;
        }

        Collection<JavaExtension> javaExts = getAllOpenedGradleJavaProjects();
        Map<String, Set<File>> result = CollectionsEx.newHashMap(javaExts.size());
        for (JavaExtension javaExt: javaExts) {
            NbJavaModule mainModule = javaExt.getCurrentModel().getMainModule();
            for (NbJarOutput jarOutput: mainModule.getJarOutputs()) {
                String key = jarOutput.getJar().getName().toLowerCase(Locale.ROOT);
                Set<File> classDirs = jarOutput.getClassDirs();

                if (!classDirs.isEmpty() && (!result.containsKey(key) || jarOutput.isDefaultJar())) {
                    result.put(key, jarOutput.getClassDirs());
                }
            }
        }
        return result;
    }

    private List<PathResourceImplementation> getBuildOutputDirsAsPathResources(JavaSourceSet sourceSet) {
        JavaOutputDirs outputDirs = sourceSet.getOutputDirs();
        PathResourceImplementation classesDir = toPathResource(outputDirs.getClassesDir(), urlForArchiveFactory);
        PathResourceImplementation resourcesDir = toPathResource(outputDirs.getResourcesDir(), urlForArchiveFactory);

        List<PathResourceImplementation> result = new ArrayList<>(2);
        if (classesDir != null) result.add(classesDir);
        if (resourcesDir != null) result.add(resourcesDir);
        return result;
    }

    private Set<File> tryUpdateDependency(File original) {
        return openedProjectsOutput != null
                ? openedProjectsOutput.get(original.getName().toLowerCase(Locale.ROOT))
                : null;
    }

    private Collection<File> updateDependencies(Collection<File> original) {
        if (openedProjectsOutput == null) {
            return original;
        }

        boolean changed = false;
        Collection<File> result = new ArrayList<>(original.size());
        for (File file: original) {
            Set<File> translated = tryUpdateDependency(file);
            if (translated != null) {
                // Reference comparison is fine because updateDependency
                // will return the same reference if it cannot update the
                // dependency.
                changed = true;
                result.addAll(translated);
            }
            else {
                result.add(file);
            }
        }

        return changed ? result : original;
    }

    private Collection<File> getFixedCompileClasspaths(JavaSourceSet sourceSet) {
        return updateDependencies(sourceSet.getClasspaths().getCompileClasspaths());
    }

    private Collection<File> getFixedRuntimeClasspaths(JavaSourceSet sourceSet) {
        return updateDependencies(sourceSet.getClasspaths().getRuntimeClasspaths());
    }

    private void loadCompilePathResources(JavaSourceSet sourceSet, Set<File> invalid) {
        Collection<File> compileCP = getFixedCompileClasspaths(sourceSet);
        setClassPathResources(
                new SourceSetClassPathType(sourceSet.getName(), ClassPathType.COMPILE),
                getPathResources(compileCP, invalid));
    }

    private void loadRuntimePathResources(JavaSourceSet sourceSet, Set<File> invalid) {
        Collection<File> runtimeCP = getFixedRuntimeClasspaths(sourceSet);
        setClassPathResources(
                new SourceSetClassPathType(sourceSet.getName(), ClassPathType.RUNTIME),
                getPathResources(runtimeCP, invalid),
                getBuildOutputDirsAsPathResources(sourceSet));
    }

    private void loadSourcePathResources(JavaSourceSet sourceSet, Set<File> invalid) {
        Map<File, FileGroupFilter> allRoots = new LinkedHashMap<>();
        for (JavaSourceGroup sourceGroup: sourceSet.getSourceGroups()) {
            Set<File> sourceRoots = sourceGroup.getSourceRoots();
            ExcludeIncludeRules includeRules = ExcludeIncludeRules.create(sourceGroup);

            for (File root: sourceRoots) {
                FileGroupFilter prevFilter = allRoots.get(root);
                FileGroupFilter newFilter;
                if (prevFilter == null) {
                    newFilter = includeRules;
                }
                else {
                    newFilter = UnionFileGroupFilter.union(prevFilter, includeRules);
                }
                allRoots.put(root, newFilter);
            }
        }

        List<PathResourceImplementation> sourcePaths = new ArrayList<>(allRoots.size());
        addPathResources(allRoots, invalid, sourcePaths);

        setClassPathResources(
                new SourceSetClassPathType(sourceSet.getName(), ClassPathType.SOURCES),
                sourcePaths);
    }

    private void addPathResources(
            Map<File, FileGroupFilter> roots,
            Set<File> invalid,
            List<? super PathResourceImplementation> result) {

        for (Map.Entry<File, FileGroupFilter> entry: roots.entrySet()) {
            result.addAll(getPathResources(Collections.singleton(entry.getKey()), invalid, entry.getValue()));
        }
    }

    private void loadPathResources(JavaSourceSet sourceSet, Set<File> invalid) {
        loadCompilePathResources(sourceSet, invalid);
        loadRuntimePathResources(sourceSet, invalid);
        loadSourcePathResources(sourceSet, invalid);
    }

    private void loadBootClassPath() {
        Collection<URL> bootLibraries = currentPlatform.getBootLibraries();
        List<PathResourceImplementation> platformResources = new ArrayList<>(bootLibraries.size());
        for (URL url: bootLibraries) {
            platformResources.add(ClassPathSupport.createResource(url));
        }

        setClassPathResources(SpecialClassPath.BOOT, platformResources);
    }

    private void loadAllRuntimeClassPath(NbJavaModule mainModule) {
        Set<File> classPaths = new HashSet<>();

        for (JavaSourceSet sourceSet: mainModule.getSources()) {
            classPaths.add(sourceSet.getOutputDirs().getClassesDir());
            classPaths.addAll(getFixedRuntimeClasspaths(sourceSet));
        }

        setClassPathResources(
                SpecialClassPath.ALL_RUNTIME,
                getPathResources(classPaths, new HashSet<File>()));
    }

    private void loadRuntimeForGlobalClassPath(NbJavaModel projectModel) {
        Set<File> classPaths = new HashSet<>();

        for (JavaSourceSet sourceSet: projectModel.getMainModule().getSources()) {
            // There is no reason to update the classpath for the global registry.
            classPaths.addAll(sourceSet.getClasspaths().getRuntimeClasspaths());
        }

        removeOtherBuildOutputDirs(projectModel, classPaths);

        setClassPathResources(
                SpecialClassPath.RUNTIME_FOR_GLOBAL,
                getPathResources(classPaths, new HashSet<File>()));
    }

    private void loadCompileForGlobalClassPath(NbJavaModel projectModel) {
        Set<File> classPaths = new HashSet<>();

        for (JavaSourceSet sourceSet: projectModel.getMainModule().getSources()) {
            // There is no reason to update the classpath for the global registry.
            classPaths.addAll(sourceSet.getClasspaths().getCompileClasspaths());
        }

        removeOtherBuildOutputDirs(projectModel, classPaths);

        setClassPathResources(
                SpecialClassPath.COMPILE_FOR_GLOBAL,
                getPathResources(classPaths, new HashSet<File>()));
    }

    private void removeOtherBuildOutputDirs(NbJavaModel projectModel, Set<File> classPaths) {
        for (JavaProjectDependencyDef dependency: translatedDependencies.values()) {
            for (JavaSourceSet sourceSet: dependency.getJavaModule().getSources()) {
                classPaths.remove(sourceSet.getOutputDirs().getClassesDir());
            }
        }
    }

    private void loadAllBuildOutputClassPath(NbJavaModel projectModel) {
        Set<File> classPaths = new HashSet<>();

        for (JavaSourceSet sourceSet: projectModel.getMainModule().getSources()) {
            classPaths.add(sourceSet.getOutputDirs().getClassesDir());
        }

        for (JavaProjectDependencyDef dependency: translatedDependencies.values()) {
            for (JavaSourceSet sourceSet: dependency.getJavaModule().getSources()) {
                classPaths.add(sourceSet.getOutputDirs().getClassesDir());
            }
        }

        setClassPathResources(
                SpecialClassPath.ALL_BUILD_OUTPUT,
                getPathResources(classPaths, new HashSet<File>()));
    }

    private void setClassPathResources(
            ClassPathKey classPathKey,
            List<PathResourceImplementation> paths) {
        classpathResources.put(classPathKey, Collections.unmodifiableList(paths));
    }

    private void setClassPathResources(
            ClassPathKey classPathKey,
            List<PathResourceImplementation> paths1,
            List<PathResourceImplementation> paths2) {
        List<PathResourceImplementation> paths = new ArrayList<>(paths1.size() + paths2.size());
        paths.addAll(paths1);
        paths.addAll(paths2);
        setClassPathResources(classPathKey, paths);
    }


    private static PathResourceImplementation toPathResource(File file, UrlFactory urlForArchiveFactory) {
        URL url = urlForArchiveFactory.toUrl(file);
        return url != null ? ClassPathSupport.createResource(url) : null;
    }

    private static PathResourceImplementation toPathResource(
            File file,
            FileGroupFilter includeRules,
            UrlFactory urlForArchiveFactory) {
        return ExcludeAwarePathResource.tryCreate(file, includeRules, urlForArchiveFactory);
    }

    private static List<PathResourceImplementation> getPathResources(
            Collection<File> files,
            Set<File> invalid) {
        return getPathResources(files, invalid, ExcludeIncludeRules.ALLOW_ALL);
    }

    private static <T> Set<T> asSet(Collection<T> input) {
        return input instanceof Set
                ? (Set<T>)input
                : new LinkedHashSet<>(input);
    }

    public static List<PathResourceImplementation> getPathResources(
            Collection<File> files,
            Set<File> invalid,
            FileGroupFilter includeRules) {

        boolean allowAll = includeRules.isAllowAll();

        UrlFactory urlFactory = UrlFactory.getDefaultArchiveOrDirFactory();
        List<PathResourceImplementation> result = new ArrayList<>(files.size());
        for (File file: asSet(files)) {
            PathResourceImplementation pathResource = allowAll
                    ? toPathResource(file, urlFactory)
                    : toPathResource(file, includeRules, urlFactory);
            // Ignore invalid classpath entries
            if (pathResource != null) {
                result.add(pathResource);
            }
            else {
                invalid.add(file);
                LOGGER.log(Level.WARNING, "Class path entry is invalid: {0}", file);
            }
        }
        return result;
    }

    private static void throwUnbuilt() {
        throw new IllegalStateException("Call build() first before calling this method.");
    }

    public Set<File> getMissing() {
        if (missing == null) {
            throwUnbuilt();
        }
        return Collections.unmodifiableSet(missing);
    }

    public Map<ClassPathKey, List<PathResourceImplementation>> getClasspathResources() {
        if (classpathResources == null) {
            throwUnbuilt();
        }
        return Collections.unmodifiableMap(classpathResources);
    }

    public enum ClassPathType {
        SOURCES,
        COMPILE,
        RUNTIME;
    }

    // Just a marker for type safety
    public static interface ClassPathKey {
    }

    public enum SpecialClassPath implements ClassPathKey {
        BOOT,
        ALL_RUNTIME,
        ALL_BUILD_OUTPUT,
        COMPILE_FOR_GLOBAL,
        RUNTIME_FOR_GLOBAL,
    }

    public static final class SourceSetClassPathType implements ClassPathKey {
        private final String sourceSetName;
        private final ClassPathType classPathType;

        public SourceSetClassPathType(String sourceSetName, ClassPathType classPathType) {
            ExceptionHelper.checkNotNullArgument(sourceSetName, "sourceSetName");
            ExceptionHelper.checkNotNullArgument(classPathType, "classPathType");

            this.sourceSetName = sourceSetName;
            this.classPathType = classPathType;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 97 * hash + sourceSetName.hashCode();
            hash = 97 * hash + classPathType.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;

            final SourceSetClassPathType other = (SourceSetClassPathType)obj;

            return this.sourceSetName.equals(other.sourceSetName)
                    && this.classPathType == other.classPathType;
        }
    }
}
