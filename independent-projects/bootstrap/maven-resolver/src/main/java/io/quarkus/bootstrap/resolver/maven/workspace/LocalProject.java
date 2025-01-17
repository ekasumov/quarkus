package io.quarkus.bootstrap.resolver.maven.workspace;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Resource;

/**
 *
 * @author Alexey Loubyansky
 */
public class LocalProject {

    public static final String PROJECT_GROUPID = "${project.groupId}";

    private static final String PROJECT_BASEDIR = "${project.basedir}";
    private static final String PROJECT_BUILD_DIR = "${project.build.directory}";
    private static final String POM_XML = "pom.xml";

    private static class WorkspaceLoader {

        private final LocalWorkspace workspace = new LocalWorkspace();
        private final Map<Path, LocalProject> projectCache = new HashMap<>();
        private final Path currentProjectPom;
        private Path workspaceRootPom;

        private WorkspaceLoader(Path currentProjectPom) throws BootstrapMavenException {
            this.currentProjectPom = isPom(currentProjectPom) ? currentProjectPom
                    : locateCurrentProjectPom(currentProjectPom, true);
        }

        private boolean isPom(Path p) {
            if (Files.exists(p) && !Files.isDirectory(p)) {
                try {
                    loadAndCache(p);
                    return true;
                } catch (BootstrapMavenException e) {
                    // not a POM file
                }
            }
            return false;
        }

        private LocalProject project(Path pomFile) throws BootstrapMavenException {
            LocalProject project = projectCache.get(pomFile.getParent());
            if (project == null) {
                project = loadAndCache(pomFile);
            }
            return project;
        }

        private LocalProject loadAndCache(Path pomFile) throws BootstrapMavenException {
            final Model model = readModel(pomFile);
            if (workspace != null && workspace.getProject(ModelUtils.getGroupId(model), model.getArtifactId()) != null) {
                // this could happen in case of an overlapping workspace layout, see LocalWorkspaceDiscoveryTest.loadOverlappingWorkspaceLayout()
                return null;
            }
            final LocalProject project = new LocalProject(model, workspace);
            projectCache.put(pomFile.getParent(), project);
            return project;
        }

        void setWorkspaceRootPom(Path rootPom) {
            this.workspaceRootPom = rootPom;
        }

        private LocalProject loadCurrentProject(Path projectPom) throws BootstrapMavenException {
            LocalProject currentProject = null;
            LocalProject childProject = null;
            do {
                final LocalProject project = loadProjectWithModules(projectPom,
                        childProject == null ? null : childProject.getDir().relativize(projectPom.getParent()).toString());
                if (project == null) {
                    // should be an overlapping workspace layout
                    break;
                }
                if (childProject != null) {
                    project.modules.add(childProject);
                } else {
                    currentProject = project;
                }

                final Parent parent = project.getRawModel().getParent();
                if (parent != null
                        && parent.getRelativePath() != null
                        && !parent.getRelativePath().isEmpty()) {
                    projectPom = project.getDir().resolve(parent.getRelativePath()).normalize();
                    if (Files.isDirectory(projectPom)) {
                        projectPom = projectPom.resolve(POM_XML);
                    }
                } else {
                    final Path parentDir = project.getDir().getParent();
                    if (parentDir == null) {
                        break;
                    }
                    projectPom = parentDir.resolve(POM_XML);
                }
                childProject = project;
            } while (Files.exists(projectPom) && !projectCache.containsKey(projectPom.getParent()));

            return currentProject;
        }

        private LocalProject loadProjectWithModules(Path projectPom, String skipModule) throws BootstrapMavenException {
            final LocalProject project = project(projectPom);
            if (project == null) {
                return null;
            }
            final List<String> modules = project.getRawModel().getModules();
            if (!modules.isEmpty()) {
                for (String module : modules) {
                    if (module.equals(skipModule)) {
                        continue;
                    }
                    final LocalProject child = loadProjectWithModules(project.getDir().resolve(module).resolve(POM_XML), null);
                    if (child != null) {
                        project.modules.add(child);
                    }
                }
            }
            return project;
        }

