package gui.connect;

import com.github.windpapi4j.WinDPAPI;
import config.GUIConfig;
import config.Labels;
import config.profile.ConfigProfile;
import config.profile.ConnProfile;
import core.manager.ColorManager;
import core.manager.ConfigurationManager;
import core.manager.ConstantManager;
import core.parameter.ConnectionBuilder;
import core.processing.GetFromRemoteAndStore;
import gui.BasicFrame;
import gui.MainTabbedPane;
import gui.MonitorDbPanel;
import gui.chart.ChartDatasetManager;
import gui.admin.ImportDialog;
import gui.custom.DatabaseIcon;
import gui.custom.AppLogo;
import gui.custom.HintTextField;
import gui.model.ContainerType;
import gui.model.EncryptionType;
import gui.util.ProgressBarUtil;
import gui.util.Themes;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.ItemEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.Icon;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jdesktop.swingx.JXTable;
import org.jdesktop.swingx.JXTextField;
import store.StoreManager;
import utility.StackTraceUtil;

@Slf4j
@Singleton
public class ConnectToDbArea extends JDialog {
    private BasicFrame jFrame;
    private GUIConfig guiConfig;
    private StoreManager storeManager;
    private GetFromRemoteAndStore getFromRemoteAndStore;
    private ColorManager colorManager;
    private ConfigurationManager configurationManager;
    private MonitorDbPanel monitorDbPanel;
    private ChartDatasetManager chartDatasetManager;
    private MainTabbedPane mainTabbedPane;

    private JPanel mainJPanel;
    private JTabbedPane connJTabbedPane;
    private JPanel configMainJPanel;
    private JPanel configOtherJPanel;
    private JPanel buttonPanel;

    private JButton jButtonConnect;
    private JButton jButtonNewConfig;
    private JButton jButtonCopyConfig;
    private JButton jButtonEditConfig;
    private JButton jButtonDeleteConfig;
    private JButton jButtonSaveConfig;
    private JButton jButtonCancel;

    private JButton openFileButton;

    private JButton startStopButton;

    private int width = 340;
    private int height = 340;

    private DefaultTreeModel modelConn;
    private JTree treeConn;


    private static final Color LABEL_COLOR = new Color(0, 70, 213);
    private static final Color LABEL_COLOR_DARK = new Color(88, 157, 246);

    /** A database is monitored: the charts keep the colors of their theme, a new theme waits for the next start */
    private boolean monitoring = false;
    private JComboBox<String> themeBox;
    private JScrollPane tableDataPaneConn;
    private JSplitPane connJSplitPane;

    private JLabel separatorConnLbl = new JLabel(Labels.getLabel("gui.connection.connection"));
    private JLabel separatorProfileLbl = new JLabel(Labels.getLabel("gui.connection.profile"));

    private JLabel connNameLbl = new JLabel(Labels.getLabel("gui.connection.name"), SwingConstants.LEADING);
    private JLabel usernameLbl = new JLabel(Labels.getLabel("gui.connection.username"), SwingConstants.LEADING);
    private JLabel passwordLbl = new JLabel(Labels.getLabel("gui.connection.password"), SwingConstants.LEADING);
    private JLabel urlLbl = new JLabel(Labels.getLabel("gui.connection.url"));
    private JLabel profileNameLbl = new JLabel(Labels.getLabel("gui.connection.name"),SwingConstants.LEADING);
    private JLabel profileDetailLbl = new JLabel(Labels.getLabel("gui.connection.profile.detail"));
    private JLabel profileMessageLbl = new JLabel(Labels.getLabel("gui.connection.profile.message"));
    private JLabel offlineLbl = new JLabel(Labels.getLabel("gui.connection.offline"));

    private JPanel initialLoadPanel;
    private JLabel separatorInitialLoadingLbl = new JLabel(Labels.getLabel("gui.connection.initial.loading"));
    private JLabel initialLoadingOracleEELbl = new JLabel(Labels.getLabel("gui.connection.initial.loading.oracle"));
    private String initialLoadingAllStr = Labels.getLabel("gui.connection.initial.loading.all");
    private String initialLoadingLastStr = Labels.getLabel("gui.connection.initial.loading.last");
    private JLabel initialLoadingMinLbl = new JLabel(Labels.getLabel("gui.connection.initial.loading.min"));

    private JSpinner initialLoadSpinner;
    private SpinnerModel initialSpinnerModel;
    private JRadioButton initialLoadAllRButton;
    private JRadioButton initialLoadLastRButton;

    private JPanel securityCipherPanel;
    private JPanel securityContainerPanel;
    private JRadioButton securityPBECipherRButton;
    private JRadioButton securityBCFipsAesCipherRButton;

    private JRadioButton securityContainerConfiguration;
    private JRadioButton securityContainerRegistry;
    private JRadioButton securityContainerWindowsDPAPI;

    private JLabel separatorRetainLbl = new JLabel(Labels.getLabel("gui.connection.retain"));
    private JLabel retainRawDataLbl = new JLabel(Labels.getLabel("gui.connection.retain.raw"));
    private JLabel retainOlapDataLbl = new JLabel(Labels.getLabel("gui.connection.retain.olap"));
    private JLabel securityLbl = new JLabel(Labels.getLabel("gui.connection.security"));
    private JLabel securityCipherLbl = new JLabel(Labels.getLabel("gui.connection.security.cipher"));
    private JLabel securityContainerLbl = new JLabel(Labels.getLabel("gui.connection.security.container"));

