package dev.archtelemetry.adapter.coverage;

import dev.archtelemetry.application.port.CoverageSource;
import dev.archtelemetry.domain.MethodCoverage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JacocoXmlCoverageSource implements CoverageSource {

    private final Path reportPath;

    public JacocoXmlCoverageSource(Path reportPath) {
        this.reportPath = reportPath;
    }

    @Override
    public List<MethodCoverage> fetchCoverage() {
        try (InputStream in = Files.newInputStream(reportPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external DTD to avoid network fetch and XXE
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);

            List<MethodCoverage> result = new ArrayList<>();
            NodeList classes = doc.getElementsByTagName("class");
            for (int i = 0; i < classes.getLength(); i++) {
                Element clazz = (Element) classes.item(i);
                String className = clazz.getAttribute("name").replace('/', '.');
                NodeList methods = clazz.getElementsByTagName("method");
                for (int j = 0; j < methods.getLength(); j++) {
                    Element method = (Element) methods.item(j);
                    String methodName = method.getAttribute("name");
                    String fqn = className + "#" + methodName;

                    int cc = 1;
                    double lineCoverage = 0.0;

                    NodeList counters = method.getElementsByTagName("counter");
                    for (int k = 0; k < counters.getLength(); k++) {
                        Element counter = (Element) counters.item(k);
                        String type = counter.getAttribute("type");
                        int missed = parseInt(counter.getAttribute("missed"));
                        int covered = parseInt(counter.getAttribute("covered"));
                        int total = missed + covered;
                        if ("COMPLEXITY".equals(type) && total > 0) {
                            cc = total;
                        }
                        if ("LINE".equals(type) && total > 0) {
                            lineCoverage = (double) covered / total;
                        }
                    }
                    result.add(new MethodCoverage(fqn, cc, lineCoverage));
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JaCoCo XML: " + reportPath, e);
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
