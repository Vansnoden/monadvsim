package com.monadvsim.app.views;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JToolBar;
import javax.swing.JMenuBar;
import javax.swing.JMenu;
import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.ImageIcon;
import java.net.URL;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Dimension;
import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.Layer;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.JProgressBar;
import javax.swing.JComponent;
import java.util.List;
import java.awt.event.KeyEvent;
import java.awt.Toolkit;
import javax.swing.AbstractAction;
import java.awt.event.ActionEvent;
import java.awt.Color;
import java.util.Collections;
import javax.swing.DropMode;

public class MainWindow extends JFrame {

    private Project project = null;
    private JSplitPane splitPane = null;
    private JPanel content = null, optionsPanel = null, canvasPanel = null, statusBar = null;
    private SimulationCanvas simCanvas = null;
    private JToolBar toolbar = null;
    private JButton btnNewProject = null, btnOpenProject = null, btnSaveProject = null;
    private JButton btnAddLayer = null, btnPanview = null, btnZoomIn = null;
    private JButton btnZoomOut = null, btnFullview = null, btnRun = null, btnPause = null;
    private JMenuBar menuBar = null;
    private JMenu projectMenu = null, recentProjectsMenu = null, layerMenu = null, helpMenu = null;
    private JMenuItem newProjectMenuItem = null, saveProjectMenuItem = null, saveAsProjectMenuItem = null;
    private JMenuItem projectPropertiesMenuItem = null, projectExportMenuItem = null, exportProjectMenuItem = null;
    private JMenuItem quitMenuItem = null, addLayerMenuItem = null, removeLayerMenuItem = null;
    private JMenuItem documentationMenuItem = null, donationMenuItem = null;
    private JTree layerTree = null;
    private DefaultMutableTreeNode treeRoot = null;
    private JProgressBar progressBar = null;
    private JLabel lblCoordinates;
    private JLabel lblCRS;

    public MainWindow() {
        this.setTitle("MonadVSIM");
        this.initComponents();
        this.pack();
        this.setResizable(true);
        this.setLocationRelativeTo(null);
        this.setVisible(true);
        this.setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    public void setProjectModel(Project project) {
        this.project = project;
    }

    public JSplitPane getSplitPane() {
        return this.splitPane;
    }

    public JPanel getContentPanel() {
        return this.content;
    }

    public JPanel getOptionsPanel() {
        return this.optionsPanel;
    }

    public JPanel getCanvasPanel() {
        return this.canvasPanel;
    }

    public JPanel getStatusBar() {
        return this.statusBar;
    }

    public JToolBar getToolbar() {
        return this.toolbar;
    }

    public JButton getBtnNewProject() {
        return this.btnNewProject;
    }

    public JButton getBtnOpenProject() {
        return this.btnOpenProject;
    }

    public JButton getBtnSaveProject() {
        return this.btnSaveProject;
    }

    public JButton getBtnAddLayer() {
        return this.btnAddLayer;
    }

    public JButton getBtnPanview() {
        return this.btnPanview;
    }

    public JButton getBtnZoomIn() {
        return this.btnZoomIn;
    }

    public JButton getBtnZoomOut() {
        return this.btnZoomOut;
    }

    public JButton getBtnFullview() {
        return this.btnFullview;
    }

    public JButton getBtnRunSim() {
        return this.btnRun;
    }

    public JButton getBtnPauseSim() {
        return this.btnPause;
    }

    public JMenuBar getJMenuBar() {
        return this.menuBar;
    }

    public JMenu getProjectMenu() {
        return this.projectMenu;
    }

    public JMenu getLayerMenu() {
        return this.layerMenu;
    }

    public JMenu getHelpMenu() {
        return this.helpMenu;
    }

    public JMenuItem getNewProjectMenuItem() {
        return this.newProjectMenuItem;
    }

    public JMenuItem getSaveProjectMenuItem() {
        return this.saveProjectMenuItem;
    }

    public JMenuItem getSaveAsProjectMenuItem() {
        return this.saveAsProjectMenuItem;
    }

    public JMenuItem getProjectPropertiesMenuItem() {
        return this.projectPropertiesMenuItem;
    }

    public JMenuItem getProjectExportMenuItem() {
        return this.projectExportMenuItem;
    }

    public JMenuItem getExportProjectMenuItem() {
        return this.exportProjectMenuItem;
    }

    public JMenuItem getQuitMenuItem() {
        return this.quitMenuItem;
    }

    public JMenuItem getAddLayerMenuItem() {
        return this.addLayerMenuItem;
    }

    public JMenuItem getRemoveLayerMenuItem() {
        return this.removeLayerMenuItem;
    }

    public JMenuItem getDocumentationMenuItem() {
        return this.documentationMenuItem;
    }

    public JMenuItem getDonationMenuItem() {
        return this.donationMenuItem;
    }

    public JMenu getRecentProjectsMenu() {
        return this.recentProjectsMenu;
    }

    public JTree getLayerTree() {
        return layerTree;
    }

    public SimulationCanvas getSimulationCanvas() {
        return this.simCanvas;
    }

    public JProgressBar getProgressBar() {
        return this.progressBar;
    }

    public JLabel getLblCoordinates() {
        return lblCoordinates;
    }

    public JLabel getLblCRS() {
        return lblCRS;
    }

    public void setStatusBarCRS(String crs) {
        if (lblCRS != null) {
            lblCRS.setText("System: " + crs);
        }
    }

    public void setProjectNameInTree(String name) {
        if (treeRoot != null && layerTree != null) {
            treeRoot.setUserObject(name);
            ((DefaultTreeModel) layerTree.getModel()).nodeChanged(treeRoot);
        }
    }

    public void updateLayerTree(List<Layer> layers) {
        treeRoot.removeAllChildren();
        if (layers != null) {
            for (Layer layer : layers) {
                treeRoot.add(new DefaultMutableTreeNode(layer));
            }
        }
        DefaultTreeModel model = (DefaultTreeModel) layerTree.getModel();
        model.nodeStructureChanged(treeRoot);
        for (int i = 0; i < layerTree.getRowCount(); i++) {
            layerTree.expandRow(i);
        }
    }

    public void refreshCanvas() {
        if (this.simCanvas != null && project != null) {
            this.simCanvas.updateLayers(project.getLayers(), project.getProjectFile());
        }
        this.simCanvas.repaint();
    }

    private void setupShortcuts() {
        KeyStroke saveShortcut = KeyStroke.getKeyStroke(KeyEvent.VK_S,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        this.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(saveShortcut, "saveAction");
        this.getRootPane().getActionMap().put("saveAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                btnSaveProject.doClick();
            }
        });
    }

