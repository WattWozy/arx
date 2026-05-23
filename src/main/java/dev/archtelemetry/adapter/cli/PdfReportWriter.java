package dev.archtelemetry.adapter.cli;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.domain.ModuleMetrics;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;
import dev.archtelemetry.domain.ViolationRecord;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PdfReportWriter {

    private static final Color BLUE    = new Color(37, 99, 235);
    private static final Color GREEN   = new Color(22, 163, 74);
    private static final Color RED     = new Color(220, 38, 38);
    private static final Color AMBER   = new Color(217, 119, 6);
    private static final Color ROW_ALT = new Color(248, 250, 252);
    private static final Color BORDER  = new Color(226, 232, 240);
    private static final Color DARK    = new Color(15, 23, 42);
    private static final Color MUTED   = new Color(100, 116, 139);

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 20, Font.BOLD,   DARK);
    private static final Font H2_FONT    = new Font(Font.HELVETICA, 13, Font.BOLD,   DARK);
    private static final Font BODY_FONT  = new Font(Font.HELVETICA,  9, Font.NORMAL, DARK);
    private static final Font BOLD_FONT  = new Font(Font.HELVETICA,  9, Font.BOLD,   DARK);
    private static final Font MUTED_FONT = new Font(Font.HELVETICA,  8, Font.NORMAL, MUTED);
    private static final Font MONO_FONT  = new Font(Font.COURIER,    8, Font.NORMAL, DARK);
    private static final Font HDR_FONT   = new Font(Font.HELVETICA,  9, Font.BOLD,   Color.WHITE);

    public static byte[] generate(Trend trend, HealthReport report, List<Snapshot> snapshots) {
        return generate(trend, report, snapshots, List.of());
    }

    public static byte[] generate(Trend trend, HealthReport report, List<Snapshot> snapshots,
                                   List<StaleModuleWarning> staleWarnings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 48, 36);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph title = new Paragraph("Arx Health Report", TITLE_FONT);
            title.setSpacingAfter(4);
            doc.add(title);

            Color trendColor = switch (report.driftDirection()) {
                case IMPROVING -> GREEN;
                case DEGRADING -> RED;
                default        -> AMBER;
            };
            String trendLabel = switch (report.driftDirection()) {
                case IMPROVING -> "IMPROVING";
                case DEGRADING -> "DEGRADING";
                default        -> "STABLE";
            };

            PdfPTable summary = new PdfPTable(3);
            summary.setWidthPercentage(100);
            summary.setSpacingBefore(10);
            summary.setSpacingAfter(18);
            addSummaryCard(summary, String.valueOf(report.snapshotsAnalyzed()), "Snapshots Analyzed", BLUE);
            addSummaryCard(summary, trendLabel, "Architecture Trend", trendColor);
            addSummaryCard(summary, String.valueOf(report.totalViolations()), "Current Violations",
                    report.totalViolations() > 0 ? RED : GREEN);
            doc.add(summary);

            addSection(doc, "Violation Trend");
            List<Trend.SnapshotEntry> entries = trend.entries();
            if (entries.isEmpty()) {
                doc.add(emptyNote("No history available."));
            } else {
                PdfPTable trendTable = new PdfPTable(3);
                trendTable.setWidthPercentage(70);
                trendTable.setHorizontalAlignment(Element.ALIGN_LEFT);
                trendTable.setWidths(new float[]{3f, 5f, 2f});
                trendTable.setSpacingAfter(16);
                addTH(trendTable, "Commit", "Date", "Violations");
                for (int i = 0; i < entries.size(); i++) {
                    Trend.SnapshotEntry e = entries.get(i);
                    Snapshot snap = i < snapshots.size() ? snapshots.get(i) : null;
                    String ts = snap != null ? snap.timestamp().toString().substring(0, 10) : "-";
                    addTR(trendTable, i % 2 == 1,
                            e.commitId().substring(0, Math.min(7, e.commitId().length())),
                            ts,
                            String.valueOf(e.violationCount()));
                }
                doc.add(trendTable);
            }

            if (report.latestProfile() != null && !report.latestProfile().moduleMetrics().isEmpty()) {
                addSection(doc, "Module Metrics");
                List<ModuleMetrics> metrics = report.latestProfile().moduleMetrics().stream()
                        .sorted(Comparator.comparing(m -> m.module().name()))
                        .toList();
                PdfPTable mt = new PdfPTable(9);
                mt.setWidthPercentage(100);
                mt.setWidths(new float[]{3f, 1.2f, 1.2f, 1.2f, 1.5f, 1.2f, 1.5f, 1.5f, 1.5f});
                mt.setSpacingAfter(16);
                addTH(mt, "Module", "Layer", "Fan-In", "Fan-Out", "Instab.", "WMC", "Hotspot", "ChurnAcc", "BusFactor");
                for (int i = 0; i < metrics.size(); i++) {
                    ModuleMetrics m = metrics.get(i);
                    addTR(mt, i % 2 == 1,
                            m.module().name(),
                            m.module().layer() < 0 ? "-" : String.valueOf(m.module().layer()),
                            String.valueOf(m.fanIn()),
                            String.valueOf(m.fanOut()),
                            String.format(Locale.US, "%.2f", m.instability()),
                            String.valueOf(m.wmc()),
                            String.format(Locale.US, "%.1f", m.hotspot()),
                            String.format(Locale.US, "%.2f", m.churnAcceleration()),
                            String.format(Locale.US, "%.2f", m.busFactorRisk()));
                }
                doc.add(mt);
            }

            addSection(doc, "Violations");
            addViolSubSection(doc, "Current",
                    entries.isEmpty() ? List.of() :
                    entries.get(entries.size() - 1).violations().stream()
                            .sorted(Comparator.comparing(v -> v.dependency().source().name()))
                            .map(v -> v.dependency().source().name() + " -> " + v.dependency().target().name())
                            .toList());
            addViolSubSection(doc, "New",
                    report.newViolations().stream()
                            .sorted(Comparator.comparing(v -> v.dependency().source().name()))
                            .map(v -> v.dependency().source().name() + " -> " + v.dependency().target().name())
                            .toList());
            addViolSubSection(doc, "Resolved",
                    report.resolvedViolations().stream()
                            .sorted(Comparator.comparing(v -> v.dependency().source().name()))
                            .map(v -> v.dependency().source().name() + " -> " + v.dependency().target().name())
                            .toList());

            List<ViolationRecord> chronic = report.violationRecords().stream()
                    .filter(ViolationRecord::isChronic)
                    .sorted(Comparator.comparingInt(ViolationRecord::ageInSnapshots).reversed())
                    .toList();
            doc.add(new Paragraph("Chronic", BOLD_FONT));
            if (chronic.isEmpty()) {
                doc.add(emptyNote("  None"));
            } else {
                for (ViolationRecord vr : chronic) {
                    Paragraph p = new Paragraph();
                    p.add(new Chunk("  " + vr.violation().dependency().source().name()
                            + " -> " + vr.violation().dependency().target().name(), MONO_FONT));
                    p.add(new Chunk("  (" + vr.ageInSnapshots() + " snapshots)", MUTED_FONT));
                    p.setSpacingAfter(2);
                    doc.add(p);
                }
            }

            if (!report.instabilityWarnings().isEmpty()) {
                addSection(doc, "Instability Warnings");
                for (var w : report.instabilityWarnings().stream()
                        .sorted(Comparator.comparing(w -> w.module().name()))
                        .toList()) {
                    Paragraph p = new Paragraph();
                    p.add(new Chunk("! " + w.module().name() + ": ", BOLD_FONT));
                    p.add(new Chunk(w.reason(), BODY_FONT));
                    p.setSpacingAfter(3);
                    doc.add(p);
                }
            }

            if (!staleWarnings.isEmpty()) {
                addSection(doc, "Stale Module Warnings");
                for (var w : staleWarnings.stream()
                        .sorted(Comparator.comparing(w -> w.module().name()))
                        .toList()) {
                    Paragraph p = new Paragraph();
                    p.add(new Chunk("! " + w.module().name(), BOLD_FONT));
                    p.add(new Chunk(" -- no files matched this module pattern", BODY_FONT));
                    p.setSpacingAfter(3);
                    doc.add(p);
                }
            }

            doc.close();
        } catch (DocumentException e) {
            throw new RuntimeException("PDF generation failed: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private static void addSummaryCard(PdfPTable table, String value, String label, Color accentColor) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BORDER);
        cell.setBorderWidthTop(3f);
        cell.setBorderColorTop(accentColor);
        cell.setPadding(12);
        cell.setBackgroundColor(Color.WHITE);
        cell.addElement(new Paragraph(value, new Font(Font.HELVETICA, 18, Font.BOLD, accentColor)));
        cell.addElement(new Paragraph(label, MUTED_FONT));
        table.addCell(cell);
    }

    private static void addSection(Document doc, String heading) throws DocumentException {
        Paragraph p = new Paragraph(heading, H2_FONT);
        p.setSpacingBefore(16);
        p.setSpacingAfter(8);
        doc.add(p);
    }

    private static void addTH(PdfPTable table, String... cols) {
        for (String col : cols) {
            PdfPCell cell = new PdfPCell(new Phrase(col, HDR_FONT));
            cell.setBackgroundColor(BLUE);
            cell.setPadding(5);
            cell.setBorderColor(BORDER);
            table.addCell(cell);
        }
    }

    private static void addTR(PdfPTable table, boolean alt, String... values) {
        Color bg = alt ? ROW_ALT : Color.WHITE;
        for (String val : values) {
            PdfPCell cell = new PdfPCell(new Phrase(val, BODY_FONT));
            cell.setBackgroundColor(bg);
            cell.setPadding(4);
            cell.setBorderColor(BORDER);
            table.addCell(cell);
        }
    }

    private static void addViolSubSection(Document doc, String label, List<String> lines) throws DocumentException {
        doc.add(new Paragraph(label, BOLD_FONT));
        if (lines.isEmpty()) {
            doc.add(emptyNote("  None"));
        } else {
            for (String line : lines) {
                Paragraph p = new Paragraph("  " + line, MONO_FONT);
                p.setSpacingAfter(2);
                doc.add(p);
            }
        }
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(4);
        doc.add(spacer);
    }

    private static Paragraph emptyNote(String text) {
        Paragraph p = new Paragraph(text, MUTED_FONT);
        p.setSpacingAfter(4);
        return p;
    }
}
