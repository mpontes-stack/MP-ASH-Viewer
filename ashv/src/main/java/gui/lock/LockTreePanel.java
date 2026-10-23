package gui.lock;

import config.Labels;
import config.profile.SqlColProfile;
import core.filter.AshFilter;
import core.lock.LockTree;
import core.lock.ObjectNames;
import gui.filter.FilterPopup;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;
import javax.swing.tree.TreePath;
import lombok.extern.slf4j.Slf4j;
import org.jdesktop.swingx.JXTreeTable;
import org.jdesktop.swingx.decorator.FontHighlighter;
import org.jdesktop.swingx.treetable.AbstractTreeTableModel;
import org.jfree.chart.util.GanttParam;
import org.jfree.chart.util.IDetailPanel;
import profile.IProfile;
import store.StoreManager;

/**
 * "Bloqueios" tab: lock tree of the period selected in the chart (whole chart before a selection)
 */
@Slf4j
public class LockTreePanel extends JPanel implements IDetailPanel {
    private static final int EXPAND_ALL_LIMIT = 300;
    private static final int MAX_COLUMN_WIDTH = 260;

    private final StoreManager storeManager;
    private final AshFilter ashFilter;
    private final Supplier<long[]> defaultRange;
    private final ObjectNames objectNames;

    private volatile long[] selectedRange;
    private boolean pending = true;
    private final AtomicInteger generation = new AtomicInteger();

    private final JLabel periodLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel objectsLabel = new JLabel();
    private final JCheckBox background = new JCheckBox(Labels.getLabel("gui.lock.background"));
    private LockTree lastTree;
    private IProfile lastProfile;
    private final JPanel content = new JPanel(new BorderLayout());

