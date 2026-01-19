package com.monadvsim.app.controllers;

import com.monadvsim.app.models.*;
import com.monadvsim.app.views.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.Raster;
import java.io.File;
import java.util.List;
import org.geotools.swing.tool.*;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.feature.FeatureIterator;
import org.geotools.coverage.grid.GridCoverage2D;
import java.util.Collections;
import javax.swing.DropMode;



public class MainController {

  private Project project;
  private final MainWindow view;
  private final ProjectPersistenceService pService = new ProjectPersistenceService();
  private final RecentProjectsService recentService = new RecentProjectsService();
  private boolean isBaking = false;
  private Timer simTimer;


  public MainController(Project project, MainWindow view) {
    this.project = project;
    this.view = view;
    this.view.setStatusBarCRS(project.getCrsCode());
    initListeners();
    updateRecentMenu();
    if (project.getCrsCode() != null) {
      view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
    }
  }


  private void initListeners() {
    view.getBtnNewProject().addActionListener(l -> handleNewProject());
    view.getBtnAddLayer().addActionListener(l -> handleNewLayer());
    view.getBtnOpenProject().addActionListener(l -> handleOpenProjectFlow());
    view.getBtnSaveProject().addActionListener(l -> handleSaveProject());
    view.getBtnRunSim().addActionListener(l -> toggleSimulation(true));
    view.getBtnPauseSim().addActionListener(l -> toggleSimulation(false));
    initMapTools();
    handleLayerTreeMouse();
    initSimulationClock();
    handleCoordsUpdate();
  }


  private void handleCoordsUpdate() {
    view.getSimulationCanvas().addMouseMotionListener(new MouseAdapter() {
      public void mouseMoved(MouseEvent e) {
        if (view.getSimulationCanvas().getMapContent() == null) return;
        try {
          AffineTransform at = view.getSimulationCanvas().getScreenToWorldTransform();
          if (at != null) {
            Point2D worldPt = at.transform(e.getPoint(), null);
            view.getLblCoordinates()
            .setText(String.format("X: %.4f, Y: %.4f", worldPt.getX(), worldPt.getY()));
          }
        } catch (Exception ex) {}
      }
    });
  }


  private void handleOpenProject(File file) {
    DialogLoading loading = new DialogLoading(view, "Opening Project ...");
    new SwingWorker<Project, Void>() {
      protected Project doInBackground() throws Exception { 
        return pService.loadProject(file); 
      }
      protected void done() {
        try {
          project = get();
          recentService.addProject(file); 
          updateRecentMenu();
          view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
          refreshUI(); view.setProjectNameInTree(project.getName());
          view.setTitle("MonadVSIM - " + project.getName());
          view.getSimulationCanvas().zoomToData();
        } catch (Exception ex) { 
          JOptionPane.showMessageDialog(view, "Load failed: " + ex.getMessage()); 
        }
        finally { 
          loading.dispose(); 
        }
      }
    }.execute();
  }
  

  private void handleOpenProjectFlow() {
    JFileChooser c = new JFileChooser();
    c.setFileFilter(new FileNameExtensionFilter("Monad Project", "mvsim"));
    if (c.showOpenDialog(view) == JFileChooser.APPROVE_OPTION){
      handleOpenProject(c.getSelectedFile());
    }
  }
  

  private void handleNewProject() {
    DialogNewProject d = new DialogNewProject();
    d.getBtnSelectFolder().addActionListener(l -> {
      JFileChooser c = new JFileChooser();
      c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (c.showOpenDialog(d) == JFileChooser.APPROVE_OPTION){
        d.getSelectFolderLabel().setText(c.getSelectedFile().getAbsolutePath());
      }
    });
    d.getBtnSave().addActionListener(l -> {
      try {
        String n = d.getProjectNameField().getText();
        File f = new File(d.getSelectFolderLabel().getText(), n + ".mvsim");
        project = new Project(); 
        project.setName(n); 
        project.setCrs("EPSG:4326"); 
        project.setProjectFile(f);
        pService.saveProject(project, f);
        view.setTitle("MonadVSIM - " + n);
        refreshUI(); 
        d.dispose();
      } catch (Exception ex) { 
        JOptionPane.showMessageDialog(d, "Error: " + ex.getMessage()); 
      }
    });
    d.setVisible(true);
  }