    private JTextField connNameTF = new JTextField();
    private JTextField searchTF = new JTextField();
    private JLabel groupLbl = new JLabel(Labels.getLabel("gui.connection.group"), SwingConstants.LEADING);
    private JComboBox<String> groupBox = new JComboBox<>();
    private JTextField usernameTF = new JTextField();
    private JPasswordField passwordTF = new JPasswordField();
    private JTextField urlTF = new HintTextField(Labels.getLabel("gui.connection.url.hint"));
    private JTextField jarTF = new HintTextField(Labels.getLabel("gui.connection.jar.hint"));
    private JFileChooser jarFC = new JFileChooser();
    private JComboBox<String> profileBox = new JComboBox<>();
    private JCheckBox isOffline = new JCheckBox();
    private JXTextField rawDataDaysRetainTF = new JXTextField();
    private JXTextField olapDataDaysRetainTF = new JXTextField();

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Inject
    public ConnectToDbArea(BasicFrame jFrame,
                           GUIConfig guiConfig,
                           StoreManager storeManager,
                           GetFromRemoteAndStore getFromRemoteAndStore,
                           ColorManager colorManager,
                           ConfigurationManager configurationManager,
                           MonitorDbPanel monitorDbPanel,
                           ChartDatasetManager chartDatasetManager,
                           @Named("startStopButton") JButton startStopButton,
                           @Named("mainTabPane") MainTabbedPane mainTabbedPane){
        MigLayout migLayout = new MigLayout("fill", "1[grow]1", "1[grow]1");
        this.setLayout(migLayout);

        this.jFrame = jFrame;
        this.guiConfig = guiConfig;
        this.storeManager = storeManager;
        this.getFromRemoteAndStore = getFromRemoteAndStore;
        this.colorManager = colorManager;
        this.configurationManager = configurationManager;
        this.monitorDbPanel = monitorDbPanel;
        this.chartDatasetManager = chartDatasetManager;
        this.startStopButton = startStopButton;
        this.mainTabbedPane = mainTabbedPane;

        this.init();
    }

    private void init(){
        MigLayout lmMain = new MigLayout("fill", "[grow]", "[][grow]");
        MigLayout lmConnMain = new MigLayout("ins 10", "[para]0[grow][150lp, fill][60lp][95lp, fill]", "");
        MigLayout lmConnOther = new MigLayout("ins 10", "[para]0[grow][250lp, fill][60lp][95lp, fill]", "");
        MigLayout lmButtonPanel = new MigLayout("fillx", "[50lp][50lp][50lp][50lp]");

        mainJPanel = new JPanel(lmMain);
        connJTabbedPane = new JTabbedPane();
        configMainJPanel = new JPanel(lmConnMain);
        configOtherJPanel = new JPanel(lmConnOther);
        buttonPanel = new JPanel(lmButtonPanel);

        this.init_gui();

        this.add(mainJPanel, "grow");

        this.setTitle(Labels.getLabel("gui.connection.label"));
        this.setSize(width,height);
        this.pack();
    }

    private void init_gui(){

        modelConn = new DefaultTreeModel(new ConnTreeNode(Labels.getLabel("gui.connection.configuration"), null, ""));

        jButtonConnect = new JButton(Labels.getLabel("gui.connection.button.connect"));
        jButtonNewConfig = new JButton(Labels.getLabel("gui.connection.button.new"));
        jButtonDeleteConfig = new JButton(Labels.getLabel("gui.connection.button.delete"));
        jButtonCopyConfig = new JButton(Labels.getLabel("gui.connection.button.copy"));
        jButtonEditConfig = new JButton(Labels.getLabel("gui.connection.button.edit"));
        jButtonSaveConfig = new JButton(Labels.getLabel("gui.connection.button.save"));
        jButtonSaveConfig.setEnabled(false);
        jButtonCancel = new JButton(Labels.getLabel("gui.connection.button.cancel"));
        jButtonCancel.setEnabled(false);

        openFileButton = new JButton(Labels.getLabel("gui.connection.button.open"));

        openFileButton.addActionListener(e -> executor.submit(() -> {
            openFileButton.setEnabled(false);

            int returnVal = jarFC.showOpenDialog(jFrame);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = jarFC.getSelectedFile();
                jarTF.setText(file.getAbsolutePath());
            }

            this.openFileButton.setVisible(true);
            openFileButton.setEnabled(true);
        }));

        treeConn = new JTree(modelConn);
        treeConn.setRootVisible(false);
        treeConn.setShowsRootHandles(true);
        treeConn.setVisibleRowCount(16);
        treeConn.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        treeConn.setCellRenderer(new ConnTreeCellRenderer());
        treeConn.addTreeSelectionListener(e -> {
            ConnTreeNode node = getSelectedNode();
            if (node != null && node.connName != null) {
                selectFromDbAndSetInGui(node.connName);
            }
        });

        groupBox.setEditable(true);
        groupBox.setToolTipText(Labels.getLabel("gui.connection.group.tooltip"));
        if (groupBox.getEditor().getEditorComponent() instanceof JComponent) {
            ((JComponent) groupBox.getEditor().getEditorComponent())
                    .putClientProperty("JTextField.placeholderText", Labels.getLabel("gui.connection.group.hint"));
        }

