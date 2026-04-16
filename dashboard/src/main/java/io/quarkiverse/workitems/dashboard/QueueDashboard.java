package io.quarkiverse.workitems.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;

/**
 * Live queue board dashboard using Tamboui terminal UI.
 *
 * <p>
 * Renders a 4-column table showing document review queue state:
 * rows = review tiers (urgent/standard/routine), columns = states (unassigned/claimed/active).
 *
 * <p>Keybindings:
 * <ul>
 * <li>'r' — run the document review scenario (POST /queue-examples/review/run)</li>
 * <li>'q' — quit</li>
 * </ul>
 */
public class QueueDashboard {

    private static final String[] TIERS = { "review/urgent", "review/standard", "review/routine" };
    private static final String[] STATES = { "unassigned", "claimed", "active" };
    private static final String[] TIER_LABELS = { "Urgent", "Standard", "Routine" };

    private final QueueDataClient client;
    private final AtomicReference<List<WorkItemInfo>> latestItems = new AtomicReference<>(List.of());
    private final AtomicReference<String> statusLine = new AtomicReference<>("Waiting for server...");
    private final AtomicBoolean scenarioRunning = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public QueueDashboard(final QueueDataClient client) {
        this.client = client;
    }

    public void run() throws Exception {
        // Start background polling
        scheduler.scheduleAtFixedRate(this::refreshData, 0, 300, TimeUnit.MILLISECONDS);

        try (final var tui = TuiRunner.create()) {
            tui.run(
                    this::handleEvent,
                    frame -> renderBoard(frame));
        }
    }

    private boolean handleEvent(final Event event, final TuiRunner runner) {
        if (event instanceof KeyEvent k) {
            if (k.isQuit()) {
                scheduler.shutdownNow();
                runner.quit();
                return true;
            }
            if (k.isCharIgnoreCase('r') && !scenarioRunning.get()) {
                runScenario();
                return true;
            }
        }
        return false;
    }

    private void refreshData() {
        try {
            final var items = client.fetchWorkItems();
            latestItems.set(items);
            if (statusLine.get().startsWith("Waiting")) {
                statusLine.set("Connected — press 'r' to run document review scenario, 'q' to quit");
            }
        } catch (Exception e) {
            statusLine.set("Error: " + e.getMessage());
        }
    }

    private void runScenario() {
        scenarioRunning.set(true);
        statusLine.set("Running document review scenario... (watch items move between columns)");
        client.runDocumentReviewScenario().thenAccept(result -> {
            scenarioRunning.set(false);
            statusLine.set("Scenario complete: " + result + " — press 'r' to run again, 'q' to quit");
        });
    }

    private void renderBoard(final dev.tamboui.terminal.Frame frame) {
        final var items = latestItems.get();
        final var area = frame.area();

        // Build the queue grid: tier -> state -> list of titles
        final Map<String, Map<String, List<String>>> grid = buildGrid(items);

        // Build table rows
        final List<Row> rows = new ArrayList<>();
        for (int i = 0; i < TIERS.length; i++) {
            final String tier = TIERS[i];
            final String tierLabel = TIER_LABELS[i];
            final Map<String, List<String>> tierData = grid.getOrDefault(tier, Map.of());

            rows.add(Row.from(
                    Cell.from(tierLabel),
                    Cell.from(formatCell(tierData.getOrDefault("unassigned", List.of()))),
                    Cell.from(formatCell(tierData.getOrDefault("claimed", List.of()))),
                    Cell.from(formatCell(tierData.getOrDefault("active", List.of())))));
        }

        final var block = Block.builder()
                .title(Title.from(" Document Review Queue Board "))
                .borders(Borders.ALL)
                .build();

        final var table = Table.builder()
                .block(block)
                .header(Row.from("Tier", "Unassigned (PENDING)", "Claimed (ASSIGNED)", "Active (IN_PROGRESS)"))
                .rows(rows)
                .widths(Constraint.length(12), Constraint.fill(), Constraint.fill(), Constraint.fill())
                .build();

        // Split the area: table fills most of the space, status line at bottom
        final List<Rect> sections = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(area);

        final Rect tableArea = sections.get(0);
        final Rect statusArea = sections.get(1);

        final var tableState = new TableState();
        frame.renderStatefulWidget(table, tableArea, tableState);
        frame.renderWidget(Paragraph.from(statusLine.get()), statusArea);
    }

    private Map<String, Map<String, List<String>>> buildGrid(final List<WorkItemInfo> items) {
        final Map<String, Map<String, List<String>>> grid = new LinkedHashMap<>();
        for (final String tier : TIERS) {
            final Map<String, List<String>> tierMap = new LinkedHashMap<>();
            for (final String state : STATES) {
                tierMap.put(state, new ArrayList<>());
            }
            grid.put(tier, tierMap);
        }

        for (final WorkItemInfo item : items) {
            final String tier = item.tier();
            final String state = item.state();
            if (tier != null && state != null && grid.containsKey(tier)) {
                grid.get(tier).get(state).add(truncate(item.title(), 28));
            }
        }
        return grid;
    }

    private String formatCell(final List<String> titles) {
        if (titles.isEmpty()) return "\u2014"; // em dash
        if (titles.size() == 1) return titles.get(0);
        return titles.get(0) + " (+" + (titles.size() - 1) + " more)";
    }

    private String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026"; // ellipsis
    }
}
