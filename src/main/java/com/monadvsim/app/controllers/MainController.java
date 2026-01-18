package com.monadvsim.app.controllers;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.ProjectPersistenceService;
import com.monadvsim.app.models.RecentProjectsService;
import com.monadvsim.app.models.VectorLayer;
import com.monadvsim.app.models.RasterLayer;
import com.monadvsim.app.models.AgentLayer;
import com.monadvsim.app.models.Layer;
import javax.swing.JTree;
import com.monadvsim.app.views.MainWindow;
import com.monadvsim.app.views.DialogNewProject;
import com.monadvsim.app.views.DialogNewLayer;
import com.monadvsim.app.views.DialogLoading;
import com.monadvsim.app.views.DialogProjectProperties;
import com.monadvsim.app.views.DialogLayerProperties;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import java.util.List;
import java.util.Arrays;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import javax.swing.JOptionPane;
import org.geotools.swing.tool.PanTool;
import org.geotools.swing.tool.ZoomInTool;
import org.geotools.swing.tool.ZoomOutTool;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Rectangle;
import javax.swing.SwingUtilities;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
import javax.swing.tree.TreePath;
import javax.swing.JColorChooser;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;


public class MainController{
  
  private Project project = null;
  private MainWindow view = null;
  private ProjectPersistenceService projectPersistenceService = null;
  private final RecentProjectsService recentService = new RecentProjectsService();
  private boolean isBaking = false;
  private Timer simulationTimer;
  
  public MainController(Project project, MainWindow view){
    this.project = project;
    this.view = view;
    this.projectPersistenceService = new ProjectPersistenceService();
    this.initListeners();
    updateRecentMenu();
    if (project.getCrsCode() != null) {
        view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
    }
  }
  
  private void initListeners(){
    this.view.getBtnNewProject().addActionListener(l -> handleNewProject());
    this.view.getBtnAddLayer().addActionListener(l -> handleNewLayer());
    this.view.getBtnOpenProject().addActionListener(l -> handleOpenProjectFlow());
    this.view.getBtnSaveProject().addActionListener(l -> handleSaveProject());
    this.view.getBtnRunSim().addActionListener(l -> startSimulation());
    this.view.getBtnPauseSim().addActionListener(l -> stopSimulation());
    this.initMapTools();
    this.handleLayerTreeMouse();
    this.initSimulationClock();;
  }
  
  private void handleOpenProject(File file) {
    DialogLoading loading = new DialogLoading(view, "Opening Project...");
    SwingWorker<Project, Void> worker = new SwingWorker<>() { 
      @Override
      protected Project doInBackground() throws Exception {
        return projectPersistenceService.loadProject(file);
      }
      @Override
      protected void done() {
        try {
          project = get();
          recentService.addProject(file); 
          updateRecentMenu();
          view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
          refreshUI();
          view.setProjectNameInTree(project.getName());
          view.setTitle("MonadVSIM - " + project.getName());
          // Force a full zoom to the new data
          view.getSimulationCanvas().zoomToData();
        } catch (Exception ex) {
          ex.printStackTrace();
          JOptionPane.showMessageDialog(view, "Load failed: " + ex.getMessage());
        } finally { loading.dispose(); }
      }
    };
    worker.execute();
  }
  
