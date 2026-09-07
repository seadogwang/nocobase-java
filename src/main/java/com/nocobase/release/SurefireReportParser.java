package com.nocobase.release;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses Maven Surefire test report XML files (TEST-*.xml) and aggregates
 * test results across all test suites.
 *
 * <p>Surefire XML structure (surefire-report-3.0.xsd):
 * <pre>
 * &lt;testsuite name="..." tests="N" failures="N" errors="N" skipped="N" time="S"&gt;
 *   &lt;testcase name="..." classname="..." time="S"&gt;
 *     &lt;!-- optional failure/error/skipped child elements --&gt;
 *   &lt;/testcase&gt;
 * &lt;/testsuite&gt;
 * </pre>
 *
 * <p>Usage:
 * <pre>{@code
 * SurefireReport report = SurefireReportParser.parseDirectory(
 *     Paths.get("target/surefire-reports"));
 * System.out.println("Tests: " + report.totalTests());
 * System.out.println("Failures: " + report.totalFailures());
 * System.out.println("Passed: " + report.allPassed());
 * }</pre>
 */
public final class SurefireReportParser {

    private SurefireReportParser() {
        // utility class
    }

    /**
     * Parses all TEST-*.xml files in the given directory and returns an
     * aggregated report.
     *
     * @param reportsDir directory containing surefire XML reports
     * @return aggregated SurefireReport
     * @throws IOException if the directory cannot be read
     */
    public static SurefireReport parseDirectory(Path reportsDir) throws IOException {
        if (!Files.isDirectory(reportsDir)) {
            return SurefireReport.empty();
        }

        List<SurefireReport> suiteReports = new ArrayList<>();

        try (Stream<Path> files = Files.list(reportsDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .forEach(p -> {
                        try {
                            suiteReports.add(parseFile(p));
                        } catch (Exception e) {
                            System.err.println("Warning: failed to parse "
                                    + p.getFileName() + ": " + e.getMessage());
                        }
                    });
        }

        return SurefireReport.merge(suiteReports);
    }

    /**
     * Parses a single TEST-*.xml surefire report file.
     *
     * @param xmlFile path to the surefire XML file
     * @return SurefireReport for that single file
     * @throws Exception if parsing fails
     */
    public static SurefireReport parseFile(Path xmlFile) throws Exception {
        try (InputStream is = new FileInputStream(xmlFile.toFile())) {
            return parseStream(is, xmlFile.getFileName().toString());
        }
    }

    /**
     * Parses a surefire XML report from an InputStream.
     *
     * @param inputStream the XML input stream
     * @param sourceName  descriptive name for error messages
     * @return SurefireReport for the parsed content
     * @throws Exception if parsing fails
     */
    public static SurefireReport parseStream(InputStream inputStream, String sourceName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(inputStream);

        Element root = doc.getDocumentElement();
        if (!"testsuite".equals(root.getNodeName())) {
            throw new IllegalArgumentException("Expected <testsuite> root element, got: " + root.getNodeName());
        }

        int tests = Integer.parseInt(getAttribute(root, "tests", "0"));
        int failures = Integer.parseInt(getAttribute(root, "failures", "0"));
        int errors = Integer.parseInt(getAttribute(root, "errors", "0"));
        int skipped = Integer.parseInt(getAttribute(root, "skipped", "0"));
        double time = Double.parseDouble(getAttribute(root, "time", "0"));
        String name = getAttribute(root, "name", sourceName);

        List<TestCaseResult> testCases = new ArrayList<>();
        NodeList caseNodes = root.getElementsByTagName("testcase");
        for (int i = 0; i < caseNodes.getLength(); i++) {
            Element caseElem = (Element) caseNodes.item(i);
            String caseName = getAttribute(caseElem, "name", "");
            String className = getAttribute(caseElem, "classname", "");
            double caseTime = parseDoubleSafe(getAttribute(caseElem, "time", "0"));

            TestCaseResult.Status status;
            String message = null;
            String type = null;

            if (caseElem.getElementsByTagName("failure").getLength() > 0) {
                status = TestCaseResult.Status.FAILURE;
                Element failure = (Element) caseElem.getElementsByTagName("failure").item(0);
                message = getAttribute(failure, "message", "");
                type = getAttribute(failure, "type", "");
            } else if (caseElem.getElementsByTagName("error").getLength() > 0) {
                status = TestCaseResult.Status.ERROR;
                Element error = (Element) caseElem.getElementsByTagName("error").item(0);
                message = getAttribute(error, "message", "");
                type = getAttribute(error, "type", "");
            } else if (caseElem.getElementsByTagName("skipped").getLength() > 0) {
                status = TestCaseResult.Status.SKIPPED;
                Element skippedElem = (Element) caseElem.getElementsByTagName("skipped").item(0);
                message = getAttribute(skippedElem, "message", "");
            } else {
                status = TestCaseResult.Status.PASSED;
            }

            testCases.add(new TestCaseResult(className, caseName, status, caseTime, message, type));
        }

        return new SurefireReport(name, tests, failures, errors, skipped, time, testCases);
    }

    private static String getAttribute(Element element, String name, String defaultValue) {
        String value = element.getAttribute(name);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}