        LocalProject load() throws BootstrapMavenException {
            final LocalProject currentProject = loadCurrentProject(currentProjectPom);
            if (workspace != null) {
                workspace.setCurrentProject(currentProject);
            }
            if (workspaceRootPom != null && !projectCache.containsKey(workspaceRootPom.getParent())) {
                loadCurrentProject(workspaceRootPom);
            }
            return currentProject;
        }
    }

    public static LocalProject load(Path path) throws BootstrapMavenException {
        return load(path, true);
    }

    public static LocalProject load(Path path, boolean required) throws BootstrapMavenException {
        final Path pom = locateCurrentProjectPom(path, required);
        if (pom == null) {
            return null;
        }
        try {
            return new LocalProject(readModel(pom), null);
        } catch (UnresolvedVersionException e) {
            // if a property in the version couldn't be resolved, we are trying to resolve it from the workspace
            return loadWorkspace(pom);
        }
    }

    public static LocalProject loadWorkspace(Path path) throws BootstrapMavenException {
        return loadWorkspace(path, true);
    }

    public static LocalProject loadWorkspace(Path path, boolean required) throws BootstrapMavenException {
        try {
            return new WorkspaceLoader(path.normalize().toAbsolutePath()).load();
        } catch (Exception e) {
            if (required) {
                throw e;
            }
            return null;
        }
    }

    /**
     * Loads the workspace the current project belongs to.
     * If current project does not exist then the method will return null.
     *
     * @param ctx bootstrap maven context
     * @return current project with the workspace or null in case the current project could not be resolved
     * @throws BootstrapMavenException in case of an error
     */
    public static LocalProject loadWorkspace(BootstrapMavenContext ctx) throws BootstrapMavenException {
        final Path currentProjectPom = ctx.getCurrentProjectPomOrNull();
        if (currentProjectPom == null) {
            return null;
        }
        final Path rootProjectBaseDir = ctx.getRootProjectBaseDir();
        final WorkspaceLoader wsLoader = new WorkspaceLoader(currentProjectPom);
        if (rootProjectBaseDir != null && !rootProjectBaseDir.equals(currentProjectPom.getParent())) {
            wsLoader.setWorkspaceRootPom(rootProjectBaseDir.resolve(POM_XML));
        }
        final LocalProject lp = wsLoader.load();
        lp.getWorkspace().setBootstrapMavenContext(ctx);
        return lp;
    }

    private static final Model readModel(Path pom) throws BootstrapMavenException {
        try {
            final Model model = ModelUtils.readModel(pom);
            model.setPomFile(pom.toFile());
            return model;
        } catch (IOException e) {
            throw new BootstrapMavenException("Failed to read " + pom, e);
        }
    }

    private static Path locateCurrentProjectPom(Path path, boolean required) throws BootstrapMavenException {
        Path p = path;
        while (p != null) {
            final Path pom = p.resolve(POM_XML);
            if (Files.exists(pom)) {
                return pom;
            }
            p = p.getParent();
        }
        if (required) {
            throw new BootstrapMavenException("Failed to locate project pom.xml for " + path);
        }
        return null;
    }

    private final Model rawModel;
    private final String groupId;
    private final String artifactId;
    private String version;
    private final Path dir;
    private final LocalWorkspace workspace;
    private final List<LocalProject> modules = new ArrayList<>(0);
    private AppArtifactKey key;

    private LocalProject(Model rawModel, LocalWorkspace workspace) throws BootstrapMavenException {
        this.rawModel = rawModel;
        this.dir = rawModel.getProjectDirectory().toPath();
        this.workspace = workspace;
        this.groupId = ModelUtils.getGroupId(rawModel);
        this.artifactId = rawModel.getArtifactId();

        final String rawVersion = ModelUtils.getRawVersion(rawModel);
        final boolean rawVersionIsUnresolved = ModelUtils.isUnresolvedVersion(rawVersion);
        version = rawVersionIsUnresolved ? ModelUtils.resolveVersion(rawVersion, rawModel) : rawVersion;

        if (workspace != null) {
            workspace.addProject(this, rawModel.getPomFile().lastModified());
            if (rawVersionIsUnresolved && version != null) {
                workspace.setResolvedVersion(version);
            }
        } else if (version == null && rawVersionIsUnresolved) {
            throw UnresolvedVersionException.forGa(groupId, artifactId, rawVersion);
        }
    }

