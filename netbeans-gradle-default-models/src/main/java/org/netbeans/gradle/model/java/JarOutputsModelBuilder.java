package org.netbeans.gradle.model.java;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskContainer;
import org.netbeans.gradle.model.ProjectInfoBuilder;

public enum JarOutputsModelBuilder
implements
        ProjectInfoBuilder<JarOutputsModel> {

    INSTANCE;

    private static Task findTaskByName(Project project, String taskName) {
        TaskContainer tasks = project.getTasks();
        Task taskOfName = tasks.findByName(taskName);
        if (taskOfName != null) {
            return taskOfName;
        }

        String prefixedTaskName = ':' + taskName;
        for (Task task: tasks) {
            if (task.getPath().endsWith(prefixedTaskName)) {
                return task;
            }
        }

        return null;
    }

    private static Class<? extends Task> findJarClass(Project project) {
        Task task = findTaskByName(project, "jar");
        return task != null ? task.getClass() : null;
    }

    public JarOutputsModel getProjectInfo(Project project) {
        if (!project.getPlugins().hasPlugin("java")) {
            return null;
        }

        Class<? extends Task> jarClass = findJarClass(project);
        if (jarClass == null) {
            return new JarOutputsModel(Collections.<JarOutput>emptySet());
        }

        List<JarOutput> result = new LinkedList<JarOutput>();
        TaskCollection<? extends Task> allJars = project.getTasks().withType(jarClass);

        for (Task jar: allJars) {
            result.add(new JarOutput(jar.getName(), (File)jar.property("archivePath")));
        }

        return new JarOutputsModel(result);
    }
}