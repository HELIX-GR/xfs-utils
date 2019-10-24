package gr.helix.util.xfs;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProjectQuota
{
    private static final Logger logger = LoggerFactory.getLogger(ProjectQuota.class);
    
    private static final long COMMAND_TIMEOUT_IN_SECONDS = 2; 
    
    /**
     * Parse a line of output of <tt>report</tt> subcommand.
     */
    private static class ReportLineParser implements Function<String, ProjectInfo>
    {
        private static final Pattern projectIdPattern = Pattern.compile("^[#](\\d+)$");
        
        private final Map<Integer, Project> knownProjects;
        
        public ReportLineParser(Map<Integer, Project> projects)
        {
            this.knownProjects = projects;
        }
        
        @Override
        public ProjectInfo apply(String line)
        {
            String[] parts =  line.split("\\s+", 5);
            if (parts.length != 5)
                return null;
            
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
        }
    }
    
    /**
     * Parse a line of output of <tt>print</tt> subcommand.
     */
    private static class PrintLineParser implements Function<String, Project>
    {
        private static final Pattern projectIdAndNamePattern = Pattern.compile("^[(]project\\s+(\\d+),\\s*(\\w+)[)]$");

        private final Path mountpoint;
        
        public PrintLineParser(Path mountpoint)
        {
            this.mountpoint = mountpoint;
        }

        @Override
        public Project apply(String line)
        {
            String[] parts = line.split("\\s+", 3);
            if (parts.length != 3)
                return null;
            
            Path path = Paths.get(parts[0]);
            Matcher projectIdMatcher = projectIdAndNamePattern.matcher(parts[2]);
            if (!projectIdMatcher.matches())
                return null;
            
            Integer projectId = Integer.parseInt(projectIdMatcher.group(1));
            String projectName = projectIdMatcher.group(2);
            return Project.of(projectId, projectName, path, mountpoint);
        }
    }

    /**
     * Parse a line of output of <tt>quota</tt> subcommand.
     */
    private static class QuotaLineParser implements Function<String, ProjectInfo>
    {
        private final Project project;

        public QuotaLineParser(Project project)
        {
            this.project = project;
        }

        @Override
        public ProjectInfo apply(String line)
        {
            if (line.isEmpty())
                return null;
            
            String[] parts = line.split("\\s+", 5);
            if (parts.length != 5)
                return null;
            
            ProjectInfo projectInfo = new ProjectInfo(project);
            projectInfo.setUsedSpace(Integer.parseInt(parts[1]));
            projectInfo.setSoftLimitForSpace( Integer.parseInt(parts[2]));
            projectInfo.setHardLimitForSpace(Integer.parseInt(parts[3]));
            return projectInfo;
        }
    }
    
    private static String commandToString(List<String> command)
    {
        String commandLine = command.stream()
            .map(s -> s.indexOf(' ') < 0? s: ('"' + s + '"')) // enclose white-space in quotes
            .collect(Collectors.joining(" "));
        return commandLine;
    }
        
    /**
     * List known (not necessarily set-up) projects under a given XFS filesystem
     * 
     * @param mountpoint The mountpoint of the XFS filesystem
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static Map<Integer, Project> listProjects(Path mountpoint) 
        throws IOException, InterruptedException
    {
        final String listingAsString = executeXfsQuotaCommand("print", null, mountpoint);
        
        return Arrays.stream(listingAsString.split("\\R"))
            .skip(1) // skip header
            .map(String::trim)
            .map(new PrintLineParser(mountpoint))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(Project::projectId, Function.identity()));
    }
    
    public static Map<Integer, ProjectInfo> getReport(Path mountpoint) 
        throws IOException, InterruptedException
    {
        final Map<Integer, Project> knownProjects = listProjects(mountpoint);
        
        final String reportAsString = executeXfsQuotaCommand("report -pnN", null, mountpoint);
        
        return Arrays.stream(reportAsString.split("\\R"))
            .map(String::trim)
            .map(new ReportLineParser(knownProjects))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(projinfo -> projinfo.getProject().projectId(), Function.identity()));
    }
    
    private static Optional<Project> getProjectByNameOrId(String projectIdentifier, Path mountpoint) 
        throws InterruptedException, IOException
    {
        final String listingAsString = executeXfsQuotaCommand("print", projectIdentifier, mountpoint);
        
        return Arrays.stream(listingAsString.split("\\R"))
            .skip(1) // skip header
            .map(new PrintLineParser(mountpoint))
            .filter(Objects::nonNull)
            .findFirst();
    }
    
    public static Optional<Project> getProjectById(int projectId, Path mountpoint) 
        throws InterruptedException, IOException
    {
        return getProjectByNameOrId(String.valueOf(projectId), mountpoint);
    }
    
    public static Optional<Project> getProjectByName(String projectName, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Validate.notBlank(projectName);
        return getProjectByNameOrId(projectName, mountpoint);
    }
    
    private static Optional<ProjectInfo> getReportForProject1(String projectIdentifier, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Project project = getProjectByNameOrId(projectIdentifier, mountpoint).orElse(null);
        return project == null? Optional.empty() : getReportForProject1(project, mountpoint);
    }
    
    private static Optional<ProjectInfo> getReportForProject1(Project project, Path mountpoint) 
        throws InterruptedException, IOException
    {
        final String quotaAsString = executeXfsQuotaCommand(
            String.format("quota -N -p %d", project.projectId()), null, mountpoint);
        
        return Optional.ofNullable((new QuotaLineParser(project)).apply(quotaAsString));
    }
    
    public static Optional<ProjectInfo> getReportForProject(int projectId, Path mountpoint) 
        throws InterruptedException, IOException
    {
        return getReportForProject1(String.valueOf(projectId), mountpoint);
    }
    
    public static Optional<ProjectInfo> getReportForProject(String projectName, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Validate.notBlank(projectName);
        return getReportForProject1(projectName, mountpoint);
    }
    
    public static Optional<ProjectInfo> getReportForProject(Project project, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Validate.notNull(project);
        return getReportForProject1(project, mountpoint);
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
     * @param quotaCommand An <tt>xfs_quota</tt> command
     * @param projectIdentifier A project identifier (numeric ID or name) to restrict the scope of the command. 
     *    It may be <code>null</code>.
     * @param mountpoint The mountpoint of the XFS filesystem
     * @return the standard output of the command
     * @throws InterruptedException
     * @throws IOException
     * 
     * @see manpage for <code>xfs_quota</code>
     */
    private static String executeXfsQuotaCommand(String quotaCommand, String projectIdentifier, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Process process = null;
        
        final List<String> command = 
            new ArrayList<>(Arrays.asList("sudo", "xfs_quota", "-x", "-c", quotaCommand));
        if (projectIdentifier != null) {
            command.add("-d");
            command.add(projectIdentifier.toString());
        }
        command.add(mountpoint.toString());
            
        final ProcessBuilder processBuilder = new ProcessBuilder(command)
            .redirectErrorStream(false);
        
        process = processBuilder.start();
        if (logger.isDebugEnabled()) {
            logger.debug("Spawned process executing `{}`: {}", commandToString(command), process);
        }
        
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
        
        final String stdoutAsString = IOUtils.toString(process.getInputStream(), Charset.defaultCharset());
        
        return stdoutAsString;
    }
    
    //
    // Tests
    //
    
    public static void main(String[] args) throws IOException, InterruptedException
    {
        final Path mountpoint = Paths.get("/var/local");
        
        Optional<Project> p1 = getProjectByName(args[0], mountpoint);
        System.err.println(p1);
        if (p1.isPresent()) {
            Optional<ProjectInfo> info1 = getReportForProject(p1.get(), mountpoint);
            System.err.println(info1);
        }
        
        
//        System.err.println(" -- Projects -- ");
//        for (Map.Entry<Integer, Project> e: listProjects(mountpoint).entrySet()) {
//            System.err.println(e.getKey() + " -> " + e.getValue());
//        }
//        
//        System.err.println(" -- Project Info -- ");
//        for (Map.Entry<Integer, ProjectInfo> e: getReport(mountpoint).entrySet()) {
//            System.err.println(e.getKey() + " -> " + e.getValue());
//        }
        
    }
}