    public LocalProject getLocalParent() {
        if (workspace == null) {
            return null;
        }
        final Parent parent = rawModel.getParent();
        if (parent == null) {
            return null;
        }
        return workspace.getProject(parent.getGroupId(), parent.getArtifactId());
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        if (version != null) {
            return version;
        }
        if (workspace != null) {
            version = workspace.getResolvedVersion();
        }
        if (version == null) {
            throw UnresolvedVersionException.forGa(groupId, artifactId, ModelUtils.getRawVersion(rawModel));
        }
        return version;
    }

    public Path getDir() {
        return dir;
    }

    public Path getOutputDir() {
        return resolveRelativeToBaseDir(configuredBuildDir(this, build -> build.getDirectory()), "target");
    }

    public Path getCodeGenOutputDir() {
        return getOutputDir().resolve("generated-sources");
    }

    public Path getClassesDir() {
        return resolveRelativeToBuildDir(configuredBuildDir(this, build -> build.getOutputDirectory()), "classes");
    }

    public Path getTestClassesDir() {
        return resolveRelativeToBuildDir(configuredBuildDir(this, build -> build.getTestOutputDirectory()), "test-classes");
    }

    public Path getSourcesSourcesDir() {
        return resolveRelativeToBaseDir(configuredBuildDir(this, build -> build.getSourceDirectory()), "src/main/java");
    }

    public Path getTestSourcesSourcesDir() {
        return resolveRelativeToBaseDir(configuredBuildDir(this, build -> build.getTestSourceDirectory()), "src/test/java");
    }

    public Path getSourcesDir() {
        return getSourcesSourcesDir().getParent();
    }

    public Path getResourcesSourcesDir() {
        final List<Resource> resources = rawModel.getBuild() == null ? Collections.emptyList()
                : rawModel.getBuild().getResources();
        //todo: support multiple resources dirs for config hot deployment
        final String resourcesDir = resources.isEmpty() ? null : resources.get(0).getDirectory();
        return resolveRelativeToBaseDir(resourcesDir, "src/main/resources");
    }

    public Path getTestResourcesSourcesDir() {
        final List<Resource> resources = rawModel.getBuild() == null ? Collections.emptyList()
                : rawModel.getBuild().getTestResources();
        //todo: support multiple resources dirs for config hot deployment
        final String resourcesDir = resources.isEmpty() ? null : resources.get(0).getDirectory();
        return resolveRelativeToBaseDir(resourcesDir, "src/test/resources");
    }

    public Model getRawModel() {
        return rawModel;
    }

    public LocalWorkspace getWorkspace() {
        return workspace;
    }

    public AppArtifactKey getKey() {
        return key == null ? key = new AppArtifactKey(groupId, artifactId) : key;
    }

    public AppArtifact getAppArtifact() {
        return getAppArtifact(rawModel.getPackaging());
    }

    public AppArtifact getAppArtifact(String extension) {
        return new AppArtifact(groupId, artifactId, "", extension, getVersion());
    }

    private Path resolveRelativeToBaseDir(String path, String defaultPath) {
        return dir.resolve(path == null ? defaultPath : stripProjectBasedirPrefix(path, PROJECT_BASEDIR));
    }

    private Path resolveRelativeToBuildDir(String path, String defaultPath) {
        return getOutputDir().resolve(path == null ? defaultPath : stripProjectBasedirPrefix(path, PROJECT_BUILD_DIR));
    }

    private static String stripProjectBasedirPrefix(String path, String expr) {
        return path.startsWith(expr) ? path.substring(expr.length() + 1) : path;
    }

    private static String configuredBuildDir(LocalProject project, Function<Build, String> f) {
        String dir = project.rawModel.getBuild() == null ? null : f.apply(project.rawModel.getBuild());
        while (dir == null) {
            project = project.getLocalParent();
            if (project == null) {
                break;
            }
            dir = project.rawModel.getBuild() == null ? null : f.apply(project.rawModel.getBuild());
        }
        return dir;
    }
}
