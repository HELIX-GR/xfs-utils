package gr.helix.util.xfs;

public class ProjectInfo
{
    private final Project project;

    /**
     * The space used (in Kbytes)
     */
    private Integer usedSpace;
    
    /**
     * The hard-limit for space (in Kbytes)
     */
    private Integer hardLimitForSpace;
    
    /**
     * The soft-limit for space (in Kbytes)
     */
    private Integer softLimitForSpace;
    
    /**
     * The number of inodes used
     */
    private Integer usedInodes;
    
    /**
     * The hard-limit for the number of inodes
     */
    private Integer hardLimitForInodes;
    
    /**
     * The soft-limit for the number of inodes
     */
    private Integer softLimitForInodes;
    
    public ProjectInfo(Project project)
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
            "ProjectInfo(project=%s, used=%d, hardLimit=%d, softLimit=%d)",
            project, usedSpace, hardLimitForSpace, softLimitForSpace);
    }
}