  private void handleOpenProjectFlow() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Monad Project", "mvsim")); 
    if (chooser.showOpenDialog(view) == JFileChooser.APPROVE_OPTION) {
      handleOpenProject(chooser.getSelectedFile());
    } 
  }
  
  private void handleNewProject(){
    DialogNewProject dialog = new DialogNewProject();
    dialog.getBtnSelectFolder().addActionListener(l -> {
      File selectedFolder = openFileChooser(dialog, JFileChooser.DIRECTORIES_ONLY, null);
      dialog.getSelectFolderLabel().setText(selectedFolder.getAbsolutePath());
    });
    dialog.getBtnSave().addActionListener(l -> {
    try {
      String name = dialog.getProjectNameField().getText();
      File pFile = new File(dialog.getSelectFolderLabel().getText(), name + ".mvsim");
      project = new Project();
      project.setName(name);
      project.setCrs("EPSG:4326"); 
      project.setProjectFile(pFile);
      projectPersistenceService.saveProject(project, pFile);
      view.setTitle("MonadVSIM - " + name);
      dialog.dispose();
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(dialog, "New project creation failed: " + ex.getMessage());
    }
      dialog.dispose();
    });
  }
  
  private void updateRecentMenu() {
    JMenu menu = view.getRecentProjectsMenu();
    if (menu == null) return;
    menu.removeAll(); 
    List<String> paths = recentService.getRecentProjects();
    for (String path : paths) {
      File file = new File(path);
      JMenuItem item = new JMenuItem(file.getName()); 
      item.setToolTipText(path);
      item.addActionListener(e -> handleOpenProject(file));
      menu.add(item);
    }
  }
  
  private void handleLayerTreeMouse(){
    this.view.getLayerTree().addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        TreePath path = view.getLayerTree().getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent(); 
        int row = view.getLayerTree().getRowForPath(path);
        if (node.getUserObject() instanceof Layer layer) { 
          Rectangle rect = view.getLayerTree().getRowBounds(row);
          if (SwingUtilities.isLeftMouseButton(e) && e.getX() < rect.x + 25) { 
            toggleLayerVisibility(layer);
          } else if (SwingUtilities.isRightMouseButton(e)) {
            view.getLayerTree().setSelectionPath(path);
            showTreeContextMenu(e, layer);
          }
        } else if (SwingUtilities.isRightMouseButton(e) && row == 0) {
          showProjectContextMenu(e);
        }
      }
    });
  }
  
  private void initMapTools(){
    // Map Tools
    this.view.getBtnZoomIn().addActionListener(e -> view.getSimulationCanvas().setActiveTool(new ZoomInTool()));
    this.view.getBtnZoomOut().addActionListener(e -> view.getSimulationCanvas().setActiveTool(new ZoomOutTool()));
    this.view.getBtnPanview().addActionListener(e -> view.getSimulationCanvas().setActiveTool(new PanTool())); 
    this.view.getBtnFullview().addActionListener(e -> {
        view.getSimulationCanvas().setActiveTool(null);
        view.getSimulationCanvas().zoomToData();
    });
  }
  
  private void handleSaveProject(){
    try{
      if (project.getProjectFile() == null) { 
        handleNewProject();
      } else {
        projectPersistenceService.saveProject(project, project.getProjectFile());
        JOptionPane.showMessageDialog(view, "Project Saved Successfully."); 
      }
    }catch(Exception e){
      e.printStackTrace();   
    }
  }
  
  private void handleNewLayer(){
    DialogNewLayer dialog = new DialogNewLayer(view);
    if (dialog.isSucceeded()) { 
      if (dialog.getLayerType().equals("Agent Layer")) {
        handleCreateAgentLayer(dialog.getLayerName(), dialog.getPopulation(), dialog.isWrapAround());
      } else {
        handleCreateSpatialLayer(dialog.getLayerType(), dialog.getLayerName());
      }
    }
  }
  
  private File openFileChooser(JDialog parent, int mode, List<String> ext){
    File selectedFile = null;
    if( mode == JFileChooser.DIRECTORIES_ONLY ){
      selectedFile = openFileChooserFolderMode(parent, mode);
    }else{
      selectedFile = openFileChooserFileMode(parent, mode, ext);
    }
    return selectedFile;
  }
  
  private File openFileChooserFolderMode(JDialog parent, int mode){
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(mode);
    chooser.setAcceptAllFileFilterUsed(false);
    int result = chooser.showOpenDialog(parent);
    if (result == JFileChooser.APPROVE_OPTION) {
        File selectedFolder = chooser.getSelectedFile();
        return selectedFolder;
    }
    return null;
  }
  
  private File openFileChooserFileMode(JDialog parent, int mode, List<String> extensions){
    JFileChooser chooser = new JFileChooser();
    FileNameExtensionFilter filter = new FileNameExtensionFilter(
        "Supported Files (" + String.join(", ", extensions) + ")", 
        extensions.toArray(new String[0])
    );
    chooser.setFileFilter(filter);
    int returnVal = chooser.showOpenDialog(parent);
    if(returnVal == JFileChooser.APPROVE_OPTION) {
        return chooser.getSelectedFile();
    }
    return null;
  }
  
  private void refreshUI() {
    view.updateLayerTree(project.getLayers());
    view.getSimulationCanvas().updateLayers(project.getLayers(), project.getProjectFile()); 
  }
  
  private void toggleLayerVisibility(Layer layer) {
    layer.setVisible(!layer.isVisible());
    refreshUI();
  }

  private void handleCreateAgentLayer(String name, int population, boolean wrap) {
    AgentLayer al = new AgentLayer(name);
    al.setWrapAround(wrap); 
    al.setPopulation(population, project);
    project.getLayers().add(al);
    refreshUI(); 
  }

  private void handleCreateSpatialLayer(String type, String name) {
    JFileChooser chooser = new JFileChooser();
    if (chooser.showOpenDialog(view) == JFileChooser.APPROVE_OPTION) {
      File layerFile = chooser.getSelectedFile();
      Layer newLayer = type.equals("Vector Layer") ? new VectorLayer(name, layerFile.getAbsolutePath()) : new RasterLayer(name, layerFile.getAbsolutePath());
      project.getLayers().add(newLayer);
      refreshUI();
    } 
  }
  
  private void handleProjectProperties() {
    DialogProjectProperties dialog = new DialogProjectProperties(view, project.getName(), project.getCrsCode());
    dialog.getBtnApply().addActionListener(e -> { 
      project.setName(dialog.getProjectName());
      project.setCrsCode(dialog.getSelectedCrs());
      view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
      refreshUI();
      dialog.dispose();
    });
    dialog.setVisible(true); 
  }
  
  private void showProjectContextMenu(MouseEvent e) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem prop = new JMenuItem("Project Properties..."); 
    prop.addActionListener(al -> handleProjectProperties());
    menu.add(prop);
    menu.show(e.getComponent(), e.getX(), e.getY());
  } 
  
  private void showTreeContextMenu(MouseEvent e, Layer layer) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem prop = new JMenuItem("Layer Properties...");
    prop.addActionListener(al -> handleLayerProperties(layer));
    menu.add(prop);
    menu.addSeparator();
    JMenuItem remove = new JMenuItem("Remove Layer"); 
    remove.addActionListener(al -> { 
      project.getLayers().remove(layer); 
      refreshUI(); 
    });
    menu.add(remove); 
    menu.show(e.getComponent(), e.getX(), e.getY());
  }
  
  private void handleLayerProperties(Layer layer) {
    DialogLayerProperties dialog = new DialogLayerProperties(view, layer);
    dialog.setVisible(true);
    if (dialog.isConfirmed()) {
      layer.setName(dialog.getLayerName());
      layer.setVisible(dialog.isVisible());
      layer.setCrsCode(dialog.getSelectedCrs());
      if (layer instanceof AgentLayer al) {
        al.setColorHex(dialog.getColorHex());
        al.setWrapAround(dialog.isWrap());
        al.setColorHex(dialog.getColorHex());
        al.setTrailsEnabled(dialog.isTrailsEnabled()); // Ensure this is checked!
        if (al.getAgentsCount() != dialog.getPopulation()) {
          al.setPopulation(dialog.getPopulation(), project);
        }
      }else if (layer instanceof VectorLayer vl){
        vl.setColorHex(dialog.getColorHex());
      }
      refreshUI();
    }
  }
  
  private void startSimulation(){
    if (isBaking || (simulationTimer != null && simulationTimer.isRunning())) return;
    if (project.getProjectFile() == null) {
      JOptionPane.showMessageDialog(view, "Please save the project before running simulation.");
      return;
    }
    // Find the first visible agent layer to bake (usually only one is active)
    AgentLayer activeLayer = (AgentLayer) project.getLayers().stream()
    .filter(l -> l instanceof AgentLayer && l.isVisible())
    .findFirst().orElse(null);

    if (activeLayer == null) {
      JOptionPane.showMessageDialog(view, "No visible Agent Layer found.");
      return;
    }

    isBaking = true;
    view.getProgressBar().setVisible(true);
    view.getProgressBar().setStringPainted(true);
    view.getProgressBar().setValue(0);

    new SwingWorker<Boolean, Integer>() {
      @Override
      protected Boolean doInBackground() throws Exception {
        // Bake terrain (Terrain awareness prevents agents from walking on water)
        activeLayer.bakeTerrainCache(project, 1000, progress -> {
          publish(progress);
        });
        return true;
      }

      @Override
      protected void process(List<Integer> chunks) {
        int latest = chunks.get(chunks.size() - 1);
        view.getProgressBar().setValue(latest);
        view.getProgressBar().setString("Baking Terrain: " + latest + "%");
      }

      @Override
      protected void done() {
        try {
          if (get()) {
            simulationTimer.start();
            view.getBtnRunSim().setEnabled(false);
            view.getBtnPauseSim().setEnabled(true);
          }
        } catch (Exception ex) {
          JOptionPane.showMessageDialog(view, "Baking Error: " + ex.getMessage());
        } finally {
          isBaking = false;
          view.getProgressBar().setVisible(false);
        }
      }
    }.execute();

  }
  
  private void stopSimulation(){
    if (simulationTimer != null) simulationTimer.stop();
    view.getBtnRunSim().setEnabled(true);
    view.getBtnPauseSim().setEnabled(false);
  }
  
  private void initSimulationClock() {
    if (simulationTimer != null) simulationTimer.stop();

    int currentDelay = 100;//view.getSliderSimSpeed().getValue();
    simulationTimer = new Timer(currentDelay, e -> { 
      // We use a SwingWorker to ensure physics calculations don't freeze the mouse/UI
      new SwingWorker<Void, Void>() {
        @Override
        protected Void doInBackground() {
          if (project == null) return null;
          // Update all active agent layers
          for (Layer layer : project.getLayers()) {
            if (layer instanceof AgentLayer al && al.isVisible()) {
              al.updateAll(project); 
            }
          }
          return null;
        }
        @Override
        protected void done() {
          // Force the SimulationCanvas to call its paintComponent method
          view.getSimulationCanvas().repaint(); 
        }
      }.execute();
    });
  }

}
