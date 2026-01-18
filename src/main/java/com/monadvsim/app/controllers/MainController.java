package com.monadvsim.app.controllers;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.ProjectPersistenceService;
import com.monadvsim.app.models.VectorLayer;
import com.monadvsim.app.models.RasterLayer;
import com.monadvsim.app.models.AgentLayer;
import com.monadvsim.app.models.Layer;
import javax.swing.JTree;
import com.monadvsim.app.views.MainWindow;
import com.monadvsim.app.views.DialogNewProject;
import com.monadvsim.app.views.DialogNewLayer;
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



public class MainController{
  
  private Project project = null;
  private MainWindow view = null;
  private ProjectPersistenceService projectPersistenceService = null;
  
  public MainController(Project project, MainWindow view){
    this.project = project;
    this.view = view;
    this.projectPersistenceService = new ProjectPersistenceService();
    this.initListeners();
  }
  
  private void initListeners(){
    this.view.getBtnNewProject().addActionListener(l -> handleNewProject());
    this.view.getBtnAddLayer().addActionListener(l -> handleNewLayer());
    this.initMapTools();
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
  
  private void handleSaveProject() throws Exception {
    if (project.getProjectFile() == null) { 
      handleNewProject();
    } else {
      projectPersistenceService.saveProject(project, project.getProjectFile());
      JOptionPane.showMessageDialog(view, "Project Saved Successfully."); 
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
      Layer newLayer = type.equals("Vector Layer") ? 
        new VectorLayer(name, layerFile.getAbsolutePath()) : new RasterLayer(name, layerFile.getAbsolutePath());
      project.getLayers().add(newLayer);
      refreshUI();
    } 
  }
  
  private void handleProjectProperties() {
    ProjectPropertiesDialog dialog = new ProjectPropertiesDialog(view, project.getName(), project.getCrsCode());
    dialog.getBtnApply().addActionListener(e -> { 
      project.setName(dialog.getProjectName());
      project.setCrsCode(dialog.getSelectedCrs());
      view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
      refreshUI();
      dialog.dispose();
    });
    dialog.setVisible(true); 
  }
  
  private void toggleLayerVisibility(Layer layer) {
    layer.setVisible(!layer.isVisible());
    refreshUI();
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

}