    private void initComponents() {
        this.initMenu();
        this.initContentPanel();
    }

    private void initMenu() {
        this.menuBar = new JMenuBar();
        this.initProjectMenu();
        this.initLayerMenu();
        this.initHelpMenu();
        this.setJMenuBar(menuBar);
    }

    private void initProjectMenu() {
        this.projectMenu = new JMenu("Project");
        this.recentProjectsMenu = new JMenu("Recent Projects");
        this.initProjectMenuItems();
        this.addProjectMenuItems();
        this.menuBar.add(this.projectMenu);
    }

    private void initProjectMenuItems() {
        this.newProjectMenuItem = new JMenuItem("New");
        this.saveProjectMenuItem = new JMenuItem("Save");
        this.saveAsProjectMenuItem = new JMenuItem("Save As");
        this.projectPropertiesMenuItem = new JMenuItem("Properties");
        this.exportProjectMenuItem = new JMenuItem("Export");
        this.quitMenuItem = new JMenuItem("Quit");
    }

    private void addProjectMenuItems() {
        this.projectMenu.add(this.newProjectMenuItem);
        this.projectMenu.add(this.projectMenu);
        this.projectMenu.addSeparator();
        this.projectMenu.add(this.saveProjectMenuItem);
        this.projectMenu.add(this.saveAsProjectMenuItem);
        this.projectMenu.addSeparator();
        this.projectMenu.add(this.projectPropertiesMenuItem);
        this.projectMenu.add(this.exportProjectMenuItem);
        this.projectMenu.addSeparator();
        this.projectMenu.add(this.quitMenuItem);
    }

    private void initLayerMenu() {
        this.layerMenu = new JMenu("Layer");
        this.initLayerMenuItems();
        this.addLayerMenuItems();
        this.menuBar.add(this.layerMenu);
    }

    private void initLayerMenuItems() {
        this.addLayerMenuItem = new JMenuItem("Add Layer");
        this.removeLayerMenuItem = new JMenuItem("Remove Layer");
    }

    private void addLayerMenuItems() {
        this.layerMenu.add(this.addLayerMenuItem);
        this.layerMenu.add(this.removeLayerMenuItem);
    }

    private void initHelpMenu() {
        this.helpMenu = new JMenu("Help");
        this.initHelpMenuItems();
        this.addHelpMenuItem();
        this.menuBar.add(this.helpMenu);
    }

    private void initHelpMenuItems() {
        this.documentationMenuItem = new JMenuItem("Documentation");
        this.donationMenuItem = new JMenuItem("Donate!");
    }

    private void addHelpMenuItem() {
        this.helpMenu.add(this.documentationMenuItem);
        this.helpMenu.add(this.donationMenuItem);
    }

    private ImageIcon loadIcon(String name) {
        URL imageURL = getClass().getResource("/icons/" + name + ".png");
        return (imageURL != null) ? new ImageIcon(imageURL) : null;
    }

