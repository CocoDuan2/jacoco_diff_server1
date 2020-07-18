package ord.jacoco.diffserver.task;

import org.apache.logging.log4j.util.Strings;
import org.eclipse.jgit.util.StringUtils;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.*;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.startup.ExecutionDataClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

import static java.lang.String.format;

@Component
public class DiffTask {
    @Value("${git-work-dir}")
    private String gitWorkDir;
    @Value("${branch}")
    private String branch;
    @Value("${compare-branch}")
    private String compareBranch;
    @Value("${tag}")
    private String tag;
    @Value("${compare-tag}")
    private String compareTag;
    @Value("${source-dirs}")
    private String sourceDirs;
    @Value("${class-dirs}")
    private String classDirs;
    @Value("${remote-host}")
    private String remoteHost;
    @Value("${remote-port}")
    private String remotePort;
    @Value("${report-dir}")
    private String reportDir;
    @Value("${mysql-jdbc-url}")
    private String mysqlJdbcUrl;
    @Value("${mysql-user}")
    private String mysqlUser;
    @Value("${mysql-password}")
    private String mysqlPassword;

    @Scheduled(initialDelay=10000,cron = "0 0/3 * * * *")
    public void Diff() {
        try {
            String execFile = reportDir.endsWith("/") ? reportDir + "exec/sq_jacoco.exec" :
                    reportDir + "/exec/sq_jacoco.exec";
            String[] hosts = remoteHost.split("\\s*,\\s*");
            Integer port = Integer.parseInt(remotePort);
            for (String host : hosts) {
                System.out.println(format("dump exec start to %s:%d", host, port));
                //下载exec文件
                ExecutionDataClient client = new ExecutionDataClient(
                        new File(execFile),
                        host, port, true
                );
                client.dump();
                if (hosts.length > 0) {
                    Thread.sleep(5000);
                }
                System.out.println(format("dump exec end to %s:%d", host, port));
            }
            //生成报告
            String[] sourceDirsStr = sourceDirs.split("\\s*,\\s*");
            File[] sourceDirFiles = new File[sourceDirsStr.length];

            String[] classDirsStr = classDirs.split("\\s*,\\s*");
            File[] classDirFiles = new File[classDirsStr.length];
            String title = new File(gitWorkDir).getName();
            for (int i = 0; i < classDirsStr.length; ++i) {
                classDirFiles[i] = new File(classDirsStr[i]);
            }
            for (int i = 0; i < sourceDirsStr.length; ++i) {
                sourceDirFiles[i] = new File(sourceDirsStr[i]);
            }

            CoverageBuilder.init(mysqlJdbcUrl, mysqlUser, mysqlPassword, title);
            create(title, new File(execFile),
                    new File(reportDir),
                    sourceDirFiles,
                    classDirFiles,
                    gitWorkDir,
                    branch,
                    compareBranch,
                    tag,
                    compareTag);
            //dump生成报告间隔
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Create the report.
     *
     * @throws IOException
     */
    public static void create(String title,
                              File executionDataFile,
                              File reportDirectory,
                              File[] sourceDirs,
                              File[] classDirs,
                              String gitPath,
                              String branch,
                              String compareBranch,
                              String tag,
                              String compareTag)
            throws IOException {

        // Read the jacoco.exec file. Multiple data files could be merged
        // at this point
        ExecFileLoader execFileLoader = loadExecutionData(executionDataFile);

        // Run the structure analyzer on a single class folder to build up
        // the coverage model. The process would be similar if your classes
        // were in a jar file. Typically you would create a bundle for each
        // class folder and each jar you want in your report. If you have
        // more than one bundle you will need to add a grouping node to your
        // report
        final IBundleCoverage bundleCoverage = analyzeStructure(title, execFileLoader,
                gitPath, branch, compareBranch, tag, compareTag, classDirs);

        createReport(bundleCoverage, reportDirectory, execFileLoader, sourceDirs);

    }

    private static ExecFileLoader loadExecutionData(File executionDataFile) throws IOException {
        ExecFileLoader execFileLoader = new ExecFileLoader();
        execFileLoader.load(executionDataFile);
        return execFileLoader;
    }

    private static IBundleCoverage analyzeStructure(String title, ExecFileLoader execFileLoader,
                                                    String gitPath,
                                                    String branch,
                                                    String compareBranch,
                                                    String tag,
                                                    String compareTag,
                                                    File[] classDirs) throws IOException {
        CoverageBuilder coverageBuilder = null;
        if (StringUtils.isEmptyOrNull(tag)) {
            if (StringUtils.isEmptyOrNull(compareBranch)) {
                coverageBuilder = new CoverageBuilder(
                        gitPath, branch);
            } else {
                coverageBuilder = new CoverageBuilder(
                        gitPath, branch, compareBranch);
            }
        } else if (StringUtils.isEmptyOrNull(compareTag)) {
            System.out.println("compareTag is null");
            System.exit(-1);
        } else {
            coverageBuilder = new CoverageBuilder(
                    gitPath, branch, tag, compareTag);
        }

        final Analyzer analyzer = new Analyzer(
                execFileLoader.getExecutionDataStore(), coverageBuilder);
        for (File classDir : classDirs) {
            analyzer.analyzeAll(classDir);
        }
        return coverageBuilder.getBundle(title);
    }

    private static void createReport(final IBundleCoverage bundleCoverage, File reportDirectory,
                                     ExecFileLoader execFileLoader, File[] sourceDirs)
            throws IOException {

        // Create a concrete report visitor based on some supplied
        // configuration. In this case we use the defaults
        final HTMLFormatter htmlFormatter = new HTMLFormatter();
        final IReportVisitor visitor = htmlFormatter
                .createVisitor(new FileMultiReportOutput(reportDirectory));

        // Initialize the report with all of the execution and session
        // information. At this point the report doesn't know about the
        // structure of the report being created
        visitor.visitInfo(execFileLoader.getSessionInfoStore().getInfos(),
                execFileLoader.getExecutionDataStore().getContents());

        // Populate the report structure with the bundle coverage information.
        // Call visitGroup if you need groups in your report.

        ISourceFileLocator sourceFileLocator;
        if (sourceDirs.length == 1) {
            sourceFileLocator = new DirectorySourceFileLocator(sourceDirs[0], "utf-8", 4);
        } else {
            //多源码路径
            MultiSourceFileLocator sourceLocator = new MultiSourceFileLocator(4);
            for (File sourceFileDir : sourceDirs) {
                sourceLocator.add(new DirectorySourceFileLocator(sourceFileDir, "utf-8", 4));
            }
            sourceFileLocator = sourceLocator;
        }
        visitor.visitBundle(bundleCoverage, sourceFileLocator);
        // Signal end of structure information to allow report to write all
        // information out
        visitor.visitEnd();

    }
}