    /**
     * @param defaultRange period used before a selection is made in the chart
     * @param objectNames names of CURRENT_OBJ#, null to show the ids
     * @param reloadOnChange reload on filter change (false for panels rebuilt by their owner)
     */
    public LockTreePanel(StoreManager storeManager, AshFilter ashFilter, Supplier<long[]> defaultRange,
                         ObjectNames objectNames, boolean reloadOnChange) {
        super(new BorderLayout());
        this.storeManager = storeManager;
        this.ashFilter = ashFilter;
        this.defaultRange = defaultRange;
        this.objectNames = objectNames;

        JButton refresh = new JButton(Labels.getLabel("gui.lock.refresh"));
        refresh.setToolTipText(Labels.getLabel("gui.lock.refresh.tooltip"));
        refresh.addActionListener(e -> load());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(refresh);
        background.setToolTipText(Labels.getLabel("gui.lock.background.tooltip"));
        background.addActionListener(e -> {
            if (lastTree != null) show(lastTree, lastProfile);
        });
        top.add(background);
        top.add(periodLabel);
        top.add(statusLabel);
        top.add(objectsLabel);
        add(top, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);

        if (reloadOnChange) {
            ashFilter.addListener(() -> SwingUtilities.invokeLater(this::requestLoad));
        }
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && pending) {
                SwingUtilities.invokeLater(this::requestLoad);
            }
        });
    }

    @Override
    public void LoadDataToDetail(GanttParam ganttParam) {
        selectedRange = new long[]{(long) ganttParam.getBeginTime(), (long) ganttParam.getEndTime()};
        SwingUtilities.invokeLater(this::requestLoad);
    }

    /** Load now when visible, otherwise when the tab is shown */
    private void requestLoad() {
        if (isShowing()) {
            load();
        } else {
            pending = true;
        }
    }

    private void load() {
        pending = false;
        lastTree = null;
        long[] range = selectedRange != null ? selectedRange : defaultRange.get();
        int gen = generation.incrementAndGet();
        if (range == null) {
            showMessage(Labels.getLabel("gui.lock.select"));
            return;
        }
        periodLabel.setText(formatPeriod(range[0], range[1]));
        statusLabel.setText("");
        objectsLabel.setText("");
        showMessage(Labels.getLabel("gui.lock.loading"));

        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        AshFilter.Matcher matcher = ashFilter.matcher(cols == null ? Collections.emptyList() : cols);
        IProfile iProfile = storeManager.getConfigurationManager().getIProfile();

        Thread t = new Thread(() -> {
            LockTree tree;
            try {
                tree = LockTree.load(storeManager, iProfile, matcher, range[0], range[1], () -> generation.get() != gen);
            } catch (Exception e) {
                log.error("Lock tree", e);
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) showMessage(Labels.getLabel("gui.lock.error") + " " + e);
                });
                return;
            }
            if (tree != null) {
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) show(tree, iProfile);
                });
            }
        }, "lock-tree");
        t.setDaemon(true);
        t.start();
    }

    private void show(LockTree tree, IProfile iProfile) {
        if (tree.isNotCollected()) {
            showMessage(Labels.getLabel("gui.lock.not.collected"));
            return;
        }
        lastTree = tree;
        lastProfile = iProfile;
        if (tree.getRoots().isEmpty()) {
            statusLabel.setText("");
            showMessage(Labels.getLabel(ashFilter.isActive() ? "gui.lock.none.filtered" : "gui.lock.none"));
            return;
        }
        List<LockTree.Node> roots = tree.getRoots(background.isSelected());
        int hidden = tree.getRoots().size() - roots.size();
        String hiddenText = hidden == 0 ? "" : "  (" + hidden + " " + Labels.getLabel("gui.lock.status.hidden") + ")";
        statusLabel.setText("—  " + roots.size() + " " + Labels.getLabel("gui.lock.status.roots")
                + ", " + LockTree.blockedSessions(roots) + " " + Labels.getLabel("gui.lock.status.blocked") + hiddenText);
        if (roots.isEmpty()) {
            showMessage(Labels.getLabel("gui.lock.only.background"));
            return;
        }

        LockTreeModel model = new LockTreeModel(roots, objectNames);
        JXTreeTable treeTable = new JXTreeTable(model);
        treeTable.setRootVisible(false);
        treeTable.setShowsRootHandles(true);
        treeTable.setColumnControlVisible(true);
        treeTable.setHorizontalScrollEnabled(true);
        if (model.size() <= EXPAND_ALL_LIMIT) {
            treeTable.expandAll();
        } else {
            for (LockTree.Node root : roots) {
                treeTable.expandPath(new TreePath(new Object[]{model.getRoot(), root}));
            }
        }
        treeTable.setLeafIcon(null);
        treeTable.setOpenIcon(null);
        treeTable.setClosedIcon(null);
        treeTable.addHighlighter(new FontHighlighter(
                (renderer, adapter) -> !adapter.isHierarchical() && treeTable.getPathForRow(adapter.row) != null
                        && treeTable.getPathForRow(adapter.row).getPathCount() == 2,
                treeTable.getFont().deriveFont(Font.BOLD)));
        treeTable.packAll();
        for (int i = 0; i < treeTable.getColumnCount(); i++) {
            TableColumn column = treeTable.getColumnModel().getColumn(i);
            column.setPreferredWidth(Math.min(column.getPreferredWidth(), MAX_COLUMN_WIDTH));
        }
        installFilterPopup(treeTable, iProfile);

        content.removeAll();
        content.add(new JScrollPane(treeTable), BorderLayout.CENTER);
        content.revalidate();
        content.repaint();

        lookupObjectNames(roots, treeTable);
    }

    /** Names of the objects shown, looked up in the background; the table shows the ids meanwhile */
    private void lookupObjectNames(List<LockTree.Node> roots, JXTreeTable treeTable) {
        if (objectNames == null || !objectNames.isAvailable()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        roots.forEach(r -> collectObjects(r, ids));
        List<String> missing = objectNames.missing(ids);
        if (missing.isEmpty()) {
            return;
        }
        int gen = generation.get();
        objectsLabel.setText(Labels.getLabel("gui.lock.objects.loading"));
        Thread t = new Thread(() -> {
            String error = objectNames.lookup(missing);
            SwingUtilities.invokeLater(() -> {
                if (generation.get() != gen) {
                    return;
                }
                objectsLabel.setText(error == null ? "" : Labels.getLabel("gui.lock.objects.error") + " " + error);
                TableColumn column = treeTable.getColumnModel().getColumn(
                        treeTable.convertColumnIndexToView(LockTreeModel.COL_OBJECT));
                treeTable.packColumn(treeTable.convertColumnIndexToView(LockTreeModel.COL_OBJECT), 6);
                column.setPreferredWidth(Math.min(column.getPreferredWidth(), MAX_COLUMN_WIDTH));
                treeTable.repaint();
            });
        }, "lock-tree-objects");
        t.setDaemon(true);
        t.start();
    }

    private static void collectObjects(LockTree.Node node, List<String> ids) {
        ids.add(node.getObject());
        node.getChildren().forEach(c -> collectObjects(c, ids));
    }

    /** Right click: filter by the session, its waiters or the value of the cell */
    private void installFilterPopup(JXTreeTable treeTable, IProfile iProfile) {
        FilterPopup.install(treeTable, ashFilter, (row, col) -> {
            TreePath path = treeTable.getPathForRow(row);
            if (path == null || !(path.getLastPathComponent() instanceof LockTree.Node)) {
                return null;
            }
            LockTree.Node node = (LockTree.Node) path.getLastPathComponent();
            int modelCol = treeTable.convertColumnIndexToModel(col);

            if (modelCol == LockTreeModel.COL_SESSION) {
                List<List<Map.Entry<String, String>>> entries = new ArrayList<>();
                entries.add(Collections.singletonList(FilterPopup.condition(iProfile.getSessColName(), node.getSid())));
                // sessions waiting directly for it (FINAL_BLOCKING_SESSION is often empty in v$session)
                if (!node.getChildren().isEmpty()) {
                    entries.add(Collections.singletonList(FilterPopup.condition(LockTree.BLOCKING_SESSION, node.getSid())));
                }
                return entries;
            }

            String colName = LockTreeModel.filterColumn(modelCol, iProfile);
            // the object column may show the name: filter by the id
            Object value = modelCol == LockTreeModel.COL_OBJECT ? node.getObject() : treeTable.getValueAt(row, col);
            if (colName == null || value == null || String.valueOf(value).isEmpty()) {
                return null;
            }
            return Collections.singletonList(Collections.singletonList(FilterPopup.condition(colName, String.valueOf(value))));
        });
    }

    private void showMessage(String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.removeAll();
        content.add(label, BorderLayout.NORTH);
        content.revalidate();
        content.repaint();
    }

    private static String formatPeriod(long begin, long end) {
        boolean sameDay = new SimpleDateFormat("yyyyMMdd").format(new Date(begin))
                .equals(new SimpleDateFormat("yyyyMMdd").format(new Date(end)));
        SimpleDateFormat f = new SimpleDateFormat(sameDay ? "HH:mm:ss" : "dd/MM HH:mm:ss");
        return f.format(new Date(begin)) + "  –  " + f.format(new Date(end));
    }

    /** Tree table model over {@link LockTree.Node} (invisible root holds the final blockers) */
    static final class LockTreeModel extends AbstractTreeTableModel {
        static final int COL_SESSION = 0, COL_STATE = 1, COL_WAIT = 2, COL_CAUSED = 3, COL_AFFECTED = 4,
                COL_EVENT = 5, COL_SQL_ID = 6, COL_OBJECT = 7, COL_USER = 8, COL_PROGRAM = 9, COL_MACHINE = 10,
                COL_MODULE = 11, COL_FIRST = 12, COL_LAST = 13;
        private static final String[] COLUMNS = {"session", "state", "wait", "caused", "affected", "event", "sqlid",
                "object", "user", "program", "machine", "module", "first", "last"};

        private final List<LockTree.Node> roots;
        private final ObjectNames objectNames;
        private final SimpleDateFormat timeFormat;

        LockTreeModel(List<LockTree.Node> roots, ObjectNames objectNames) {
            super(new Object());
            this.roots = roots;
            this.objectNames = objectNames;
            long first = roots.stream().mapToLong(LockTree.Node::getFirst).min().orElse(0);
            long last = roots.stream().mapToLong(LockTree.Node::getLast).max().orElse(0);
            boolean sameDay = new SimpleDateFormat("yyyyMMdd").format(new Date(first))
                    .equals(new SimpleDateFormat("yyyyMMdd").format(new Date(last)));
            this.timeFormat = new SimpleDateFormat(sameDay ? "HH:mm:ss" : "dd/MM HH:mm:ss");
        }

        /** Raw data column to filter by for a model column, null when none */
        static String filterColumn(int column, IProfile iProfile) {
            switch (column) {
                case COL_USER: return LockTree.USERNAME;
                case COL_PROGRAM: return LockTree.PROGRAM;
                case COL_MACHINE: return LockTree.MACHINE;
                case COL_MODULE: return LockTree.MODULE;
                case COL_SQL_ID: return iProfile.getSqlIdColName();
                case COL_EVENT: return iProfile.getEventColName();
                case COL_OBJECT: return LockTree.CURRENT_OBJ;
                default: return null;
            }
        }

        int size() {
            int[] n = {0};
            roots.forEach(r -> count(r, n));
            return n[0];
        }

        private static void count(LockTree.Node node, int[] n) {
            n[0]++;
            node.getChildren().forEach(c -> count(c, n));
        }

        @Override public int getColumnCount() { return COLUMNS.length; }

        @Override public String getColumnName(int column) {
            return Labels.getLabel("gui.lock.col." + COLUMNS[column]);
        }

        @Override public Class<?> getColumnClass(int column) {
            return column == COL_WAIT || column == COL_CAUSED || column == COL_AFFECTED ? Integer.class : String.class;
        }

        @Override public Object getValueAt(Object node, int column) {
            if (!(node instanceof LockTree.Node)) {
                return null;
            }
            LockTree.Node n = (LockTree.Node) node;
            switch (column) {
                case COL_SESSION: return n.getSerial().isEmpty() ? n.getSid() : n.getSid() + "," + n.getSerial();
                case COL_STATE: return state(n);
                case COL_WAIT: return n.isRoot() ? null : n.getWaitSamples();
                case COL_CAUSED: return n.getCausedSamples() == 0 ? null : n.getCausedSamples();
                case COL_AFFECTED: return n.getAffected() == 0 ? null : n.getAffected();
                case COL_USER: return n.getUsername();
                case COL_PROGRAM: return n.getProgram();
                case COL_MACHINE: return n.getMachine();
                case COL_MODULE: return n.getModule();
                case COL_SQL_ID: return n.getSqlId();
                case COL_EVENT: return n.getEvent();
                case COL_OBJECT: return objectName(n.getObject());
                case COL_FIRST: return n.getFirst() == Long.MAX_VALUE ? "" : timeFormat.format(new Date(n.getFirst()));
                case COL_LAST: return n.getLast() == Long.MIN_VALUE ? "" : timeFormat.format(new Date(n.getLast()));
                default: return null;
            }
        }

        private String objectName(String id) {
            if ("-1".equals(id)) {
                return "";
            }
            String name = objectNames == null ? null : objectNames.get(id);
            return name == null ? id : name;
        }

        private static String state(LockTree.Node n) {
            if (n.isRoot()) {
                if (n.getActiveWhileBlocking() == 0) {
                    return Labels.getLabel("gui.lock.state.inactive");
                }
                long pct = Math.round(n.getActiveWhileBlocking() * 100.0 / n.getBlockingSamples());
                return Labels.getLabel("gui.lock.state.active") + " " + pct + "%";
            }
            return Labels.getLabel(n.getChildren().isEmpty() ? "gui.lock.state.waiting" : "gui.lock.state.waiting.blocking");
        }

        @Override public Object getChild(Object parent, int index) {
            return children(parent).get(index);
        }

        @Override public int getChildCount(Object parent) {
            return children(parent).size();
        }

        @Override public int getIndexOfChild(Object parent, Object child) {
            return children(parent).indexOf(child);
        }

        @Override public boolean isLeaf(Object node) {
            return getChildCount(node) == 0;
        }

        private List<LockTree.Node> children(Object parent) {
            return parent instanceof LockTree.Node ? ((LockTree.Node) parent).getChildren() : roots;
        }
    }
}
