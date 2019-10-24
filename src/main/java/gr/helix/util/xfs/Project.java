package gr.helix.util.xfs;

import java.nio.file.Path;

import org.apache.commons.lang3.Validate;

/**
 * Represents a project in a XFS filesystem
 */
public class Project
{
    private static final int MAX_PROJECT_ID = 9999;
    
    private final int projectId;
    
    private final String projectName;
    
    private final Path path;
    
    private final Path mountpoint;

    private Project(int projectId, String projectName, Path path, Path mountpoint)
    {        
        this.projectId = projectId;
        this.projectName = projectName;
        this.path = path;
        this.mountpoint = mountpoint;
    }
    
    /**
     * Create an instance of a {@link Project}
     * 
     * @param projectId The project id (an integer between 1 and {@linkplain Project#MAX_PROJECT_ID})
     * @param projectName The project name
     * @param path An absolute path to the root directory of the project (must be inside XFS mount-point)
     * @param mountpoint An absolute path for the mountpoint of the XFS filesystem
     */
    public static Project of(int projectId, String projectName, Path path, Path mountpoint)
    {
        Validate.inclusiveBetween(1, MAX_PROJECT_ID, projectId);
        Validate.notEmpty(projectName, "Expected a non-empty project name");
        Validate.notNull(path, "Expected a path for the root directory of the project");
        Validate.isTrue(path.isAbsolute(), "The root directory of the project must given as an absolute path");
        Validate.notNull(mountpoint, "Expected a path for the mountpoint of the XFS filesystem");
        Validate.isTrue(mountpoint.isAbsolute(), "The mountpoint must given as an absolute path");
        Validate.isTrue(path.startsWith(mountpoint), "The root directory of the project must be inside the XFS filesystem");
        
        return new Project(projectId, projectName, path, mountpoint);
    }
    
    public int projectId()
    {
        return projectId;
    }
    
    public String projectName()
    {
        return projectName;
    }
    
    public Path path()
    {
        return path;
    }
    
    public Path mountpoint()
    {
        return mountpoint;
    }

    @Override
    public String toString()
    {
        return String.format("Project(projectId=%s, projectName=%s, path=%s, mountpoint=%s)",
            projectId, projectName, path, mountpoint);
    }
}
