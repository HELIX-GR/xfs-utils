package gr.helix.util.xfs;

import org.apache.commons.lang3.Validate;

public class ProjectReport
{
    private final Project project;

    /**
     * The space used (in 1K blocks).
     * 
     * <p>Note that a zero value may indicate either that the project is not set-up or that has 
     * it has not allocated a disk block (yet).
     */
    Integer usedBlocks;
    
    /**
     * The hard-limit for space (in 1K blocks).
     * 
     * <p>A zero value may indicate either that the project is not set-up or that a limit
     * is not defined (yet).
     */
    Integer hardLimitForBlocks;
    
    /**
     * The soft-limit for space (in 1K blocks).
     * <p>See note on zeros at {@link ProjectReport#hardLimitForBlocks}
     */
    Integer softLimitForBlocks;
    
    /**
     * The number of inodes used (i.e the number of files).
     * 
     * <p>A zero value indicates that the project is not set-up (otherwise at least 1 inode is 
     * used for the root directory of the project).
     */
    Integer usedInodes;
    
    /**
     * The hard-limit for the number of inodes (i.e the number of files).
     * 
     * <p>See note on zeros at {@link ProjectReport#hardLimitForBlocks}
     */
    Integer hardLimitForInodes;
    
    /**
     * The soft-limit for the number of inodes (i.e the number of files).
     * 
     * <p>See note on zeros at {@link ProjectReport#hardLimitForBlocks}
     */
    Integer softLimitForInodes;
    
    /**
     * A package-private constructor
     */
    ProjectReport(Project project)
    {
        Validate.notNull(project);
        this.project = project;
    }
    
    public Project getProject()
    {
        return project;
    }

    public Integer getUsedBlocks()
    {
        return usedBlocks;
    }

    public Long getUsedBytes()
    {
        return usedBlocks == null? null : (usedBlocks.longValue() * 1024L);
    }
    
    public Integer getHardLimitForBlocks()
    {
        return hardLimitForBlocks;
    }
    
    public Long getHardLimitForBytes()
    {
        return hardLimitForBlocks == null? null : (hardLimitForBlocks.longValue() * 1024L);
    }

    public Integer getSoftLimitForBlocks()
    {
        return softLimitForBlocks;
    }
    
    public Long getSoftLimitForBytes()
    {
        return softLimitForBlocks == null? null : (softLimitForBlocks.longValue() * 1024L);
    }

    public Integer getUsedInodes()
    {
        return usedInodes;
    }
    
    public Integer getNumberOfFiles()
    {
        return usedInodes;
    }

    public Integer getHardLimitForInodes()
    {
        return hardLimitForInodes;
    }

    public Integer getSoftLimitForInodes()
    {
        return softLimitForInodes;
    }

    @Override
    public String toString()
    {
        return String.format(
            "ProjectReport (project=#%d, " +
                "usedBlocks=%s, hardLimitForBlocks=%s, softLimitForBlocks=%s, " +
                "usedInodes=%s, hardLimitForInodes=%s, softLimitForInodes=%s)",
            project.id(), 
            usedBlocks, hardLimitForBlocks, softLimitForBlocks, 
            usedInodes, hardLimitForInodes, softLimitForInodes);
    }
}