    private void initContentPanel() {
        this.content = new JPanel();
        this.content.setPreferredSize(new Dimension(900, 650));
        this.content.setLayout(new BorderLayout());
        this.initToolbar();
        this.initOptionsPanel();
        this.initCanvasPanel();
        this.splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.optionsPanel, this.canvasPanel);
        this.optionsPanel.setMinimumSize(new Dimension(200, 0));
        this.canvasPanel.setMinimumSize(new Dimension(700, 0));
        this.splitPane.setDividerLocation(250);
        this.content.add(this.splitPane, BorderLayout.CENTER);
        this.initStatusBar();
        this.add(this.content);
    }

    private void initToolbar() {
        this.toolbar = new JToolBar();
        this.initToolButtons();
        this.addToolButtonsToToolbar();
        this.toolbar.setFloatable(false);
        this.content.add(this.toolbar, BorderLayout.NORTH);
    }

    private void initToolButtons() {
        this.btnNewProject = new JButton(loadIcon("new"));
        this.btnOpenProject = new JButton(loadIcon("open_project"));
        this.btnSaveProject = new JButton(loadIcon("save_project"));
        this.btnAddLayer = new JButton(loadIcon("add_layer"));
        this.btnPanview = new JButton(loadIcon("hand_pan"));
        this.btnZoomIn = new JButton(loadIcon("zoom_in"));
        this.btnZoomOut = new JButton(loadIcon("zoom_out"));
        this.btnFullview = new JButton(loadIcon("full_view"));
        this.btnRun = new JButton(loadIcon("run_sim"));
        this.btnPause = new JButton(loadIcon("pause_sim"));
        this.initToolButtonsTooltipText();
    }

    private void initToolButtonsTooltipText() {
        this.btnNewProject.setToolTipText("New Project");
        this.btnOpenProject.setToolTipText("Open Project");
        this.btnSaveProject.setToolTipText("Save Project");
        this.btnAddLayer.setToolTipText("Add Layer");
        this.btnPanview.setToolTipText("Pan View");
        this.btnZoomIn.setToolTipText("Zoom In");
        this.btnZoomOut.setToolTipText("Zoom Out");
        this.btnFullview.setToolTipText("Full view");
        this.btnRun.setToolTipText("Run Simulation");
        this.btnPause.setToolTipText("Pause Simulation");
    }

    private void addToolButtonsToToolbar() {
        this.toolbar.add(this.btnNewProject);
        this.toolbar.add(this.btnOpenProject);
        this.toolbar.add(this.btnSaveProject);
        this.toolbar.add(this.btnAddLayer);
        this.toolbar.addSeparator();
        this.toolbar.add(this.btnPanview);
        this.toolbar.add(this.btnZoomIn);
        this.toolbar.add(this.btnZoomOut);
        this.toolbar.add(this.btnFullview);
        this.toolbar.addSeparator();
        this.toolbar.add(this.btnRun);
        this.toolbar.add(this.btnPause);
    }

    private void initOptionsPanel() {
        this.optionsPanel = new JPanel(new BorderLayout());
        this.optionsPanel.setPreferredSize(new Dimension(200, 0));
        this.initProjectExplorer();
    }

    private void initProjectExplorer() {
        this.treeRoot = new DefaultMutableTreeNode("Layers");
        this.layerTree = new JTree(treeRoot);
        this.layerTree.setCellRenderer(new LayerTreeRenderer());
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(new JLabel("Project Explorer"), BorderLayout.NORTH);
        treePanel.add(new JScrollPane(layerTree), BorderLayout.CENTER);
        this.optionsPanel.add(treePanel, BorderLayout.CENTER);
    }

    private void initCanvasPanel() {
        this.canvasPanel = new JPanel(new BorderLayout());
        this.canvasPanel.setPreferredSize(new Dimension(700, 0));
        this.initSimCanvas();
        this.canvasPanel.add(this.simCanvas, BorderLayout.CENTER);
    }

    private void initSimCanvas() {
        this.simCanvas = new SimulationCanvas();
    }

    private void initStatusBar() {
        this.statusBar = new JPanel(new BorderLayout());
        this.statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        // West side: Mouse Coordinates
        this.lblCoordinates = new JLabel("X: 0.00, Y: 0.00");
        this.lblCoordinates.setPreferredSize(new Dimension(200, 20));
        // Center side: CRS display
        this.lblCRS = new JLabel("System: EPSG:4326");
        this.lblCRS.setHorizontalAlignment(JLabel.CENTER);
        // East side: Progress and Branding
        this.progressBar = new JProgressBar();
        this.progressBar.setPreferredSize(new Dimension(150, 18));
        this.progressBar.setVisible(false); // Hide by default
        JPanel eastPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        eastPanel.add(this.progressBar);
        eastPanel.add(new JLabel("monadvsim@2026"));
        this.statusBar.add(lblCoordinates, BorderLayout.WEST);
        this.statusBar.add(lblCRS, BorderLayout.CENTER);
        this.statusBar.add(eastPanel, BorderLayout.EAST);
        this.content.add(this.statusBar, BorderLayout.SOUTH);
    }

}