        tableDataPaneConn =
                new JScrollPane(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableDataPaneConn.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        tableDataPaneConn.setViewportView(treeConn);
        tableDataPaneConn.setVerticalScrollBar(tableDataPaneConn.getVerticalScrollBar());
        tableDataPaneConn.setMinimumSize(new Dimension(100, 0));

        /******/
        Themes.foreground(separatorConnLbl, LABEL_COLOR, LABEL_COLOR_DARK);
        configMainJPanel.add(separatorConnLbl, "gapbottom 1, span, split 2, aligny center");
        configMainJPanel.add(new JSeparator(), "gapleft rel, growx");

        configMainJPanel.add(connNameLbl,   "skip");
        configMainJPanel.add(connNameTF,    "span, growx");

        configMainJPanel.add(groupLbl,   "skip");
        configMainJPanel.add(groupBox,    "span, growx");

        configMainJPanel.add(urlLbl,   "skip");
        configMainJPanel.add(urlTF,    "span, growx");
        urlTF.setToolTipText(Labels.getLabel("gui.connection.url.tooltip"));

        configMainJPanel.add(usernameLbl,   "skip");
        configMainJPanel.add(usernameTF,    "span, growx");

        configMainJPanel.add(passwordLbl,   "skip");
        configMainJPanel.add(passwordTF,    "span, growx");

        Themes.foreground(separatorProfileLbl, LABEL_COLOR, LABEL_COLOR_DARK);
        configMainJPanel.add(separatorProfileLbl, "gapbottom 1, span, split 2, aligny center");
        configMainJPanel.add(new JSeparator(), "gapleft rel, growx");

        Arrays.stream(ConstantManager.Profile.values()).forEach(k -> profileBox.addItem(k.name()));
        profileBox.addItemListener(arg -> {
            if (arg.getStateChange() == ItemEvent.SELECTED) {
                setProfileMessageLblVisible((String) arg.getItem());
            }
        });

        configMainJPanel.add(profileNameLbl,   "skip");
        configMainJPanel.add(profileBox,   "span, growx");

        configMainJPanel.add(profileDetailLbl, "skip");
        configMainJPanel.add(profileMessageLbl,   "span, growx");

        configMainJPanel.add(openFileButton,    "skip, wmin 30");
        configMainJPanel.add(jarTF,    "span, growx, wmin 150");

        configMainJPanel.add(offlineLbl,   "skip");
        configMainJPanel.add(isOffline,   "span, growx");
        isOffline.setSelected(false);

        Themes.foreground(separatorInitialLoadingLbl, LABEL_COLOR, LABEL_COLOR_DARK);
        configOtherJPanel.add(separatorInitialLoadingLbl, "gapbottom 1, span, split 2, aligny center");
        configOtherJPanel.add(new JSeparator(), "gapleft rel, growx");

        MigLayout lmInitialLoad = new MigLayout("", "[30lp][30lp][30lp][30lp]");
        initialLoadPanel = new JPanel(lmInitialLoad);

        initialSpinnerModel = new SpinnerNumberModel(5, 0, 120, 1);
        initialLoadSpinner = new JSpinner(initialSpinnerModel);
        initialLoadSpinner.setToolTipText(Labels.getLabel("gui.connection.initial.loading.tooltip"));

        initialLoadAllRButton = new JRadioButton(initialLoadingAllStr);
        initialLoadAllRButton.addChangeListener(e -> {
            AbstractButton aButton = (AbstractButton)e.getSource();
            ButtonModel aModel = aButton.getModel();
            if (aModel.isSelected()) {
                initialLoadLastRButton.setSelected(false);
                initialLoadSpinner.setEnabled(false);
            }
        });

        initialLoadLastRButton = new JRadioButton(initialLoadingLastStr);
        initialLoadLastRButton.addChangeListener(e -> {
            AbstractButton aButton = (AbstractButton)e.getSource();
            ButtonModel aModel = aButton.getModel();
            if (aModel.isSelected()) {
                initialLoadAllRButton.setSelected(false);
                initialLoadSpinner.setEnabled(true);
            }
        });

        MigLayout securityMigLayout = new MigLayout("", "[30lp][30lp][30lp][30lp]");
        MigLayout securityContainerMigLayout = new MigLayout("", "[30lp][30lp][30lp][30lp][30lp][30lp]");
        securityCipherPanel = new JPanel(securityMigLayout);
        securityContainerPanel = new JPanel(securityContainerMigLayout);

        securityBCFipsAesCipherRButton = new JRadioButton(Labels.getLabel("gui.connection.security.cipher.aes"));
        securityPBECipherRButton = new JRadioButton(Labels.getLabel("gui.connection.security.cipher.pbe"));
        securityBCFipsAesCipherRButton.setToolTipText(Labels.getLabel("gui.connection.security.cipher.aes.tooltip"));
        securityPBECipherRButton.setToolTipText(Labels.getLabel("gui.connection.security.cipher.pbe.tooltip"));

        securityContainerWindowsDPAPI = new JRadioButton(Labels.getLabel("gui.connection.security.container.dbapi"));
        securityContainerRegistry = new JRadioButton(Labels.getLabel("gui.connection.security.container.registry"));
        securityContainerConfiguration = new JRadioButton(Labels.getLabel("gui.connection.security.container.configuration"));
        securityContainerWindowsDPAPI.setToolTipText(Labels.getLabel("gui.connection.security.container.dbapi.tooltip"));
        securityContainerRegistry.setToolTipText(Labels.getLabel("gui.connection.security.container.registry.tooltip"));
        securityContainerConfiguration.setToolTipText(Labels.getLabel("gui.connection.security.container.configuration.tooltip"));

        securityBCFipsAesCipherRButton.addChangeListener(event -> {
            AbstractButton aButton = (AbstractButton) event.getSource();
            ButtonModel model = aButton.getModel();
            if (model.isSelected()) {
                securityBCFipsAesCipherRButton.setSelected(true);
                securityPBECipherRButton.setSelected(false);
            }
        });

        securityPBECipherRButton.addChangeListener(event -> {
            AbstractButton aButton = (AbstractButton) event.getSource();
            ButtonModel model = aButton.getModel();
            if (model.isSelected()) {
                securityPBECipherRButton.setSelected(true);
                securityBCFipsAesCipherRButton.setSelected(false);
            }
        });

        securityContainerWindowsDPAPI.addItemListener(evt -> {
            if(evt.getStateChange() == ItemEvent.SELECTED){
                securityContainerWindowsDPAPI.setSelected(true);
                securityContainerRegistry.setSelected(false);
                securityContainerConfiguration.setSelected(false);

                if (!WinDPAPI.isPlatformSupported()) {
                    String message = "Please, select another option - Registry or Configuration";
                    log.info(message);
                    JOptionPane.showConfirmDialog(this,
                        "Windows Data Protection API (DPAPI) as secure container is not supported. " + message,
                        "Information", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                }
            }
        });

        securityContainerRegistry.addItemListener(evt -> {
            if(evt.getStateChange() == ItemEvent.SELECTED){
                securityContainerRegistry.setSelected(true);
                securityContainerWindowsDPAPI.setSelected(false);
                securityContainerConfiguration.setSelected(false);
            }
        });

        securityContainerConfiguration.addItemListener(evt -> {
            if(evt.getStateChange() == ItemEvent.SELECTED){
                securityContainerConfiguration.setSelected(true);
                securityContainerWindowsDPAPI.setSelected(false);
                securityContainerRegistry.setSelected(false);
            }
        });

        securityCipherPanel.add(securityBCFipsAesCipherRButton, "gap 1");
        securityCipherPanel.add(new JSeparator(SwingConstants.VERTICAL), "growy, hmin 10, alignx center");
        securityCipherPanel.add(securityPBECipherRButton, "gap 1");

        securityContainerPanel.add(securityContainerWindowsDPAPI, "gap 1");
        securityContainerPanel.add(new JSeparator(SwingConstants.VERTICAL), "growy, hmin 10, alignx center");
        securityContainerPanel.add(securityContainerRegistry, "gap 1");
        securityContainerPanel.add(new JSeparator(SwingConstants.VERTICAL), "growy, hmin 10, alignx center");
        securityContainerPanel.add(securityContainerConfiguration, "gap 1");

        initialLoadPanel.add(initialLoadAllRButton, "gap 1");
        initialLoadPanel.add(new JSeparator(SwingConstants.VERTICAL), "growy, hmin 10, alignx center");
        initialLoadPanel.add(initialLoadLastRButton, "gap 1");
        initialLoadPanel.add(initialLoadSpinner);
        initialLoadPanel.add(initialLoadingMinLbl);

        configOtherJPanel.add(initialLoadingOracleEELbl,   "skip");
        configOtherJPanel.add(initialLoadPanel,    "span, growx, wmin 100");

        Themes.foreground(separatorRetainLbl, LABEL_COLOR, LABEL_COLOR_DARK);
        configOtherJPanel.add(separatorRetainLbl, "gapbottom 1, span, split 2, aligny center");
        configOtherJPanel.add(new JSeparator(), "gapleft rel, growx");

        configOtherJPanel.add(retainRawDataLbl,   "skip");
        configOtherJPanel.add(rawDataDaysRetainTF,    "span, growx, wmin 150");
        rawDataDaysRetainTF.setToolTipText(Labels.getLabel("gui.connection.retain.raw.tooltip"));

        configOtherJPanel.add(retainOlapDataLbl,   "skip");
        configOtherJPanel.add(olapDataDaysRetainTF,    "span, growx, wmin 150");
        olapDataDaysRetainTF.setToolTipText(Labels.getLabel("gui.connection.retain.olap.tooltip"));

        Themes.foreground(securityLbl, LABEL_COLOR, LABEL_COLOR_DARK);
        configOtherJPanel.add(securityLbl, "gapbottom 1, span, split 2, aligny center");
        configOtherJPanel.add(new JSeparator(), "gapleft rel, growx");

        configOtherJPanel.add(securityCipherLbl,   "skip");
        configOtherJPanel.add(securityCipherPanel,    "span, growx, wmin 100");
        configOtherJPanel.add(securityContainerLbl,   "skip");
        configOtherJPanel.add(securityContainerPanel,    "span, growx, wmin 100");

        jButtonConnect.addActionListener(e -> {
            // imported histories have no URL: they open offline
            if (urlTF.getText().trim().isEmpty() && !isOffline.isSelected()) {
                isOffline.setSelected(true);
            }
            if (isOffline.isSelected()){
                ProgressBarUtil.runProgressDialog(this::loadObjectsByConnectionNameOffline,
                        jFrame, Labels.getLabel("gui.connection.loading.label") + " " + connNameTF.getText());
                jButtonConnect.setEnabled(false);

                String oldTitle = jFrame.getTitle();
                jFrame.setTitle("Offline: " + oldTitle + " ::: " + connNameTF.getText() + " ::: " + urlTF.getText());
            } else {
                ProgressBarUtil.runProgressDialog(this::loadObjectsByConnectionName,
                        jFrame, Labels.getLabel("gui.connection.loading.label") + " " + connNameTF.getText());
                jButtonConnect.setEnabled(false);

                String oldTitle = jFrame.getTitle();
                jFrame.setTitle(oldTitle + " ::: " + connNameTF.getText() + " ::: " + urlTF.getText());
            }
        });

        jButtonNewConfig.addActionListener(e ->{
            ConnTreeNode selected = getSelectedNode();
            this.setDetailEditable(true);
            this.clearProfileFields();
            groupBox.setSelectedItem(selected == null ? "" : selected.groupPath); // new connection in the selected folder
        });

        jButtonCopyConfig.addActionListener(e ->{
            this.setDetailEditable(true);
            this.copyConnection();
        });

        jButtonDeleteConfig.addActionListener(e -> executor.submit(() -> {
            try {
                jButtonConnect.setEnabled(false);
                jButtonNewConfig.setEnabled(false);
                jButtonCopyConfig.setEnabled(false);
                jButtonDeleteConfig.setEnabled(false);

                int input = JOptionPane.showConfirmDialog(this, // 0=yes, 1=no, 2=cancel
                        "Do you want to delete configuration: " + connNameTF.getText() +"?");
                if (input == 0) {
                    this.deleteConfig();
                    this.loadConfigToGui();
                }

                jButtonConnect.setEnabled(true);
                jButtonNewConfig.setEnabled(true);
                jButtonCopyConfig.setEnabled(true);
                jButtonDeleteConfig.setEnabled(true);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(jFrame, ex.getMessage());
            }
        }));

        jButtonEditConfig.addActionListener(e ->{
            executor.submit(() -> {
                try {
                    setDetailEditable(true);
                    profileBox.setEnabled(false); // Profile unchangeable to keep sqlColProfileList invariable too
                    setNumForDataDaysRetainTF(rawDataDaysRetainTF);
                    setNumForDataDaysRetainTF(olapDataDaysRetainTF);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(jFrame, ex.getMessage());
                }
            });
        });

        jButtonSaveConfig.addActionListener(e ->{
            /** create and save data **/
            executor.submit(() -> {
                try {
                    jButtonSaveConfig.setEnabled(false);
                    jButtonCancel.setEnabled(false);

                    String savedConnName = connNameTF.getText();
                    this.saveData();
                    this.loadConfigToGui();
                    this.selectConfigByName(savedConnName);
                    this.setDetailEditable(false);

                    setTextDataDaysRetainTF(rawDataDaysRetainTF, configurationManager.getRawRetainDays());
                    setOlapDataDaysRetainTF(olapDataDaysRetainTF, configurationManager.getOlapRetainDays());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(jFrame, ex.getMessage());
                }
            });
            /** create and save data **/
        });

        jButtonCancel.addActionListener(e ->{
            /** cancel **/
            executor.submit(() -> {
                jButtonSaveConfig.setEnabled(false);
                jButtonCancel.setEnabled(false);

                this.loadConfigToGui();
                this.setDetailEditable(false);

                setTextDataDaysRetainTF(rawDataDaysRetainTF, configurationManager.getRawRetainDays());
                setOlapDataDaysRetainTF(olapDataDaysRetainTF, configurationManager.getOlapRetainDays());
            });
            /** cancel **/
        });

        this.setDetailEditable(false);

        configurationManager.assignDefaultGroups();
        this.loadConfigToGui();

        buttonPanel.add(jButtonConnect, "gap 1");
        buttonPanel.add(jButtonNewConfig, "gap 1");
        buttonPanel.add(jButtonCopyConfig, "gap 1");
        buttonPanel.add(jButtonDeleteConfig, "gap 1");
        buttonPanel.add(jButtonEditConfig, "gap 1");
        buttonPanel.add(jButtonSaveConfig, "gap 1");
        buttonPanel.add(jButtonCancel, "gap 1");
        // a history backup (.zip) into a new connection or one with an empty history
        JButton jButtonImport = new JButton(Labels.getLabel("gui.import.open"));
        jButtonImport.setToolTipText(Labels.getLabel("gui.import.open.tooltip"));
        jButtonImport.addActionListener(e -> ImportDialog.open(jFrame, configurationManager, () -> {
            configurationManager.assignDefaultGroups();
            this.loadConfigToGui();
        }));
        buttonPanel.add(jButtonImport, "gapleft 12");

        connJTabbedPane.add(configMainJPanel, Labels.getLabel("gui.connection.connection.main"));
        connJTabbedPane.add(configOtherJPanel, Labels.getLabel("gui.connection.connection.other"));

        // Search above the connection tree: filters by name or group while typing
        searchTF.putClientProperty("JTextField.placeholderText", Labels.getLabel("gui.connection.search"));
        searchTF.putClientProperty("JTextField.showClearButton", true);
        searchTF.putClientProperty("JTextField.leadingIcon", new com.formdev.flatlaf.icons.FlatSearchIcon());
        searchTF.setToolTipText(Labels.getLabel("gui.connection.search.tooltip"));
        searchTF.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { loadConfigToGui(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { loadConfigToGui(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { loadConfigToGui(); }
        });
        searchTF.registerKeyboardAction(e -> searchTF.setText(""),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);
        searchTF.addActionListener(e -> { // Enter: first connection found
            selectFirstConnection((ConnTreeNode) modelConn.getRoot());
            treeConn.requestFocusInWindow();
        });

        JPanel connTreePanel = new JPanel(new java.awt.BorderLayout(0, 4));
        connTreePanel.add(searchTF, java.awt.BorderLayout.NORTH);
        connTreePanel.add(tableDataPaneConn, java.awt.BorderLayout.CENTER);
        connTreePanel.setMinimumSize(new Dimension(100, 0));

        connJSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, connTreePanel, connJTabbedPane);
        connJSplitPane.setContinuousLayout(true);
        connJSplitPane.setOneTouchExpandable(true);
        connJSplitPane.setResizeWeight(0.3);

        mainJPanel.add(buttonPanel, "split 3, growx, wmin 200");
        mainJPanel.add(createThemePanel(), "right");
        mainJPanel.add(AppLogo.label(40, 2), "right, gapright 8, wrap");
        mainJPanel.add(connJSplitPane, "grow");

        JdialogComponentListener jdialogComponentListener = new JdialogComponentListener();
        this.addComponentListener(jdialogComponentListener);
    }

    private static final String[] THEMES = {Themes.LIGHT, Themes.DARK, Themes.CLASSIC};

    /**
     * "Tema" combo: changes the open windows before connecting, otherwise only at the next start
     */
    private JPanel createThemePanel() {
        themeBox = new JComboBox<>();
        for (String theme : THEMES) {
            themeBox.addItem(Labels.getLabel("gui.theme." + theme));
        }
        themeBox.setSelectedIndex(Arrays.asList(THEMES).indexOf(Themes.current()));
        themeBox.setToolTipText(Labels.getLabel("gui.theme.tooltip"));
        themeBox.addActionListener(e -> {
            String theme = THEMES[themeBox.getSelectedIndex()];
            if (theme.equals(Themes.current())) {
                return;
            }
            if (monitoring) {
                Themes.save(theme);
                JOptionPane.showMessageDialog(this, Labels.getLabel("gui.theme.next"),
                        Labels.getLabel("gui.theme.title"), JOptionPane.INFORMATION_MESSAGE);
            } else {
                SwingUtilities.invokeLater(() -> {
                    Themes.switchTo(theme);
                    this.pack();
                });
            }
        });

        // language: texts are read when the windows are built, so it applies at the next start
        JComboBox<String> languageBox = new JComboBox<>();
        for (String language : gui.util.Languages.ALL) {
            languageBox.addItem(Labels.getLabel("language." + language));
        }
        languageBox.setSelectedIndex(Arrays.asList(gui.util.Languages.ALL).indexOf(gui.util.Languages.current()));
        languageBox.setToolTipText(Labels.getLabel("preferences.language.tooltip"));
        languageBox.addActionListener(e -> {
            String language = gui.util.Languages.ALL[languageBox.getSelectedIndex()];
            if (language.equals(gui.util.Languages.current())) {
                return;
            }
            gui.util.Languages.save(language);
            JOptionPane.showMessageDialog(this, Labels.getLabel("preferences.language.next"),
                    Labels.getLabel("preferences.language"), JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel panel = new JPanel(new MigLayout("ins 0", "[][]16[][]"));
        panel.add(new JLabel(Labels.getLabel("gui.theme.label")));
        panel.add(themeBox);
        panel.add(new JLabel(Labels.getLabel("preferences.language") + ":"));
        panel.add(languageBox);
        return panel;
    }

    private void setDetailEditable(boolean bParameter){
        connNameTF.setEnabled(bParameter);
        searchTF.setEnabled(!bParameter); // filtering the tree while editing would load another connection
        groupBox.setEnabled(bParameter);
        usernameTF.setEnabled(bParameter);
        passwordTF.setEnabled(bParameter);
        urlTF.setEnabled(bParameter);
        profileBox.setEnabled(bParameter);
        jarTF.setEnabled(bParameter);
        openFileButton.setEnabled(bParameter);
        rawDataDaysRetainTF.setEnabled(bParameter);
        olapDataDaysRetainTF.setEnabled(bParameter);

        jButtonConnect.setEnabled(!bParameter);
        jButtonNewConfig.setEnabled(!bParameter);
        jButtonCopyConfig.setEnabled(!bParameter);
        jButtonDeleteConfig.setEnabled(!bParameter);
        jButtonSaveConfig.setEnabled(bParameter);
        jButtonCancel.setEnabled(bParameter);

        initialLoadAllRButton.setEnabled(bParameter);
        initialLoadLastRButton.setEnabled(bParameter);
        initialLoadSpinner.setEnabled(bParameter);

        securityBCFipsAesCipherRButton.setEnabled(bParameter);
        securityPBECipherRButton.setEnabled(bParameter);

        securityContainerWindowsDPAPI.setEnabled(bParameter);
        securityContainerRegistry.setEnabled(bParameter);
        securityContainerConfiguration.setEnabled(bParameter);
    }

    private void clearProfileFields(){
        connNameTF.setText("");
        groupBox.setSelectedItem("");
        usernameTF.setText("");
        passwordTF.setText("");
        urlTF.setText("");
        jarTF.setText("");
        rawDataDaysRetainTF.setText("");
        olapDataDaysRetainTF.setText("");

        initialLoadAllRButton.setSelected(true);
        initialLoadLastRButton.setSelected(false);

        securityPBECipherRButton.setEnabled(true);
        securityBCFipsAesCipherRButton.setEnabled(true);
        securityPBECipherRButton.setSelected(false);
        securityBCFipsAesCipherRButton.setSelected(false);

        securityContainerRegistry.setEnabled(true);
        securityContainerConfiguration.setEnabled(true);
        securityContainerWindowsDPAPI.setEnabled(true);
        securityContainerRegistry.setSelected(false);
        securityContainerConfiguration.setSelected(false);
        securityContainerWindowsDPAPI.setSelected(false);
    }

    private void copyConnection(){
        connNameTF.setText("");
    }

    private void selectFromDbAndSetInGui(String connName){
        ConnectionBuilder connParameters = configurationManager.getConnectionParameters(connName);

        connNameTF.setText(connParameters.getConnectionName());
        groupBox.setSelectedItem(connParameters.getGroupName());
        usernameTF.setText(connParameters.getUserName());
        passwordTF.setText(connParameters.getPassword());
        urlTF.setText(connParameters.getUrl());
        jarTF.setText(connParameters.getJar());
        profileBox.setSelectedItem(connParameters.getProfile());
        rawDataDaysRetainTF.setText(connParameters.getRawRetainDays());
        olapDataDaysRetainTF.setText(connParameters.getOlapRetainDays());

        setProfileMessageLblVisible((String) profileBox.getSelectedItem());

        if (configurationManager.getConnProfileList().stream().filter(e -> e.getConfigName()
                        .equalsIgnoreCase(String.valueOf(connName))).findFirst().get().isRunning()){
            jButtonConnect.setEnabled(false);
            jButtonConnect.setText(Labels.getLabel("gui.connection.button.connect.running"));
        } else {
            if (configurationManager.getConfigurationName() == null){
                jButtonConnect.setEnabled(true);
            } else {
                jButtonConnect.setEnabled(false);
                jButtonDeleteConfig.setEnabled(false);
                jButtonEditConfig.setEnabled(false);
            }

            jButtonConnect.setText(Labels.getLabel("gui.connection.button.connect"));
        }

        setInitialLoading(connParameters.getInitialLoading());

        if (!rawDataDaysRetainTF.isEnabled()) {
            setTextDataDaysRetainTF(rawDataDaysRetainTF, Integer.parseInt(connParameters.getRawRetainDays()));
        }

        if (!olapDataDaysRetainTF.isEnabled()) {
            setOlapDataDaysRetainTF(olapDataDaysRetainTF, Integer.parseInt(connParameters.getOlapRetainDays()));
        }

        if (connParameters.getEncryptionType() != null) {
            if (EncryptionType.AES.equals(connParameters.getEncryptionType())) {
                securityBCFipsAesCipherRButton.setSelected(true);
            }
            if (EncryptionType.PBE.equals(connParameters.getEncryptionType())) {
                securityPBECipherRButton.setSelected(true);
            }
            securityBCFipsAesCipherRButton.setEnabled(false);
            securityPBECipherRButton.setEnabled(false);
        } else {
            securityBCFipsAesCipherRButton.setSelected(false);
            securityPBECipherRButton.setSelected(false);
        }

        if (connParameters.getContainerType() != null) {
            if (ContainerType.DPAPI.equals(connParameters.getContainerType())) {
                securityContainerWindowsDPAPI.setSelected(true);
            }
            if (ContainerType.REGISTRY.equals(connParameters.getContainerType())) {
                securityContainerRegistry.setSelected(true);
            }
            if (ContainerType.CONFIGURATION.equals(connParameters.getContainerType())) {
                securityContainerConfiguration.setSelected(true);
            }
            securityContainerWindowsDPAPI.setEnabled(false);
            securityContainerRegistry.setEnabled(false);
            securityContainerConfiguration.setEnabled(false);
        } else {
            securityContainerRegistry.setSelected(false);
            securityContainerConfiguration.setSelected(false);
            securityContainerWindowsDPAPI.setSelected(false);
        }
    }

    private void saveData(){
       ConnectionBuilder connParameters = new ConnectionBuilder.Builder(connNameTF.getText())
                .userName(usernameTF.getText())
                .password(String.valueOf(passwordTF.getPassword()))
                .url(urlTF.getText())
                .jar(jarTF.getText())
                .profile(String.valueOf((profileBox.getSelectedItem())))
                .driverName(configurationManager.getProfileImpl(
                        String.valueOf((profileBox.getSelectedItem()))).getDriverName())
                .initialLoading(initialLoadAllRButton.isSelected() ? "-1" : initialLoadSpinner.getValue().toString())
                .rawRetainDays(rawDataDaysRetainTF.getText())
                .olapRetainDays(olapDataDaysRetainTF.getText())
                .encryptionType(getEncryptionType())
                .containerType(getContainerType())
                .groupName(String.valueOf(groupBox.getEditor().getItem()))
                .build();

        configurationManager.saveConnection(connParameters);

        connParameters.cleanPassword();
    }

    private EncryptionType getEncryptionType() {
        return securityBCFipsAesCipherRButton.isSelected() ? EncryptionType.AES : EncryptionType.PBE;
    }

    private ContainerType getContainerType() {
        if (securityContainerWindowsDPAPI.isSelected()) {
            return ContainerType.DPAPI;
        } else if (securityContainerRegistry.isSelected()) {
            return ContainerType.REGISTRY;
        } else if (securityContainerConfiguration.isSelected()) {
            return ContainerType.CONFIGURATION;
        } else {
            return ContainerType.CONFIGURATION;
        }
    }

    private void deleteConfig(){
        configurationManager.deleteConfig(connNameTF.getText());
    }

    private void loadConfigToGui(){
        ConnTreeNode previous = getSelectedNode();
        String previousConnName = previous == null ? null : previous.connName;

        ConnTreeNode root = new ConnTreeNode(Labels.getLabel("gui.connection.configuration"), null, "");

        List<ConfigProfile> configs = new ArrayList<>(configurationManager.getConnProfileList());
        configs.sort(Comparator.comparing(ConfigProfile::getConfigName, String.CASE_INSENSITIVE_ORDER));

        String search = normalizeForSearch(searchTF.getText());

        for (ConfigProfile config : configs) {
            if (!matchesSearch(search, config)) {
                continue;
            }
            ConnTreeNode parent = root;
            String path = "";
            String groupName = ConfigurationManager.normalizeGroupName(config.getGroupName());
            if (!groupName.isEmpty()) {
                for (String folder : groupName.split("/")) {
                    path = path.isEmpty() ? folder : path + "/" + folder;
                    parent = parent.getOrAddFolder(folder, path);
                }
            }
            ConnTreeNode connNode = new ConnTreeNode(config.getConfigName(), config.getConfigName(), groupName);
            connNode.dbType = DatabaseIcon.typeOf(config.getConnProfile() == null ? null : config.getConnProfile().getProfileName());
            parent.add(connNode);
        }
        root.sortChildren();

        modelConn.setRoot(root);
        for (int i = 0; i < treeConn.getRowCount(); i++) {
            treeConn.expandRow(i);
        }

        // Existing folders for the group field
        Object currentGroup = groupBox.getEditor().getItem();
        groupBox.removeAllItems();
        groupBox.addItem("");
        configs.stream()
                .map(c -> ConfigurationManager.normalizeGroupName(c.getGroupName()))
                .filter(g -> !g.isEmpty())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(groupBox::addItem);
        groupBox.setSelectedItem(currentGroup);

        if (previousConnName == null || !selectConfigByName(previousConnName)) {
            selectFirstConnection(root);
        }
    }

    /**
     * All words of the search must appear in the connection name or group (ignoring case and accents)
     */
    private static boolean matchesSearch(String search, ConfigProfile config) {
        if (search.isEmpty()) {
            return true;
        }
        String text = normalizeForSearch(config.getConfigName() + " " + java.util.Objects.toString(config.getGroupName(), ""));
        for (String word : search.split("\\s+")) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeForSearch(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim();
    }

    private boolean selectConfigByName(String connName) {
        ConnTreeNode node = findConnection((ConnTreeNode) modelConn.getRoot(), connName);
        if (node == null) {
            return false;
        }
        TreePath path = new TreePath(node.getPath());
        treeConn.setSelectionPath(path);
        treeConn.scrollPathToVisible(path);
        return true;
    }

    private void selectFirstConnection(ConnTreeNode root) {
        Enumeration<TreeNode> nodes = root.depthFirstEnumeration();
        List<ConnTreeNode> leaves = new ArrayList<>();
        while (nodes.hasMoreElements()) {
            ConnTreeNode node = (ConnTreeNode) nodes.nextElement();
            if (node.connName != null) {
                leaves.add(node);
            }
        }
        // first connection in display order
        for (int i = 0; i < treeConn.getRowCount(); i++) {
            Object last = treeConn.getPathForRow(i).getLastPathComponent();
            if (leaves.contains(last)) {
                treeConn.setSelectionRow(i);
                return;
            }
        }
    }

    private ConnTreeNode findConnection(ConnTreeNode parent, String connName) {
        Enumeration<TreeNode> nodes = parent.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            ConnTreeNode node = (ConnTreeNode) nodes.nextElement();
            if (node.connName != null && node.connName.equalsIgnoreCase(connName)) {
                return node;
            }
        }
        return null;
    }

    private ConnTreeNode getSelectedNode() {
        TreePath path = treeConn == null ? null : treeConn.getSelectionPath();
        return path == null ? null : (ConnTreeNode) path.getLastPathComponent();
    }

    /**
     * Connection nodes with a database icon by type (Oracle, Postgres)
     */
    private static class ConnTreeCellRenderer extends DefaultTreeCellRenderer {
        private final Map<DatabaseIcon.Type, Icon> icons = new EnumMap<>(DatabaseIcon.Type.class);

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof ConnTreeNode && ((ConnTreeNode) value).connName != null) {
                setIcon(icons.computeIfAbsent(((ConnTreeNode) value).dbType, DatabaseIcon::new));
            }
            return c;
        }

        /**
         * A little wider than the text: the tree measures the names before the dialog is shown, and the text drawn
         * on screen (antialiasing / scaling of the desktop) can be a few pixels wider, which cut the names with "..."
         */
        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return d == null ? null : new Dimension(d.width + 12, d.height);
        }
    }

    /**
     * Folder (connName == null) or connection node of the connection tree
     */
    private static class ConnTreeNode extends DefaultMutableTreeNode {
        final String connName;
        final String groupPath;
        DatabaseIcon.Type dbType = DatabaseIcon.Type.OTHER;

        ConnTreeNode(String label, String connName, String groupPath) {
            super(label);
            this.connName = connName;
            this.groupPath = groupPath;
        }

        ConnTreeNode getOrAddFolder(String name, String path) {
            for (int i = 0; i < getChildCount(); i++) {
                ConnTreeNode child = (ConnTreeNode) getChildAt(i);
                if (child.connName == null && child.getUserObject().toString().equalsIgnoreCase(name)) {
                    return child;
                }
            }
            ConnTreeNode folder = new ConnTreeNode(name, null, path);
            add(folder);
            return folder;
        }

        @Override
        public boolean isLeaf() {
            return connName != null;
        }

        /** Folders first, then connections, both alphabetically */
        @SuppressWarnings("unchecked")
        void sortChildren() {
            if (children == null) {
                return;
            }
            children.sort((a, b) -> {
                ConnTreeNode n1 = (ConnTreeNode) a;
                ConnTreeNode n2 = (ConnTreeNode) b;
                if ((n1.connName == null) != (n2.connName == null)) {
                    return n1.connName == null ? -1 : 1;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(n1.toString(), n2.toString());
            });
            for (int i = 0; i < getChildCount(); i++) {
                ((ConnTreeNode) getChildAt(i)).sortChildren();
            }
        }
    }

    private void loadObjectsByConnectionName() {
        try {
            configurationManager.loadCurrentConfiguration(connNameTF.getText());

            ConfigProfile configProfile = configurationManager.getCurrentConfiguration();

            getFromRemoteAndStore.initConnection(configProfile);
            getFromRemoteAndStore.initProfile(configurationManager.getIProfile());

            chartDatasetManager.setIProfile(configurationManager.getIProfile());

            monitorDbPanel.setConnProfile(configProfile.getConnProfile());
            monitorDbPanel.initialize();

            getFromRemoteAndStore.loadDataFromRemoteToLocalStore();

            monitorDbPanel.initializeGui();
            monitoring = true;

            monitorDbPanel.setProfile(configurationManager.getIProfile());

            startStopButton.setEnabled(true);
            startStopButton.doClick();

            jButtonConnect.setEnabled(false);
            jButtonConnect.setText(Labels.getLabel("gui.connection.button.connect.running"));

            monitorDbPanel.adddGui();

        } catch (Exception sqlEx) {
            log.error(StackTraceUtil.getCustomStackTrace(sqlEx));
            JOptionPane.showMessageDialog(jFrame, StackTraceUtil.getCustomStackTrace(sqlEx));
        }

        this.setVisible(false);
    }

    private void loadObjectsByConnectionNameOffline(){
        try {
            configurationManager.loadCurrentConfiguration(connNameTF.getText());

            ConnProfile connection = configurationManager.getCurrentConfiguration().getConnProfile();

            getFromRemoteAndStore.initProfile(configurationManager.getIProfile());

            chartDatasetManager.setIProfile(configurationManager.getIProfile());

            monitorDbPanel.setConnProfile(connection);
            monitorDbPanel.initialize();
            getFromRemoteAndStore.loadConvertManager(); //

            storeManager.getOlapDAO().setIProfile(configurationManager.getIProfile());

            monitorDbPanel.loadHistory();

            monitorDbPanel.setIProfile(configurationManager.getIProfile());
            monitorDbPanel.getHistoryPanel().setIProfile(configurationManager.getIProfile());

            jButtonConnect.setEnabled(false);
            jButtonConnect.setText(Labels.getLabel("gui.connection.button.connect.running"));

            monitorDbPanel.addGuiHistory();
            monitorDbPanel.addAdmin();
            monitoring = true;

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(jFrame, ex.getMessage());
        }

        this.setVisible(false);
    }

    private class JdialogComponentListener implements  ComponentListener{
        @Override public void componentResized(ComponentEvent e) { }
        @Override public void componentMoved(ComponentEvent e) { }
        @Override public void componentShown(ComponentEvent e) { }
        @Override public void componentHidden(ComponentEvent e) {
            mainTabbedPane.requestFocus();
        }
    }

    private void setNumForDataDaysRetainTF(JXTextField dataDaysRetainTF) {
        if (dataDaysRetainTF.getText().equalsIgnoreCase(String.valueOf(ConstantManager.RetainData.Never))){
            dataDaysRetainTF.setText(String.valueOf(ConstantManager.RETAIN_DAYS_MIN));
        } else if (dataDaysRetainTF.getText().equalsIgnoreCase(String.valueOf(ConstantManager.RetainData.Always))) {
            dataDaysRetainTF.setText(String.valueOf(ConstantManager.RETAIN_DAYS_MAX));
        }

        dataDaysRetainTF.setEnabled(false);
    }

    private void setTextDataDaysRetainTF(JXTextField dataDaysRetainTF, int retainDays){
        if (retainDays <= ConstantManager.RETAIN_DAYS_MIN) {
            dataDaysRetainTF.setText(String.valueOf(ConstantManager.RetainData.Never));
        } else if (retainDays >= ConstantManager.RETAIN_DAYS_MAX) {
            dataDaysRetainTF.setText(String.valueOf(ConstantManager.RetainData.Always));
        }

        dataDaysRetainTF.setEnabled(false);
    }

    private void setOlapDataDaysRetainTF(JXTextField dataDaysRetainTF, int retainDays){
        if ((retainDays <= ConstantManager.RETAIN_DAYS_MIN)
                | (retainDays >= ConstantManager.RETAIN_DAYS_MAX)) {
            dataDaysRetainTF.setText(String.valueOf(ConstantManager.RetainData.Always));
        }

        dataDaysRetainTF.setEnabled(false);
    }

    private void setProfileMessageLblVisible(String selectedItem) {
        if (selectedItem != null
            && selectedItem.contains(String.valueOf(ConstantManager.Profile.OracleEE))) {
            profileMessageLbl.setVisible(true);
        } else {
            profileMessageLbl.setVisible(false);
        }
    }

    private void setInitialLoading(String initialLoading) {
        if (initialLoading.equals("-1")) {
            initialLoadAllRButton.setSelected(true);
            initialLoadLastRButton.setSelected(false);
            initialLoadSpinner.setEnabled(false);
            initialLoadSpinner.setValue(ConstantManager.INITIAL_LOADING_DEFAULT);
        } else {
            initialLoadAllRButton.setSelected(false);
            initialLoadLastRButton.setSelected(true);
            initialLoadSpinner.setEnabled(connNameTF.isEnabled());
            initialLoadSpinner.setValue(Integer.valueOf(initialLoading));
        }
    }

}