  private void handleLayerTreeMouse() {
    JTree tree = view.getLayerTree();
    tree.setDragEnabled(true);
    tree.setDropMode(DropMode.INSERT);
    tree.setTransferHandler(new LayerTransferHandler(() -> this.project, this::refreshUI));
    tree.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        
        if (SwingUtilities.isRightMouseButton(e)) {
          tree.setSelectionPath(path);
          if (node.getUserObject() instanceof Layer layer) {
            showTreeContextMenu(e, layer);
          } else if (tree.getRowForPath(path) == 0) {
            showProjectContextMenu(e);
          }
        } 
        
        else if (SwingUtilities.isLeftMouseButton(e)) {
          if (node.getUserObject() instanceof Layer layer) {
            int rowX = tree.getRowBounds(tree.getRowForPath(path)).x;
            if (e.getX() < rowX + 25) {
              layer.setVisible(!layer.isVisible());
              refreshUI();
            }
          }
        }
      }
    });
  }


  private void showTreeContextMenu(MouseEvent e, Layer layer) {
    JPopupMenu m = new JPopupMenu();
    JMenuItem prop = new JMenuItem("Properties"); 
    prop.addActionListener(a -> handleLayerProperties(layer));
    m.add(prop);
    if (layer instanceof AgentLayer al) {
      JMenuItem rule = new JMenuItem("Rules"); 
      rule.addActionListener(a -> new DialogRuleEditor(view, al, project).setVisible(true)); 
      m.add(rule);
    }
    JMenuItem inspect = new JMenuItem("Inspect Data Table"); 
    inspect.addActionListener(a -> openDataTable(layer)); m.add(inspect);
    m.addSeparator();
    JMenuItem rem = new JMenuItem("Remove"); 
    rem.addActionListener(a -> { 
      project.getLayers().remove(layer); refreshUI(); 
    }); 
    m.add(rem);
    m.show(e.getComponent(), e.getX(), e.getY());
  }


  public void openDataTable(Layer layer) {
    try {
      DefaultTableModel model = new DefaultTableModel();
      if (layer instanceof VectorLayer vl){
        model = getVectorTableModel(vl);
      } else if (layer instanceof RasterLayer rl) {
        model = getRasterTableModel(rl);
      } else if (layer instanceof AgentLayer al){
        model = getAgentTableModel(al);
      }
      showDataViewer(layer.getName(), model);
    } catch (Exception ex) { 
      ex.printStackTrace(); 
    }
  }


  private DefaultTableModel getVectorTableModel(VectorLayer vl) throws Exception {
    DefaultTableModel m = new DefaultTableModel();
    SimpleFeatureSource s = (SimpleFeatureSource) vl.getFeatureSource(project.getProjectFile());
    SimpleFeatureCollection coll = s.getFeatures();
    for (AttributeDescriptor ad : coll.getSchema().getAttributeDescriptors()){ 
       m.addColumn(ad.getLocalName());
    }
    try (FeatureIterator<SimpleFeature> it = coll.features()) {
      int count = 0;
      while (it.hasNext() && count++ < 500){
         m.addRow(it.next().getAttributes().toArray());
      }
    }
    return m;
  }


  private DefaultTableModel getRasterTableModel(RasterLayer rl) throws Exception {
    DefaultTableModel m = new DefaultTableModel(new String[]{"X","Y","Value"}, 0);
    GridCoverage2D g = (GridCoverage2D) rl.getGridCoverage(project.getProjectFile());
    Raster r = g.getRenderedImage().getData();
    for (int i = 0; i < 20; i++){
      for (int j = 0; j < 20; j++){
        m.addRow(new Object[]{i, j, r.getSampleDouble(i, j, 0)});
      }
    }
    return m;
  }
  

  private DefaultTableModel getAgentTableModel(AgentLayer al) {
    DefaultTableModel m = new DefaultTableModel(new String[]{"ID","X","Y","Status"}, 0);
    List<Agent> agents = al.getAgents();
    for (int i = 0; i < agents.size(); i++) {
      Agent a = agents.get(i);
      m.addRow(new Object[]{
        i, String.format("%.4f", a.getX()), 
        String.format("%.4f", a.getY()), 
        a.isAlive() ? "Alive" : "Dead"
      });
    }
    return m;
  }


  private void showDataViewer(String title, TableModel m) {
    JDialog d = new JDialog(view, "Data: " + title, false);
    JTable t = new JTable(m); 
    t.setAutoCreateRowSorter(true);
    d.add(new JScrollPane(t)); 
    d.setSize(600, 400); 
    d.setLocationRelativeTo(view); 
    d.setVisible(true);
  }
  

  private void handleNewLayer() {
    DialogNewLayer d = new DialogNewLayer(view);
    if (d.isSucceeded()) {
      if (d.getLayerType().equals("Agent Layer")) {
        AgentLayer al = new AgentLayer(d.getLayerName()); 
        al.setWrapAround(d.isWrapAround());
        al.setPopulation(d.getPopulation(), project);
        project.getLayers().add(0, al);
      } else {
        JFileChooser c = new JFileChooser();
        if (c.showOpenDialog(view) == JFileChooser.APPROVE_OPTION) {
          Layer nl = d.getLayerType().equals("Vector Layer") ? 
            new VectorLayer(d.getLayerName(), c.getSelectedFile().getAbsolutePath()) :
            new RasterLayer(d.getLayerName(), c.getSelectedFile().getAbsolutePath());
          project.getLayers().add(0, nl);
        }
      }
      refreshUI();
      SwingUtilities.invokeLater(() -> {
        view.getSimulationCanvas().zoomToData();
      });
    }
  }


  private void handleLayerProperties(Layer l) {
    DialogLayerProperties d = new DialogLayerProperties(view, l); d.setVisible(true);
    if (d.isConfirmed()) {
      l.setName(d.getLayerName()); 
      l.setVisible(d.isVisible()); 
      l.setOpacity(d.getOpacity());
      if (l instanceof AgentLayer al) { 
        al.setWrapAround(d.isWrap()); 
        al.setPopulation(d.getPopulation(), project); 
        al.setBaseSpeed(d.getBaseSpeed());
        al.setColorHex(d.getColorHex());
      }
      if(l instanceof VectorLayer vl){
        vl.setColorHex(d.getColorHex());
      }
      refreshUI();
    }
  }

  public void toggleSimulation(boolean run) {
    if (run) {
      if (project.getProjectFile() == null) return;
      ensureTerrainBaked(() -> { simTimer.start(); 
      view.getBtnRunSim().setEnabled(false); 
      view.getBtnPauseSim().setEnabled(true); });
    } else {
      if (simTimer != null) simTimer.stop();
      view.getBtnRunSim().setEnabled(true); view.getBtnPauseSim().setEnabled(false);
    }
  }
  

  private void ensureTerrainBaked(Runnable cb) {
    AgentLayer al = null;
    for (Layer l : project.getLayers()) {
      if (l instanceof AgentLayer && l.isVisible()) {
        al = (AgentLayer) l;
        break; 
      }
    }
    if (al == null) return;
    final AgentLayer activeLayer = al;
    if (al.getTerrainCache() == null) {
      isBaking = true; view.getProgressBar().setVisible(true);
      new SwingWorker<Void, Integer>() {
        protected Void doInBackground() throws Exception { 
          activeLayer.bakeTerrainCache(project, 1000, p -> publish(p)); 
          return null; 
        }
        protected void process(List<Integer> c) { 
          view.getProgressBar().setValue(c.get(c.size()-1)); 
        }
        protected void done() { 
          isBaking = false; view.getProgressBar().setVisible(false); cb.run(); 
        }
      }.execute();
    } else cb.run();
  }
  

  private void initSimulationClock() {
    simTimer = new Timer(16, e -> {
      if (project == null || isBaking) return;
      for (Layer l : project.getLayers()) {
        if (l instanceof AgentLayer && l.isVisible()) {
          AgentLayer al = (AgentLayer) l;
          al.updateAll(project);
        }
      }
      view.getSimulationCanvas().repaint();
    });
  }


  private void handleSaveProject() {
    try { 
      pService.saveProject(project, project.getProjectFile()); 
    } catch (Exception e) {} 
  }


  private void initMapTools() {
    view.getBtnZoomIn().addActionListener(e -> view.getSimulationCanvas()
    .setActiveTool(new ZoomInTool()));
    view.getBtnZoomOut().addActionListener(e -> view.getSimulationCanvas()
    .setActiveTool(new ZoomOutTool()));
    view.getBtnPanview().addActionListener(e -> view.getSimulationCanvas()
    .setActiveTool(new PanTool()));
    view.getBtnFullview().addActionListener(e -> { view.getSimulationCanvas()
    .setActiveTool(null); view.getSimulationCanvas().zoomToData(); });
  }

  
  private void refreshUI() { 
    view.updateLayerTree(project.getLayers()); 
    view.getSimulationCanvas().updateLayers(project.getLayers(), project.getProjectFile()); 
    view.getSimulationCanvas().repaint();
  }


  private void updateRecentMenu() { 
    JMenu m = view.getRecentProjectsMenu(); 
    if (m == null) return; m.removeAll(); 
    recentService.getRecentProjects().forEach(p -> { 
      JMenuItem i = new JMenuItem(new File(p).getName()); 
      i.addActionListener(e -> handleOpenProject(new File(p))); m.add(i); 
    }); 
  }


  private void showProjectContextMenu(MouseEvent e) { 
    JPopupMenu m = new JPopupMenu(); JMenuItem p = new JMenuItem("Properties"); 
    p.addActionListener(a -> handleProjectProperties()); m.add(p); 
    m.show(e.getComponent(), e.getX(), e.getY()); 
  }


  private void handleProjectProperties() { 
    DialogProjectProperties d = new DialogProjectProperties(view, 
                                project.getName(), 
                                project.getCrsCode()); 
    d.getBtnApply().addActionListener(e -> { 
      project.setName(d.getProjectName()); project.setCrsCode(d.getSelectedCrs()); 
      view.getSimulationCanvas().updateViewportCRS(project.getCrsCode()); 
      refreshUI(); 
      d.dispose(); 
    }); 
    d.setVisible(true); 
  }
  
  
  public void moveLayerUp() {
    TreePath path = view.getLayerTree().getSelectionPath();
    if (path == null) return;
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    if (node.getUserObject() instanceof Layer) {
      DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
      int listIndex = parent.getIndex(node); // Gets 0-indexed position
      if (listIndex > 0) {
        Collections.swap(project.getLayers(), listIndex, listIndex - 1);
        refreshUI(); //  sync both Tree AND Map
      }
    }
  }
  
  
  private void refreshTree() {
    view.updateLayerTree(project.getLayers());
    for (int i = 0; i < view.getLayerTree().getRowCount(); i++) {
        view.getLayerTree().expandRow(i);
    }
  }

}
