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
import java.awt.Dimension;
import com.monadvsim.app.models.Project;


public class MainWindow extends JFrame{
  
  private Project projectModel=null;
  private JSplitPane splitPane=null;
  private JPanel content=null, optionsPanel=null, canvasPanel=null, statusBar=null;
  private JToolBar toolbar=null;
  private JButton btnNewProject=null, btnOpenProject=null, btnSaveProject=null;
  private JButton btnAddLayer=null, btnPanview=null, btnZoomIn=null;
  private JButton btnZoomOut=null, btnFullview=null, btnRun=null, btnPause=null;
  private JMenuBar menuBar=null;
  private JMenu projectMenu=null, layerMenu=null, helpMenu=null;
  private JMenuItem newProjectMenuItem=null, saveProjectMenuItem=null, saveAsProjectMenuItem=null;
  private JMenuItem projectPropertiesMenuItem=null, projectExportMenuItem=null, exportProjectMenuItem=null;
  private JMenuItem quitMenuItem=null, addLayerMenuItem=null, removeLayerMenuItem=null;
  private JMenuItem documentationMenuItem=null, donationMenuItem=null;
  
  public MainWindow(){
    this.setTitle("MonadVSIM");
    this.initComponents();
    this.pack();
    this.setResizable(true);
    this.setLocationRelativeTo(null);
    this.setVisible(true);
    this.setDefaultCloseOperation(EXIT_ON_CLOSE);
  }
  
  // Getters and Setters
  public void setProjectModel(Project project){ 
    this.projectModel = project;
  }
  
  // Private functions
  
  private void initComponents(){
    this.initMenu();
    this.initContentPanel();
  }
  
  private void initMenu(){
    this.menuBar = new JMenuBar();
    this.initProjectMenu();
    this.initLayerMenu();
    this.initHelpMenu();
    this.setJMenuBar(menuBar);
  }
  
  private void initProjectMenu(){
    this.projectMenu = new JMenu("Project");
    this.initProjectMenuItems();
    this.addProjectMenuItems();
    this.menuBar.add(this.projectMenu);
  }
  
  private void initProjectMenuItems(){
    this.newProjectMenuItem = new JMenuItem("New");
    this.saveProjectMenuItem = new JMenuItem("Save");
    this.saveAsProjectMenuItem = new JMenuItem("Save As");
    this.projectPropertiesMenuItem = new JMenuItem("Properties"); 
    this.exportProjectMenuItem = new JMenuItem("Export");
    this.quitMenuItem = new JMenuItem("Quit");
  }
  
  private void addProjectMenuItems(){
    this.projectMenu.add(this.newProjectMenuItem);
    this.projectMenu.add(this.saveProjectMenuItem);
    this.projectMenu.add(this.saveAsProjectMenuItem);
    this.projectMenu.addSeparator();
    this.projectMenu.add(this.projectPropertiesMenuItem); 
    this.projectMenu.add(this.exportProjectMenuItem);
    this.projectMenu.addSeparator();
    this.projectMenu.add(this.quitMenuItem);
  }
  
  private void initLayerMenu(){
    this.layerMenu = new JMenu("Layer");
    this.initLayerMenuItems();
    this.addLayerMenuItems();
    this.menuBar.add(this.layerMenu);
  }
  
  private void initLayerMenuItems(){
    this.addLayerMenuItem = new JMenuItem("Add Layer");
    this.removeLayerMenuItem = new JMenuItem("Remove Layer");
  }
  
  private void addLayerMenuItems(){
    this.layerMenu.add(this.addLayerMenuItem);
    this.layerMenu.add(this.removeLayerMenuItem);
  }
  
  private void initHelpMenu(){
    this.helpMenu = new JMenu("Help");
    this.initHelpMenuItems();
    this.addHelpMenuItem();
    this.menuBar.add(this.helpMenu);
  }
  
  private void initHelpMenuItems(){
    this.documentationMenuItem = new JMenuItem("Documentation");
    this.donationMenuItem = new JMenuItem("Donate!");
  }
  
  private void addHelpMenuItem(){
    this.helpMenu.add(this.documentationMenuItem);
    this.helpMenu.add(this.donationMenuItem);
  }
  
  private ImageIcon loadIcon(String name){
    URL imageURL = getClass().getResource("/icons/" + name + ".png");
    return (imageURL != null)? new ImageIcon(imageURL) : null;
  }
  
  private void initContentPanel(){
    this.content = new JPanel();
    this.content.setPreferredSize(new Dimension(900, 650));
    this.content.setLayout(new BorderLayout());
    this.initToolbar();
    this.initOptionsPanel();
    this.initCanvasPanel();
    this.splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.optionsPanel, this.canvasPanel);
    this.splitPane.setDividerLocation(250);
    this.splitPane.setEnabled(false);
    this.content.add(this.splitPane, BorderLayout.CENTER);
    this.initStatusBar();
    this.add(this.content);
  }
  
  private void initToolbar(){
    this.toolbar = new JToolBar();
    this.initToolButtons();
    this.addToolButtonsToToolbar();
    this.toolbar.setFloatable(false);
    this.content.add(this.toolbar, BorderLayout.NORTH);
  }
  
  private void initToolButtons(){
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
  
  private void initToolButtonsTooltipText(){
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
  
  private void addToolButtonsToToolbar(){
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
  
  private void initOptionsPanel(){
    this.optionsPanel = new JPanel();
    this.optionsPanel.setPreferredSize(new Dimension(200, 0));
  }
  
  private void initCanvasPanel(){
    this.canvasPanel = new JPanel();
    this.canvasPanel.setPreferredSize(new Dimension(700, 0));
  }
  
  private void initStatusBar(){
    this.statusBar = new JPanel(new BorderLayout());
    this.statusBar.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
    this.statusBar.add(new JLabel("monadvsim@2025"), BorderLayout.EAST);
    this.content.add(this.statusBar, BorderLayout.SOUTH);
  }
  
}
