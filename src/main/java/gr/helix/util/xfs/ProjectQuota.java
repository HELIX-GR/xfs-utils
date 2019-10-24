package gr.helix.util.xfs;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProjectQuota
{
    private static final Logger logger = LoggerFactory.getLogger(ProjectQuota.class);
    
    private static final long COMMAND_TIMEOUT_IN_SECONDS = 2; 
    
    /**
     * List known (not necessarily set-up) projects under a given XFS filesystem
     * 
     * @param mountpoint The mountpoint of the XFS filesystem
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static Map<Integer, Project> listProjects(Path mountpoint) throws IOException, InterruptedException
    {
        final String listingAsString = executeXfsQuotaCommand("print", mountpoint);
        
        final Pattern projectIdAndNamePattern = Pattern.compile("^[(]project\\s+(\\d+),\\s*(\\w+)[)]$");
        
        final Function<String, Project> parseLineToProject = (String line) -> {
            String[] parts = line.split("\\s+", 3);
            Path path = Paths.get(parts[0]);
            Matcher projectIdMatcher = projectIdAndNamePattern.matcher(parts[2]);
            if (!projectIdMatcher.matches())
                return (Project) null;
            Integer projectId = Integer.parseInt(projectIdMatcher.group(1));
            String projectName = projectIdMatcher.group(2);
            return Project.of(projectId, projectName, path, mountpoint);
        };
        
        return Arrays.stream(listingAsString.split("\\R"))
            .skip(1) // skip header line
            .map(String::trim)
            .map(parseLineToProject)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(Project::projectId, Function.identity()));
    }
    
    public static Map<Integer, ProjectInfo> reportProjects(Path mountpoint) throws IOException, InterruptedException
    {
        final Map<Integer, Project> knownProjects = listProjects(mountpoint);
        
        final String reportAsString = executeXfsQuotaCommand("report -pnN", mountpoint);
        
        final Pattern projectIdPattern = Pattern.compile("^[#](\\d+)$");
        
        final Function<String, ProjectInfo> parseLineToProjectInfo = (String line) -> {
            String[] parts =  line.split("\\s+", 5);
            Matcher projectIdMatcher = projectIdPattern.matcher(parts[0]);
            if (!projectIdMatcher.matches())
                return null;
            Integer projectId = Integer.parseInt(projectIdMatcher.group(1));;
            Project project = knownProjects.get(projectId);
            Validate.validState(project != null, "This project ID is unknown: %s", projectId);
            ProjectInfo projectInfo = new ProjectInfo(project);
            projectInfo.setUsedSpace(Integer.parseInt(parts[1]));
            projectInfo.setSoftLimitForSpace( Integer.parseInt(parts[2]));
            projectInfo.setHardLimitForSpace(Integer.parseInt(parts[3]));
            return projectInfo;
        };
        
        return Arrays.stream(reportAsString.split("\\R"))
            .map(String::trim)
            .map(parseLineToProjectInfo)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(projinfo -> projinfo.getProject().projectId(), Function.identity()));
    }
    
    /**
     * Setup the project: enable accounting and quota enforcement. If the project is unknown, it
     * registers under <code>/etc/projects</code>. If the project is known and already set-up, 
     * nothing happens.
     * 
     * <p>Note: This method does not create the project's directory (it must already exist).
     * 
     * @param project The project descriptor
     */
    public static void setupProject(Project project)
    {
        // Todo
    }
    
    /**
     * Cleanup the project: stop accounting and quota enforcement.
     * 
     * <p>Note: This method does not delete the project's directory, it simply stops monitoring it.
     * 
     * @param project The project descriptor
     */
    public static void cleanupProject(Project project)
    {
        // Todo
    }
    
    /**
     * Set space quota for a project
     * 
     * @param project The project descriptor
     * @param softLimit The soft limit in Kbytes (1K blocks)
     * @param hardLimit The hard limit in Kbytes (1K blocks)
     */
    public static void setQuotaForSpace(Project project, int softLimit, int hardLimit)
    {
        // Todo
    }
    
    /**
     * Set inode quota for a project
     * 
     * @param project The project descriptor
     * @param softLimit The soft limit in Kbytes (1K blocks)
     * @param hardLimit The hard limit in Kbytes (1K blocks)
     */
    public static void setQuotaForInodes(Project project, int softLimit, int hardLimit)
    {
        // Todo
    }
    
    /**
     * Execute an <code>xfs_quota</code> command forking a child process and return its output as
     * a string.
     * 
     * @param quotaCommand
     * @param mountpoint
     * @return the standard output of the command
     * @throws InterruptedException
     * @throws IOException
     * 
     * @see manpage for <code>xfs_quota</code>
     */
    private static String executeXfsQuotaCommand(String quotaCommand, Path mountpoint) 
        throws InterruptedException, IOException
    {
        final List<String> command = 
            Arrays.asList("sudo", "xfs_quota", "-x", "-c", quotaCommand, mountpoint.toString());
        final ProcessBuilder processBuilder = new ProcessBuilder(command)
            .redirectErrorStream(false);
        
        final Process process = processBuilder.start();
        logger.debug("Spawned process executing `xfs_quota -x -c '{}' {}`: {}", 
            quotaCommand, mountpoint, process);
        
        final boolean finished = process.waitFor(COMMAND_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            throw new IllegalStateException(
                String.format("Timed out (%ds) waiting for `xfs_quota` command", COMMAND_TIMEOUT_IN_SECONDS));
        }
        
        final int exitCode = process.exitValue();
        logger.debug("Process {} exited with code {}", process, exitCode);
        if (exitCode != 0) {
            throw new IllegalStateException(
                String.format("The `xfs_quota` command has failed: %s", quotaCommand));
        }
        
        final String stdoutAsString = 
            IOUtils.toString(process.getInputStream(), Charset.defaultCharset());
        
        return stdoutAsString;
    }
    
    //
    // Tests
    //
    
    public static void main(String[] args) throws IOException, InterruptedException
    {
        final Path mountpoint = Paths.get("/var/local");
        
        System.err.println(" -- Projects -- ");
        for (Map.Entry<Integer, Project> e: listProjects(mountpoint).entrySet()) {
            System.err.println(e.getKey() + " -> " + e.getValue());
        }
        
        System.err.println(" -- Project Info -- ");
        for (Map.Entry<Integer, ProjectInfo> e: reportProjects(mountpoint).entrySet()) {
            System.err.println(e.getKey() + " -> " + e.getValue());
        }
        
    }
}
