package gr.helix.util.xfs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProjectQuota
{
    private static final Logger logger = LoggerFactory.getLogger(ProjectQuota.class);
    
    private static final Path PROJECTS_FILE = Paths.get("/etc/projects");
    
    private static final Path PROJID_FILE = Paths.get("/etc/projid");
    
    private static final Path PROJECTS_FILE_LOCK_FILE = Paths.get("/tmp/projects.lock");
    
    private static final long PROJECTS_FILE_LOCK_RETRY_INTERVAL = 250L;
    
    private static final long PROJECTS_FILE_LOCK_RETRY_MAX_COUNT = 5;
    
    private static final FileAttribute<?> PROJECTS_FILE_LOCK_PERMISSION = PosixFilePermissions
        .asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    
    private static final String PROJECTS_LINE_FORMAT = "%1$d:%2$s%n";
    
    private static final String PROJID_LINE_FORMAT = "%2$s:%1$d%n";
    
    private static final long COMMAND_TIMEOUT_IN_SECONDS = 2; 
    
    @FunctionalInterface
    private interface DefinitionEditor
    {
        void editDefinitionForProject(Project project) throws IOException, InterruptedException;
    }
    
    /**
     * Parse a line of output of <tt>report</tt> subcommand.
     */
    private static class ReportLineParser implements Function<String, ProjectReport>
    {
        private static final Pattern projectIdPattern = Pattern.compile("^[#](\\d+)$");
        
        private final Map<Integer, Project> knownProjects;
        
        public ReportLineParser(Map<Integer, Project> projects)
        {
            this.knownProjects = projects;
        }
        
        @Override
        public ProjectReport apply(String line)
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
            
            ProjectReport projectReport = new ProjectReport(project);
            projectReport.setUsedSpace(Integer.parseInt(parts[1]));
            projectReport.setSoftLimitForSpace(Integer.parseInt(parts[2]));
            projectReport.setHardLimitForSpace(Integer.parseInt(parts[3]));
            return projectReport;
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
     * Parse a line of output of <tt>quota -b -v -p PROJID</tt> subcommand.
     */
    private static class BlockQuotaLineParser implements Function<String, ProjectReport>
    {
        private final Project project;

        public BlockQuotaLineParser(Project project)
        {
            this.project = project;
        }

        @Override
        public ProjectReport apply(String line)
        {
            if (line.isEmpty())
                return null;
            
            String[] parts = line.split("\\s+", 5);
            if (parts.length != 5)
                return null;
            
            ProjectReport projectReport = new ProjectReport(project);
            projectReport.setUsedSpace(Integer.parseInt(parts[1]));
            projectReport.setSoftLimitForSpace( Integer.parseInt(parts[2]));
            projectReport.setHardLimitForSpace(Integer.parseInt(parts[3]));
            return projectReport;
        }
    }
    
    private static String commandToString(List<String> command)
    {
        String commandLine = command.stream()
            .map(s -> s.indexOf(' ') < 0? s: ('"' + s + '"')) // enclose white-space in quotes
            .collect(Collectors.joining(" "));
        return commandLine;
    }
    
    private static Map<Integer, Path> loadProjectPaths() throws IOException
    {
        return Files.lines(PROJECTS_FILE)
            .map(line -> line.split(":"))
            .filter(fields -> fields.length == 2)
            .map(fields -> Pair.of(Integer.valueOf(fields[0]), Paths.get(fields[1])))
            .collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (u, v) -> u, LinkedHashMap::new));
    }
    
    private static Map<Integer, String> loadProjectNames() throws IOException
    {
        return Files.lines(PROJID_FILE)
            .map(line -> line.split(":"))
            .filter(fields -> fields.length == 2)
            .map(fields -> Pair.of(Integer.valueOf(fields[1]), fields[0]))
            .collect(Collectors.toMap(Pair::getLeft, Pair::getRight, (u, v) -> u, LinkedHashMap::new));
    }
    
    private static void editDefinition(DefinitionEditor editor, Project project) 
        throws IOException, InterruptedException
    {
        // Lock before modifying files
        
        Path lock1 = null;
        int retryCount = 0;
        while (lock1 == null) {
            try {
                lock1 = Files.createFile(PROJECTS_FILE_LOCK_FILE, PROJECTS_FILE_LOCK_PERMISSION);
            } catch (FileAlreadyExistsException ex) {
                if (++retryCount <= PROJECTS_FILE_LOCK_RETRY_MAX_COUNT) {
                    Thread.sleep(PROJECTS_FILE_LOCK_RETRY_INTERVAL);
                } else {
                    throw ex;
                }
            }
        }
        
        // Edit project definition at /etc/{projid,projects}
        
        try {
            editor.editDefinitionForProject(project);
        } finally {
            Files.delete(PROJECTS_FILE_LOCK_FILE);
        }
    }
    
    private static void registerProject(Project project) 
        throws IOException, InterruptedException
    {
        logger.info("Adding definition for project {} under {}", project, PROJECTS_FILE);
        
        // Append a single definition to /etc/{projid,projects}
        
        final Map<Integer, Path> projectPathById = loadProjectPaths();
        final Map<Integer, String> projectNameById = loadProjectNames();
        if (!projectPathById.keySet().equals(projectNameById.keySet())) {
            logger.warn("The project definition files are expected to hold the same set of project IDs!");
        }
        
        if (!projectNameById.containsKey(project.id())) {
            try (BufferedWriter writer = Files.newBufferedWriter(PROJECTS_FILE, StandardOpenOption.APPEND)) {
                writer.write(String.format(PROJECTS_LINE_FORMAT, project.id(), project.path()));
            }
            try (BufferedWriter writer = Files.newBufferedWriter(PROJID_FILE, StandardOpenOption.APPEND)) {
                writer.write(String.format(PROJID_LINE_FORMAT, project.id(), project.name()));
            }
        } else {
            logger.info("The project #{} is already defined under {}", project.id(), PROJECTS_FILE);
            if (!projectNameById.get(project.id()).equals(project.name())) {
                logger.warn("A project cannot be renamed: project #{} is named as {} (!= {})",
                    project.id(), projectNameById.get(project.id()), project.name());
            }
            if (!projectPathById.get(project.id()).equals(project.path())) {
                logger.warn("A project\'s path cannot be reassigned: project #{} mapped to {} (!= {})",
                    project.id(), projectPathById.get(project.id()), project.path());
            }
        }
    }
    
    private static void deregisterProject(Project project)
        throws IOException, InterruptedException
    {
        logger.info("Removing definition of project {} from {}", project, PROJECTS_FILE);
        
        // Regenerate all definitions except of the one of the de-registered project
        
        final Map<Integer, Path> projectPathById = loadProjectPaths();
        final Map<Integer, String> projectNameById = loadProjectNames();
        if (!projectPathById.keySet().equals(projectNameById.keySet())) {
            logger.warn("The project definition files are expected to hold the same set of project IDs!");
        }
        
        if (projectNameById.containsKey(project.id())) {
            try (BufferedWriter writer = Files.newBufferedWriter(PROJECTS_FILE)) {
                for (Map.Entry<Integer, Path> p: projectPathById.entrySet()) {
                    if (!p.getKey().equals(project.id())) 
                        writer.write(String.format(PROJECTS_LINE_FORMAT, p.getKey(), p.getValue()));
                }
            }
            try (BufferedWriter writer = Files.newBufferedWriter(PROJID_FILE)) {
                for (Map.Entry<Integer, String> p: projectNameById.entrySet()) {
                    if (!p.getKey().equals(project.id()))
                        writer.write(String.format(PROJID_LINE_FORMAT, p.getKey(), p.getValue()));
                }
            }
        } else {
            logger.info("The project #{} cannot be de-registered because it does not exist under {}", 
                project.id(), PROJECTS_FILE);
        }
        
        return;
    }
    
    private static Optional<Project> findProjectByNameOrId(String projectIdentifier, Path mountpoint) 
        throws InterruptedException, IOException
    {
        final String listingAsString = executeQuotaCommand("print", projectIdentifier, mountpoint);
        
        return Arrays.stream(listingAsString.split("\\R"))
            .skip(1) // skip header
            .map(new PrintLineParser(mountpoint))
            .filter(Objects::nonNull)
            .findFirst();
    }
    
    private static Optional<ProjectReport> getReportForProject1(String projectIdentifier, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Project project = findProjectByNameOrId(projectIdentifier, mountpoint).orElse(null);
        return project == null? Optional.empty() : getReportForProject1(project);
    }
    
    private static Optional<ProjectReport> getReportForProject1(Project project) 
        throws InterruptedException, IOException
    {
        // Get the quota report for blocks
        final String quotaAsString = executeQuotaCommand(
            String.format("quota -b -v -N -p %d", project.id()), null, project.mountpoint());
        
        return Optional.ofNullable((new BlockQuotaLineParser(project)).apply(quotaAsString));
    }
    
    private static void setupProject1(Project project)
        throws InterruptedException, IOException
    {
        executeQuotaCommand(
            String.format("project -s %d", project.id()), 
            null, project.mountpoint());
    }
    
    private static void cleanupProject1(Project project)
        throws InterruptedException, IOException
    {
        executeQuotaCommand(
            String.format("project -C %d", project.id()), 
            null, project.mountpoint());
    }
    
    private static void setQuotaForSpace1(Project project, int softLimit, int hardLimit) 
        throws InterruptedException, IOException
    {
        executeQuotaCommand(
            String.format("limit -p bsoft=%dK bhard=%dK %d", softLimit, hardLimit, project.id()), 
            null, project.mountpoint());
    }
    
    private static void setQuotaForInodes1(Project project, int softLimit, int hardLimit) 
        throws InterruptedException, IOException
    {
        executeQuotaCommand(
            String.format("limit -p isoft=%d ihard=%d %d", softLimit, hardLimit, project.id()), 
            null, project.mountpoint());
    }
    
    private static void checkProjectBeforeApplyingQuota(Project project)
        throws InterruptedException, IOException
    {
        final Path path = project.path();
        Validate.validState(Files.isDirectory(path), 
            "The project\'s root directory does not exist: %s", path);
        
        Validate.validState(getReportForProject1(project).isPresent(), 
            "The project %s is not setup! must set it up first before applying quota", project);
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
        final String listingAsString = executeQuotaCommand("print", null, mountpoint);
        
        return Arrays.stream(listingAsString.split("\\R"))
            .skip(1) // skip header
            .map(String::trim)
            .map(new PrintLineParser(mountpoint))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(Project::id, Function.identity()));
    }
    
    public static Map<Integer, ProjectReport> getReport(Path mountpoint) 
        throws IOException, InterruptedException
    {
        final Map<Integer, Project> knownProjects = listProjects(mountpoint);
        
        final String reportAsString = executeQuotaCommand("report -pnN", null, mountpoint);
        
        return Arrays.stream(reportAsString.split("\\R"))
            .map(String::trim)
            .map(new ReportLineParser(knownProjects))
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(projinfo -> projinfo.getProject().id(), Function.identity()));
    }
    
    public static Optional<Project> findProjectById(int projectId, Path mountpoint) 
        throws InterruptedException, IOException
    {
        return findProjectByNameOrId(String.valueOf(projectId), mountpoint);
    }
    
    public static Optional<Project> findProjectByName(String projectName, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Validate.notBlank(projectName);
        return findProjectByNameOrId(projectName, mountpoint);
    }
        
    public static Optional<ProjectReport> getReportForProject(int projectId, Path mountpoint) 
        throws InterruptedException, IOException
    {
        return getReportForProject1(String.valueOf(projectId), mountpoint);
    }
    
    public static Optional<ProjectReport> getReportForProject(String projectName, Path mountpoint) 
        throws InterruptedException, IOException
    {
        Validate.notBlank(projectName);
        return getReportForProject1(projectName, mountpoint);
    }
    
    public static Optional<ProjectReport> getReportForProject(Project project) 
        throws InterruptedException, IOException
    {
        Validate.notNull(project);
        return getReportForProject1(project);
    }
    
    /**
     * Setup the project: enable accounting and quota enforcement. If the project is unknown, it
     * registers under <code>/etc/projects</code>. If the project is known and already set-up, 
     * nothing happens.
     * 
     * <p>Note: This method does not create the project's directory (it must already exist).
     * 
     * @param projectToSetup The project descriptor
     */
    public static void setupProject(Project projectToSetup)
        throws InterruptedException, IOException
    {
        Validate.notNull(projectToSetup);
        
        final Path mountpoint = projectToSetup.mountpoint();
        Validate.validState(Files.isDirectory(mountpoint), "The mountpoint is not a directory!");
        
        final Path path = projectToSetup.path();
        Validate.validState(Files.isDirectory(path), 
            "The project\'s root directory does not exist: %s", path);
        
        // Register
        
        Project project = findProjectById(projectToSetup.id(), mountpoint).orElse(null);
        if (project == null) {
            // The project is not registered under /etc/projects
            editDefinition(ProjectQuota::registerProject, projectToSetup);
            project = findProjectById(projectToSetup.id(), mountpoint).get();
            
        }
        
        // Setup
        // Note: We consider a project as set-up if it outputs a quota report of non-zero usage
        
        ProjectReport projectReport = getReportForProject1(project).orElse(null);
        Validate.validState(projectReport == null || projectReport.getUsedSpace() != null, 
            "The report is expected to have a non-null value for usedSpace");
        if (projectReport == null || projectReport.getUsedSpace() == 0) {
            setupProject1(project);
        }
    }
    
    public static void setupProjects(Collection<Project> projectsToSetup)
    {
        // Todo
    }
    
    /**
     * Cleanup the project: stop accounting and quota enforcement, remove definitions under 
     * <code>/etc/projects</code>.
     * 
     * <p>Note: This method does not delete the project's directory, it simply stops monitoring it.
     * 
     * @param project The project descriptor
     * @throws IOException 
     * @throws InterruptedException 
     */
    public static void cleanupProject(Project projectToCleanup) 
        throws InterruptedException, IOException
    {
        Validate.notNull(projectToCleanup);
        
        final Path mountpoint = projectToCleanup.mountpoint();
        Validate.validState(Files.isDirectory(mountpoint), "The mountpoint is not a directory!");
        
        // Cleanup
        
        if (Files.isDirectory(projectToCleanup.path()) && getReportForProject1(projectToCleanup).isPresent()) {
            cleanupProject1(projectToCleanup);
        }
        
        // De-register
        
        if (findProjectById(projectToCleanup.id(), mountpoint).isPresent()) {
            // Remove relevant definitions from /etc/{projects,projid}
            editDefinition(ProjectQuota::deregisterProject, projectToCleanup);
        }
    }
    
    public static void cleanupProjects(Collection<Project> projectsToSetup)
    {
        // Todo
    }
    
    /**
     * Set space quota for a project
     * 
     * @param project The project descriptor
     * @param softLimit The soft limit in Kbytes (1K blocks). A zero value means no limit.
     * @param hardLimit The hard limit in Kbytes (1K blocks). A zero value means no limit.
     */
    public static void setQuotaForSpace(Project project, int softLimit, int hardLimit)
        throws InterruptedException, IOException
    {
        Validate.notNull(project);
        
        Validate.isTrue(softLimit <= hardLimit, 
            "The soft limit (=%d Kbytes) must be lower than or equal to the hard limit (=%d Kbytes)",
            softLimit, hardLimit);
        
        checkProjectBeforeApplyingQuota(project);
        setQuotaForSpace1(project, softLimit, hardLimit);
    }
    
    /**
     * Set inode quota (number of inodes) for a project
     * 
     * @param project The project descriptor
     * @param softLimit The soft limit in Kbytes (1K blocks). A zero value means no limit.
     * @param hardLimit The hard limit in Kbytes (1K blocks). A zero value means no limit.
     */
    public static void setQuotaForInodes(Project project, int softLimit, int hardLimit)
        throws InterruptedException, IOException
    {
        Validate.notNull(project);
        
        Validate.isTrue(softLimit <= hardLimit, 
            "The soft limit (=%d) must be lower than or equal to the hard limit (=%d)",
            softLimit, hardLimit);
        
        checkProjectBeforeApplyingQuota(project);
        setQuotaForInodes1(project, softLimit, hardLimit);
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
    private static String executeQuotaCommand(String quotaCommand, String projectIdentifier, Path mountpoint) 
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
        
        Optional<Project> p1 = findProjectByName(args[0], mountpoint);
        System.err.println(p1);
        if (p1.isPresent()) {
            Optional<ProjectReport> info1 = getReportForProject(p1.get());
            System.err.println(info1);
        }

        //Project p1 = Project.of(1500, "tester1__at_example_com", 
        //    Paths.get("/var/local/nfs/jupyter-c1/notebooks/users/tester1@example.com/"), mountpoint);
        
        //System.err.println(getReportForProject(p1));
        
        //setupProject(p1);
        //cleanupProject(p1);
        
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
