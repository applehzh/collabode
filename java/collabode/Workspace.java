package collabode;

import java.io.ByteArrayInputStream;
import java.util.Hashtable;

import net.appjet.ajstdlib.execution;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.internal.core.IInternalDebugCoreConstants;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import collabode.testing.AnnotationsInitializer;

@SuppressWarnings("restriction")
interface Restricted {
    public static final String DEBUG_PREF_ENABLE_STATUS_HANDLERS = IInternalDebugCoreConstants.PREF_ENABLE_STATUS_HANDLERS;
}

public class Workspace {
    private static IWorkspace WORKSPACE;
    
    public static synchronized IWorkspace getWorkspace() {
        if (WORKSPACE == null) {
            InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putBoolean(ResourcesPlugin.PREF_AUTO_REFRESH, true);
            InstanceScope.INSTANCE.getNode(DebugPlugin.getUniqueIdentifier()).putBoolean(Restricted.DEBUG_PREF_ENABLE_STATUS_HANDLERS, false);
            Hashtable<String,String> options = getJavaCoreOptions();
            options.put(JavaCore.CODEASSIST_VISIBILITY_CHECK, "enabled");
            options.put(JavaCore.CODEASSIST_FORBIDDEN_REFERENCE_CHECK, "enabled");
            options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, "space");
            JavaCore.setOptions(options);
            WORKSPACE = ResourcesPlugin.getWorkspace();
        }
        return WORKSPACE;
    }
    
    @SuppressWarnings("unchecked")
    public static Hashtable<String,String> getJavaCoreOptions() {
        return JavaCore.getOptions();
    }
    
    public static IProject[] listProjects() {
        return getWorkspace().getRoot().getProjects();
    }
    
    public static IProject accessProject(String projectname) throws CoreException {
        IProject project = getWorkspace().getRoot().getProject(projectname);
        if (project.exists()) { project.open(null); }
        return project;
    }
    
    public static Preferences getProjectPrefs(IProject project, String node) {
        return new ProjectScope(project).getNode(Application.BUNDLE.getSymbolicName()).node(node);
    }
    
    public static IProject createJavaProject(String projectname) throws CoreException {
        final IProject project = getWorkspace().getRoot().getProject(projectname);
        if ( ! project.exists()) {
            getWorkspace().run(new IWorkspaceRunnable() {
                public void run(IProgressMonitor monitor) throws CoreException {
                    project.create(null);
                    project.open(null);
                    addJavaNature(project);
                    setupJavaClasspath(project);
                }
            }, null);
        }
        return project;
    }
    
    public static IProject createWebAppProject(String projectname) throws CoreException {
        return createJavaProject(projectname + "-webapp");
    }
    
    /**
     * Clone a project using on-disk files and in-memory {@link PadDocument}s
     * belonging to <code>username</code>.
     */
    public static IProject cloneProject(String username, final String projectname, String destinationname) throws CoreException {
        final IProject dest = getWorkspace().getRoot().getProject(destinationname);
        if (dest.exists()) { return dest; }
        
        IProject project = getWorkspace().getRoot().getProject(projectname);
        project.open(null);
        IProjectDescription desc = project.getDescription();
        desc.setLocation(null);
        desc.setName(destinationname);
        project.copy(desc, false, null);
        
        ResourcesPlugin.getWorkspace().run(new IWorkspaceRunnable() {
            public void run(IProgressMonitor monitor) {
                Preferences prefs = getProjectPrefs(dest, "clone");
                prefs.put("origin", projectname);
                try {
                    prefs.flush();
                } catch (BackingStoreException bse) {
                    bse.printStackTrace(); // XXX
                }
            }
        }, null);
        
        for (PadDocument doc : PadDocumentOwner.of(username).documents()) {
            if (doc.collab.file.getProject().equals(project)) {
                IFile file = (IFile)dest.findMember(doc.collab.file.getProjectRelativePath());
                // XXX duplicated from elsewhere
                file.setContents(new ByteArrayInputStream(doc.get().getBytes()), false, true, null);
            }
        }
        
        return dest;
    }
    
    public static ILaunchConfiguration accessLaunchConfig(IFile file) {
        return DebugPlugin.getDefault().getLaunchManager().getLaunchConfiguration(file);
    }
    
    private static void addJavaNature(IProject project) throws CoreException {
        IProjectDescription description = project.getDescription();
        String[] prevNatures = description.getNatureIds();
        String[] newNatures = new String[prevNatures.length + 1];
        System.arraycopy(prevNatures, 0, newNatures, 0, prevNatures.length);
        newNatures[prevNatures.length] = JavaCore.NATURE_ID;
        description.setNatureIds(newNatures);
        project.setDescription(description, null);
    }

    private static void setupJavaClasspath(IProject project) throws CoreException {
        IFolder srcFolder = project.getFolder("src");
        srcFolder.create(true, true, null);
        IFolder binFolder = project.getFolder("bin");
        if ( ! binFolder.exists()) {
            binFolder.create(true, true, null);
        }
        IJavaProject javaProject = JavaCore.create(project);
        javaProject.setOutputLocation(binFolder.getFullPath(), null);
        IClasspathEntry[] entries = new IClasspathEntry[] {
                JavaRuntime.getDefaultJREContainerEntry(),
                JavaCore.newContainerEntry(JUnitCore.JUNIT4_CONTAINER_PATH),
                JavaCore.newContainerEntry(AnnotationsInitializer.PATH),
                JavaCore.newSourceEntry(srcFolder.getFullPath())
        };
        javaProject.setRawClasspath(entries, null);
    }
    
    /**
     * Schedule a JavaScript task for execution with no delay.
     */
    public static void scheduleTask(String taskName, Object... args) {
        execution.scheduleTaskInPool("dbwriter_infreq", taskName, 0, args); // XXX maybe a different pool?
    }
}
