package gr.helix.util.xfs;

public class ProjectReport
{
    private final Project project;

    /**
     * The space used (in 1K blocks).
     * 
     * <p>Note that a zero value may indicate either that the project is not set-up or that has 
     * it has not allocated a disk block (yet).
     */
    private Integer usedSpace;
    
    /**
     * The hard-limit for space (in 1K blocks).
     * 
     * <p>A zero value may indicate either that the project is not set-up or that a limit
     * is not defined (yet).
     */
    private Integer hardLimitForSpace;
    
    /**
     * The soft-limit for space (in 1K blocks).
     * <p>See note on zeros at {@link ProjectReport#hardLimitForSpace}
     */
    private Integer softLimitForSpace;
    
    /**
     * The number of inodes used (i.e the number of files).
     * 
     * <p>A zero value indicates that the project is not set-up (otherwise at least 1 inode is 
     * used for the root directory of the project).
     */
    private Integer usedInodes;
    
    /**
     * The hard-limit for the number of inodes (i.e the number of files).
     * 
     * <p>See note on zeros at {@link ProjectReport#hardLimitForSpace}
     */
    private Integer hardLimitForInodes;
    
    /**
     * The soft-limit for the number of inodes (i.e the number of files).
     * 
     * <p>See note on zeros at {@link ProjectReport#hardLimitForSpace}
     */
    private Integer softLimitForInodes;
    
    public ProjectReport(Project project)
    {
        this.project = project;
    }
    
    public Project getProject()
    {
        return project;
    }

    public Integer getUsedSpace()
    {
        return usedSpace;
    }

    public void setUsedSpace(Integer usedSpace)
    {
        this.usedSpace = usedSpace;
    }

    public Integer getHardLimitForSpace()
    {
        return hardLimitForSpace;
    }

    public void setHardLimitForSpace(Integer hardLimitForSpace)
    {
        this.hardLimitForSpace = hardLimitForSpace;
    }

    public Integer getSoftLimitForSpace()
    {
        return softLimitForSpace;
    }

    public void setSoftLimitForSpace(Integer softLimitForSpace)
    {
        this.softLimitForSpace = softLimitForSpace;
    }

    public Integer getUsedInodes()
    {
        return usedInodes;
    }
    
    public Integer getNumberOfFiles()
    {
        return usedInodes;
    }

    public void setUsedInodes(Integer usedInodes)
    {
        this.usedInodes = usedInodes;
    }

    public Integer getHardLimitForInodes()
    {
        return hardLimitForInodes;
    }

    public void setHardLimitForInodes(Integer hardLimitForInodes)
    {
        this.hardLimitForInodes = hardLimitForInodes;
    }

    public Integer getSoftLimitForInodes()
    {
        return softLimitForInodes;
    }

    public void setSoftLimitForInodes(Integer softLimitForInodes)
    {
        this.softLimitForInodes = softLimitForInodes;
    }

    @Override
    public String toString()
    {
        return String.format(
            "ProjectReport(project=#%d, used=%dK, hardLimit=%dK, softLimit=%dK)",
            project.id(), usedSpace, hardLimitForSpace, softLimitForSpace);
    }
}
